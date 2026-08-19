#!/usr/bin/env python3
"""Audit stored word timings against the exact EveryAyah MP3 with CTC.

This is deliberately an *audit*, not a timing generator.  It reads each
stored timing occurrence (including a reciter's re-says) as the fixed
transcript, forces that exact sequence through an Arabic CTC model, and emits
one JSON object per ayah.  It never alters ``data/quran.db``.

The report contains the SHA-256 of the local EveryAyah file, the full stored
occurrence sequence, per-word forced boundaries, residuals from the shipped
boundaries, and CTC label probabilities.  A forced CTC path is useful
independent evidence, but it is not an automatic ear-verdict: low-probability
or high-residual words are explicitly marked ``review`` rather than silently
accepted.

The model and MP3s must already be cached locally.  For a resumable full
reciter audit, for example:

    /home/sammy/qasr/venv/bin/python tools/audit_forced_alignment.py \\
      --reciter-id 1 --output /home/sammy/qasr/audits/alafasy.jsonl --resume

The second CTC witness is MMS/uroman (no Arabic letters in its vocab):

    ... --model MahmoudAshraf/mms-300m-1130-forced-aligner --romanize \\
      --output /tmp/mms.jsonl

For parallel machines/workers, split the deterministic selected-row order:

    ... --all-reciters --shard 1/8 --output /home/sammy/qasr/audits/1-of-8.jsonl

The output is intentionally JSONL: interrupted work is preserved and
``--resume`` skips every ayah already represented in the file.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sqlite3
import subprocess
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

import torch


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DB = ROOT / "data" / "quran.db"
DEFAULT_QASR = Path.home() / "qasr"
MODEL_NAME = "jonatasgrosman/wav2vec2-large-xlsr-53-arabic"
MMS_MODEL_NAME = "MahmoudAshraf/mms-300m-1130-forced-aligner"

# The recognizer's ordinary output is unvowelled.  Normalize Uthmani spellings
# to that alphabet before constructing the fixed transcript.  These are the
# same substantial letter folds as qasr/pipeline.py; all combining marks and
# Quran annotation glyphs are intentionally omitted because they have no model
# labels and are not separately timed words.
MODEL_LETTER_FOLDS = str.maketrans({
    "ٱ": "ا", "أ": "ا", "إ": "ا", "آ": "ا", "ى": "ي",
    "ة": "ه", "ؤ": "و", "ئ": "ي",
})

# Official MMS/uroman Arabic folds from ctc-forced-aligner norm_config["ara"].
# Apply these before romanizing so Uthmani maddah/wasl marks do not become the
# Latin letters "maddah".  Then keep only the MMS latin vocabulary.
MMS_ARABIC_FOLDS = str.maketrans({
    "ٱ": "ا",
    "ٰ": "ا",
    "ۥ": "و",
    "ۦ": "ي",
    "ـ": None,
    "ٓ": None,
    "ٔ": "ء",
    "ٕ": "ء",
    **{chr(code): None for code in range(0x064B, 0x0653)},
})
MMS_VOCAB_CHARS = frozenset("abcdefghijklmnopqrstuvwxyz'")


@dataclass(frozen=True)
class Occurrence:
    """One stored timing segment, never deduplicated by canonical position."""

    position: int
    arabic: str
    normalized: str
    start_ms: int
    end_ms: int


def normalize_for_model(text: str) -> str:
    """Return the model's unvowelled Arabic spelling for one Quran word."""
    letters: list[str] = []
    for original in text:
        # Uthmani text encodes some medial hamzas as a combining mark after a
        # tatweel (for example يَسۡـَٔلُونَ).  It is a consonant, unlike the
        # vowel/recitation marks around it, so retain it before dropping marks.
        if original in ("ٔ", "ٕ"):
            letters.append("ء")
            continue
        # Fold before decomposition: NFKD turns precomposed أ/إ/ؤ/ئ into a
        # base letter plus the same combining hamza mark, which would otherwise
        # accidentally make them behave differently from the model spelling.
        for char in unicodedata.normalize("NFKD", original.translate(MODEL_LETTER_FOLDS)):
            if unicodedata.category(char).startswith("M") or char == "ـ":
                continue
            if "ء" <= char <= "ي":
                letters.append(char)
    return "".join(letters)


def normalize_for_mms(text: str, romanizer) -> str:
    """Return the MMS latin spelling for one Quran word.

    Folds Uthmani marks the way the official MMS aligner does, romanizes with
    uroman ``ara``, then keeps only ``a-z`` and apostrophe.
    """
    folded: list[str] = []
    for char in text.translate(MMS_ARABIC_FOLDS):
        if unicodedata.category(char).startswith("M"):
            continue
        folded.append(char)
    roman = romanizer.romanize_string("".join(folded), lcode="ara")
    letters = [char for char in roman.lower() if char in MMS_VOCAB_CHARS]
    if not letters:
        raise ValueError("romanizer produced no MMS letters")
    return "".join(letters)


def blank_id_for(tokenizer) -> int:
    """Return the CTC blank id: ``<blank>`` when the tokenizer has one, else pad."""
    vocabulary = tokenizer.get_vocab()
    blank_id = getattr(tokenizer, "blank_token_id", None)
    if blank_id is not None:
        return int(blank_id)
    for token in (getattr(tokenizer, "blank_token", None), "<blank>"):
        if token and token in vocabulary:
            return int(vocabulary[token])
    if tokenizer.pad_token_id is not None:
        return int(tokenizer.pad_token_id)
    raise ValueError("CTC tokenizer lacks a usable blank")


def sha256(path: Path) -> str:
    """Fingerprint the exact MP3 whose waveform supplied this evidence."""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def decode_waveform(path: Path, sample_rate: int):
    """Decode one MP3 through FFmpeg's robust MPEG decoder.

    librosa's mpg123 backend can silently return only one corrupt-looking
    frame for valid EveryAyah files.  The audit is acoustic evidence, so every
    row uses the same decoder rather than accepting a truncated waveform.
    """
    try:
        import numpy as np
    except ModuleNotFoundError as error:
        raise RuntimeError("numpy is required; use /home/sammy/qasr/venv/bin/python") from error
    result = subprocess.run(
        [
            "ffmpeg", "-v", "error", "-nostdin", "-i", str(path), "-vn",
            "-ac", "1", "-ar", str(sample_rate), "-f", "f32le", "pipe:1",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode or not result.stdout:
        detail = result.stderr.decode(errors="replace").strip()
        raise RuntimeError(f"ffmpeg could not decode {path}: {detail or result.returncode}")
    if len(result.stdout) % 4:
        raise RuntimeError(f"ffmpeg returned incomplete PCM for {path}")
    return np.frombuffer(result.stdout, dtype="<f4").copy()


def parse_shard(value: str) -> tuple[int, int]:
    """Parse human-friendly 1-based ``PARTS/TOTAL`` shard notation."""
    try:
        part_text, total_text = value.split("/", 1)
        part, total = int(part_text), int(total_text)
    except ValueError as error:
        raise argparse.ArgumentTypeError("shard must be PART/TOTAL, e.g. 1/8") from error
    if total < 1 or not 1 <= part <= total:
        raise argparse.ArgumentTypeError("shard must satisfy 1 <= PART <= TOTAL")
    return part - 1, total


def ctc_expanded_states(labels: Sequence[int], blank_id: int) -> list[int]:
    """Expand CTC labels to blank/label/blank states for Viterbi alignment."""
    states = [blank_id]
    for label in labels:
        states.extend((label, blank_id))
    return states


def forced_ctc_viterbi(
    log_probabilities: torch.Tensor,
    labels: Sequence[int],
    blank_id: int,
) -> tuple[list[int], float]:
    """Return the best CTC state at each frame for a fixed label sequence.

    This is the conventional CTC trellis, including a mandatory blank between
    repeated labels.  The returned state path has one entry per acoustic frame;
    odd expanded states correspond to labels, even states to blanks.
    """
    if log_probabilities.ndim != 2:
        raise ValueError("expected CTC log probabilities shaped [frames, labels]")
    if not labels:
        raise ValueError("cannot force-align an empty transcript")
    frame_count, vocabulary_size = log_probabilities.shape
    if not frame_count:
        raise ValueError("cannot force-align an empty emission sequence")
    if any(label < 0 or label >= vocabulary_size for label in labels):
        raise ValueError("transcript contains a model label outside the vocabulary")

    device = log_probabilities.device
    states = ctc_expanded_states(labels, blank_id)
    state_labels = torch.tensor(states, device=device, dtype=torch.long)
    state_count = len(states)
    negative_infinity = torch.tensor(float("-inf"), device=device)

    previous = torch.full((state_count,), float("-inf"), device=device)
    previous[0] = log_probabilities[0, blank_id]
    if state_count > 1:
        previous[1] = log_probabilities[0, state_labels[1]]
    parents = torch.zeros((frame_count, state_count), dtype=torch.int32, device="cpu")

    # A label can skip its preceding blank only when it differs from the label
    # two states back.  Without this rule CTC would collapse a genuine doubled
    # character (for example the two lam letters in Allah) into one.
    can_skip = torch.zeros(state_count, dtype=torch.bool, device=device)
    for state in range(3, state_count, 2):
        can_skip[state] = state_labels[state] != state_labels[state - 2]

    for frame in range(1, frame_count):
        stay = previous
        advance = torch.cat((negative_infinity.reshape(1), previous[:-1]))
        skip = torch.full_like(previous, float("-inf"))
        skip[2:] = previous[:-2]
        skip = torch.where(can_skip, skip, negative_infinity)
        choices = torch.stack((stay, advance, skip))
        best, choice = choices.max(dim=0)
        parent = torch.arange(state_count, device=device)
        parent = torch.where(choice == 1, parent - 1, parent)
        parent = torch.where(choice == 2, parent - 2, parent)
        parents[frame] = parent.to(dtype=torch.int32, device="cpu")
        previous = best + log_probabilities[frame, state_labels]

    final_label_state = state_count - 2
    final_blank_state = state_count - 1
    if previous[final_blank_state] >= previous[final_label_state]:
        state = final_blank_state
    else:
        state = final_label_state
    score = float(previous[state].detach().cpu())
    if not math.isfinite(score):
        raise ValueError("CTC transcript cannot fit the available emission frames")

    path = [0] * frame_count
    for frame in range(frame_count - 1, -1, -1):
        path[frame] = state
        if frame:
            state = int(parents[frame, state])
    return path, score


def target_labels(
    occurrences: Sequence[Occurrence],
    vocabulary: dict[str, int],
    delimiter: str | None,
) -> tuple[list[int], list[int | None], str]:
    """Encode every stored occurrence as one CTC transcript, repeats intact.

    ``label_occurrences`` maps each label to the stored occurrence that owns
    it; delimiter labels map to ``None``.  This lets a report distinguish two
    identical canonical words recited at different moments.  MMS has no word
    delimiter token, so ``delimiter`` may be ``None`` and letters concatenate.
    """
    labels: list[int] = []
    label_occurrences: list[int | None] = []
    rendered_words: list[str] = []
    for occurrence_index, occurrence in enumerate(occurrences):
        if not occurrence.normalized:
            raise ValueError(f"position {occurrence.position} normalizes to no model letters")
        if occurrence_index and delimiter is not None:
            labels.append(vocabulary[delimiter])
            label_occurrences.append(None)
        for char in occurrence.normalized:
            if char not in vocabulary:
                raise ValueError(
                    f"position {occurrence.position} contains model-unsupported {char!r}"
                )
            labels.append(vocabulary[char])
            label_occurrences.append(occurrence_index)
        rendered_words.append(occurrence.normalized)
    joiner = delimiter or ""
    return labels, label_occurrences, joiner.join(rendered_words)


def occurrence_frame_evidence(
    log_probabilities: torch.Tensor,
    state_path: Sequence[int],
    labels: Sequence[int],
    label_occurrences: Sequence[int | None],
    occurrence_count: int,
) -> list[dict[str, float | int | None]]:
    """Collect emitting-frame ranges and CTC probabilities for each occurrence."""
    frames: list[list[int]] = [[] for _ in range(occurrence_count)]
    probabilities: list[list[float]] = [[] for _ in range(occurrence_count)]
    # A scalar ``.cpu()`` in this loop synchronizes CUDA once for every emitted
    # character frame.  Moving the small [frames, vocabulary] probability
    # matrix once per ayah keeps a full-corpus audit practical on GPU.
    cpu_probabilities = log_probabilities.exp().detach().cpu()
    for frame, state in enumerate(state_path):
        if state % 2 == 0:
            continue
        label_index = (state - 1) // 2
        occurrence_index = label_occurrences[label_index]
        if occurrence_index is None:
            continue
        label = labels[label_index]
        frames[occurrence_index].append(frame)
        probabilities[occurrence_index].append(float(cpu_probabilities[frame, label]))

    evidence: list[dict[str, float | int | None]] = []
    for occurrence_frames, occurrence_probabilities in zip(frames, probabilities):
        if not occurrence_frames:
            # This should be unreachable for a complete Viterbi path, but a
            # nullable report is safer than inventing a time if an assumption
            # changes in a future CTC implementation.
            evidence.append({
                "firstFrame": None,
                "lastFrameExclusive": None,
                "meanLabelProbability": None,
                "minLabelProbability": None,
            })
            continue
        evidence.append({
            "firstFrame": occurrence_frames[0],
            "lastFrameExclusive": occurrence_frames[-1] + 1,
            "meanLabelProbability": sum(occurrence_probabilities) / len(occurrence_probabilities),
            "minLabelProbability": min(occurrence_probabilities),
        })
    return evidence


def timing_occurrences(
    db: sqlite3.Connection,
    reciter_id: int,
    surah: int,
    ayah: int,
    normalize=normalize_for_model,
) -> list[Occurrence]:
    """Load the exact stored occurrence sequence for a timing row."""
    row = db.execute(
        "SELECT segments FROM timings WHERE reciter_id=? AND surah_id=? AND ayah_number=?",
        (reciter_id, surah, ayah),
    ).fetchone()
    if row is None:
        raise ValueError("timing row is absent")
    words = {
        position: arabic
        for position, arabic in db.execute(
            "SELECT position, arabic FROM words WHERE surah_id=? AND ayah_number=?",
            (surah, ayah),
        )
    }
    occurrences: list[Occurrence] = []
    for segment in json.loads(row[0]):
        if len(segment) != 3:
            raise ValueError(f"malformed timing segment {segment!r}")
        position, start_ms, end_ms = segment
        arabic = words.get(position)
        if arabic is None:
            raise ValueError(f"timing position {position} has no Quran word")
        occurrences.append(Occurrence(
            position=int(position),
            arabic=arabic,
            normalized=normalize(arabic),
            start_ms=int(start_ms),
            end_ms=int(end_ms),
        ))
    if not occurrences:
        raise ValueError("timing row has no stored occurrences")
    return occurrences


def audio_path(qasr_root: Path, slug: str, surah: int, ayah: int) -> Path:
    """Return the exact locally cached EveryAyah MP3 path, or fail closed."""
    path = qasr_root / "audio" / f"{slug}_{surah:03d}{ayah:03d}.mp3"
    if not path.is_file() or not path.stat().st_size:
        raise FileNotFoundError(path)
    return path


def load_model(model_name: str, device_name: str):
    """Load the locally cached processor/model only; this audit must be reproducible offline."""
    try:
        from transformers import AutoModelForCTC, AutoProcessor
    except ModuleNotFoundError as error:
        raise RuntimeError(
            "transformers is required; use /home/sammy/qasr/venv/bin/python"
        ) from error
    auto_device = device_name == "auto"
    if auto_device:
        device_name = "cuda" if torch.cuda.is_available() else "cpu"
    if device_name == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("--device cuda was requested but CUDA is unavailable")
    processor = AutoProcessor.from_pretrained(model_name, local_files_only=True)
    model = AutoModelForCTC.from_pretrained(model_name, local_files_only=True).eval()
    try:
        model = model.to(device_name)
    except torch.OutOfMemoryError:
        if not auto_device or device_name != "cuda":
            raise
        # Other long-running acoustic workers may occupy the one GPU.  Auto is
        # a convenience mode, so fall back rather than losing an otherwise
        # resumable audit; an explicit --device cuda remains fail-fast.
        print("CUDA is full; continuing this audit on CPU", file=sys.stderr, flush=True)
        torch.cuda.empty_cache()
        model = model.to("cpu")
        device_name = "cpu"
    return processor, model, torch.device(device_name)


def force_one(
    *,
    db: sqlite3.Connection,
    processor,
    model,
    device: torch.device,
    qasr_root: Path,
    reciter_id: int,
    slug: str,
    surah: int,
    ayah: int,
    min_label_probability: float,
    max_residual_ms: int,
    romanize: bool = False,
    model_name: str = MODEL_NAME,
    romanizer=None,
) -> dict:
    """Generate one self-contained audio-evidence report object."""
    if romanize:
        if romanizer is None:
            try:
                import uroman as ur
            except ModuleNotFoundError as error:
                raise RuntimeError(
                    "uroman is required for --romanize; use /home/sammy/qasr/venv/bin/python"
                ) from error
            romanizer = ur.Uroman()
        occurrences = timing_occurrences(
            db, reciter_id, surah, ayah,
            normalize=lambda text: normalize_for_mms(text, romanizer),
        )
    else:
        occurrences = timing_occurrences(db, reciter_id, surah, ayah)
    path = audio_path(qasr_root, slug, surah, ayah)
    vocabulary = processor.tokenizer.get_vocab()
    blank_id = blank_id_for(processor.tokenizer)
    delimiter = None if romanize else processor.tokenizer.word_delimiter_token
    if delimiter is not None and delimiter not in vocabulary:
        raise ValueError("CTC tokenizer lacks a usable word delimiter")
    if delimiter is None and not romanize:
        raise ValueError("CTC tokenizer lacks a usable word delimiter")
    labels, label_occurrences, transcript = target_labels(occurrences, vocabulary, delimiter)

    sample_rate = processor.feature_extractor.sampling_rate
    waveform = decode_waveform(path, sample_rate)
    inputs = processor(waveform, sampling_rate=sample_rate, return_tensors="pt")
    with torch.inference_mode():
        logits = model(inputs.input_values.to(device)).logits[0]
        log_probabilities = logits.log_softmax(dim=-1)
    state_path, viterbi_score = forced_ctc_viterbi(log_probabilities, labels, int(blank_id))
    evidence = occurrence_frame_evidence(
        log_probabilities, state_path, labels, label_occurrences, len(occurrences)
    )
    frame_ms = len(waveform) * 1000.0 / sample_rate / len(state_path)

    words: list[dict] = []
    review_count = 0
    for occurrence_index, (occurrence, item) in enumerate(zip(occurrences, evidence), start=1):
        first_frame = item["firstFrame"]
        last_frame = item["lastFrameExclusive"]
        if first_frame is None or last_frame is None:
            forced_start = forced_end = None
            start_residual = end_residual = None
            status = "review"
        else:
            forced_start = round(first_frame * frame_ms)
            forced_end = round(last_frame * frame_ms)
            start_residual = forced_start - occurrence.start_ms
            end_residual = forced_end - occurrence.end_ms
            # A stored segment end is frequently the next-word handoff window,
            # not an independently annotated acoustic release: a reciter may
            # leave silence between words, while playback deliberately keeps
            # the previous wash alive to the next start.  Therefore the start
            # is the automatic sync gate.  We retain (and expose) the end
            # residual as a diagnostic, but do not turn every intended pause
            # into a false "audio mismatch".
            status = (
                "aligned"
                if item["meanLabelProbability"] >= min_label_probability
                and abs(start_residual) <= max_residual_ms
                else "review"
            )
        if status == "review":
            review_count += 1
        words.append({
            "occurrence": occurrence_index,
            "position": occurrence.position,
            "arabic": occurrence.arabic,
            "normalized": occurrence.normalized,
            "storedStartMs": occurrence.start_ms,
            "storedEndMs": occurrence.end_ms,
            "forcedStartMs": forced_start,
            "forcedEndMs": forced_end,
            "startResidualMs": start_residual,
            "endResidualMs": end_residual,
            "startWithinTolerance": (
                None if start_residual is None else abs(start_residual) <= max_residual_ms
            ),
            "endWithinTolerance": (
                None if end_residual is None else abs(end_residual) <= max_residual_ms
            ),
            **item,
            "status": status,
        })
    return {
        "schema": 1,
        "kind": "ctc_fixed_sequence_audio_audit",
        "model": model_name,
        "ctcDevice": str(device),
        "reciterId": reciter_id,
        "reciterSlug": slug,
        "surahId": surah,
        "ayah": ayah,
        "audioPath": str(path),
        "audioSha256": sha256(path),
        "audioDecoder": "ffmpeg",
        "audioDurationMs": round(len(waveform) * 1000.0 / sample_rate),
        "ctcFrames": len(state_path),
        "ctcFrameMs": frame_ms,
        "fixedTranscript": transcript,
        "viterbiLogProbabilityPerFrame": viterbi_score / len(state_path),
        "occurrenceCount": len(words),
        "reviewCount": review_count,
        "words": words,
    }


def selected_rows(
    db: sqlite3.Connection,
    reciter_ids: Sequence[int],
    surah: int | None,
    ayah: int | None,
) -> list[tuple[int, str, int, int]]:
    """Return stable, fully specified timing rows for audit/sharding."""
    clauses = ["t.reciter_id IN (%s)" % ",".join("?" for _ in reciter_ids)]
    parameters: list[int] = list(reciter_ids)
    if surah is not None:
        clauses.append("t.surah_id=?")
        parameters.append(surah)
    if ayah is not None:
        clauses.append("t.ayah_number=?")
        parameters.append(ayah)
    return db.execute(
        "SELECT t.reciter_id, r.slug, t.surah_id, t.ayah_number "
        "FROM timings t JOIN reciters r ON r.id=t.reciter_id WHERE "
        + " AND ".join(clauses)
        + " ORDER BY t.reciter_id, t.surah_id, t.ayah_number",
        parameters,
    ).fetchall()


def completed_keys(path: Path) -> set[tuple[int, int, int]]:
    """Read completed success/error rows so an interrupted audit can continue."""
    if not path.exists():
        return set()
    keys: set[tuple[int, int, int]] = set()
    with path.open(encoding="utf-8") as report:
        for number, line in enumerate(report, start=1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                keys.add((int(row["reciterId"]), int(row["surahId"]), int(row["ayah"])))
            except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
                raise ValueError(f"invalid existing report line {number} in {path}") from error
    return keys


def argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument("--reciter-id", action="append", type=int,
                           help="reciter database id; repeat for selected reciters")
    selection.add_argument("--all-reciters", action="store_true", help="audit all reciters with timings")
    parser.add_argument("--surah", type=int, help="restrict to one surah")
    parser.add_argument("--ayah", type=int, help="restrict to one ayah (requires --surah)")
    parser.add_argument("--shard", type=parse_shard, help="deterministic 1-based PART/TOTAL row shard")
    parser.add_argument("--output", type=Path, required=True, help="JSONL evidence report")
    parser.add_argument("--resume", action="store_true", help="append and skip rows already in --output")
    parser.add_argument("--qasr-root", type=Path, default=DEFAULT_QASR,
                        help=f"qasr cache root (default: {DEFAULT_QASR})")
    parser.add_argument("--db", type=Path, default=DEFAULT_DB, help="timing database to audit")
    parser.add_argument("--model", default=MODEL_NAME, help="locally cached Hugging Face CTC model")
    parser.add_argument(
        "--romanize",
        action="store_true",
        help="uroman+MMS latin transcript (required for the MMS forced-aligner)",
    )
    parser.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    parser.add_argument("--min-label-probability", type=float, default=0.15,
                        help="below this mean CTC label probability, mark word review")
    parser.add_argument("--max-residual-ms", type=int, default=250,
                        help="larger stored-vs-forced start residual marks word review")
    parser.add_argument("--stop-on-error", action="store_true",
                        help="fail immediately instead of writing an error report row")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = argument_parser().parse_args(argv)
    if args.ayah is not None and args.surah is None:
        raise SystemExit("--ayah requires --surah")
    if not 0.0 < args.min_label_probability <= 1.0:
        raise SystemExit("--min-label-probability must be in (0, 1]")
    if args.max_residual_ms < 0:
        raise SystemExit("--max-residual-ms must be non-negative")
    if args.output.exists() and not args.resume:
        raise SystemExit(f"{args.output} already exists; use --resume or choose a new output path")

    db = sqlite3.connect(args.db)
    try:
        if args.all_reciters:
            reciter_ids = [row[0] for row in db.execute(
                "SELECT id FROM reciters WHERE has_timings=1 ORDER BY id"
            )]
        else:
            reciter_ids = args.reciter_id
        rows = selected_rows(db, reciter_ids, args.surah, args.ayah)
        if args.shard:
            part, total = args.shard
            rows = [row for index, row in enumerate(rows) if index % total == part]
        done = completed_keys(args.output) if args.resume else set()
        rows = [row for row in rows if (row[0], row[2], row[3]) not in done]
        if not rows:
            print("no rows left to audit", flush=True)
            return 0
        processor, model, device = load_model(args.model, args.device)
        romanizer = None
        if args.romanize:
            try:
                import uroman as ur
            except ModuleNotFoundError as error:
                raise SystemExit(
                    "uroman is required for --romanize; use /home/sammy/qasr/venv/bin/python"
                ) from error
            romanizer = ur.Uroman()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        mode = "a" if args.resume else "w"
        success = review_words = errors = 0
        with args.output.open(mode, encoding="utf-8") as report:
            for index, (reciter_id, slug, surah, ayah) in enumerate(rows, start=1):
                try:
                    evidence = force_one(
                        db=db, processor=processor, model=model, device=device,
                        qasr_root=args.qasr_root, reciter_id=reciter_id, slug=slug,
                        surah=surah, ayah=ayah,
                        min_label_probability=args.min_label_probability,
                        max_residual_ms=args.max_residual_ms,
                        romanize=args.romanize,
                        model_name=args.model,
                        romanizer=romanizer,
                    )
                    success += 1
                    review_words += evidence["reviewCount"]
                    message = f"ok {reciter_id} {surah}:{ayah} review={evidence['reviewCount']}"
                except Exception as error:  # Evidence must record, not hide, a gap.
                    if args.stop_on_error:
                        raise
                    errors += 1
                    evidence = {
                        "schema": 1,
                        "kind": "ctc_fixed_sequence_audio_audit_error",
                        "reciterId": reciter_id,
                        "reciterSlug": slug,
                        "surahId": surah,
                        "ayah": ayah,
                        "error": f"{type(error).__name__}: {error}",
                    }
                    message = f"ERROR {reciter_id} {surah}:{ayah}: {evidence['error']}"
                report.write(json.dumps(evidence, ensure_ascii=False, separators=(",", ":")) + "\n")
                report.flush()
                print(f"[{index}/{len(rows)}] {message}", flush=True)
        print(
            f"complete: rows={len(rows)} successful={success} review_words={review_words} errors={errors}",
            flush=True,
        )
        return 1 if errors else 0
    finally:
        db.close()


if __name__ == "__main__":
    sys.exit(main())
