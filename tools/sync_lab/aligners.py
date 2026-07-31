"""Forced-alignment methods for the sync lab.

All methods take known text (word list) + audio and return
[[position_1based, start_ms, end_ms], ...].
Never trust model transcription for wording — only timestamps.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Callable

import numpy as np
import torch


# ── audio I/O ──────────────────────────────────────────────────────────────

def load_mono_16k(path: str | Path) -> tuple[np.ndarray, int]:
    """Load any audio as mono float32 @ 16 kHz (soundfile / librosa / ffmpeg)."""
    path = str(path)
    y = None
    sr = None
    try:
        import soundfile as sf
        y, sr = sf.read(path, always_2d=False)
        y = np.asarray(y, dtype=np.float32)
        if y.ndim > 1:
            y = y.mean(axis=1)
    except Exception:
        y = None
    if y is None:
        try:
            import librosa
            y, sr = librosa.load(path, sr=16000, mono=True)
            return y.astype(np.float32), 16000
        except Exception:
            pass
    if y is None:
        # ffmpeg decode to wav bytes
        import subprocess, tempfile, os
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp_path = tmp.name
        try:
            subprocess.check_call(
                ["ffmpeg", "-y", "-i", path, "-ac", "1", "-ar", "16000", tmp_path],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            import soundfile as sf
            y, sr = sf.read(tmp_path, always_2d=False)
            y = np.asarray(y, dtype=np.float32)
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass
    if y is None:
        raise RuntimeError(f"cannot load audio: {path}")
    if sr != 16000:
        # linear resample
        n_out = int(round(len(y) * 16000 / sr))
        x_old = np.linspace(0.0, 1.0, num=len(y), endpoint=False)
        x_new = np.linspace(0.0, 1.0, num=n_out, endpoint=False)
        y = np.interp(x_new, x_old, y).astype(np.float32)
        sr = 16000
    return y, sr


def audio_duration_ms(path: str | Path) -> float:
    y, sr = load_mono_16k(path)
    return 1000.0 * len(y) / sr


# ── text normalization for CTC vocab matching ─────────────────────────────

_DIAC = re.compile(r"[\u064B-\u065F\u0670\u06D6-\u06ED]")
# Quranic annotation marks often outside model vocab
_STRIP = re.compile(r"[\u06E5\u06E6\u06E7\u06E8\u06E9\u06EA\u06EB\u06EC\u06ED\u06D6-\u06DC\u06DF-\u06E4\u06E5\u06E6\u06E7\u06E8\u06EA-\u06ED]")


def strip_tashkeel(s: str) -> str:
    s = _DIAC.sub("", s)
    s = _STRIP.sub("", s)
    s = s.replace("ٱ", "ا").replace("ٰ", "").replace("ـ", "")
    s = s.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
    s = s.replace("ى", "ي").replace("ة", "ه")
    s = re.sub(r"\s+", " ", s).strip()
    return s


def letters_only(s: str) -> str:
    s = strip_tashkeel(s)
    return "".join(ch for ch in s if unicodedata.category(ch).startswith("L") or ch == " ")


# ── CTC core via torchaudio.functional.forced_align ───────────────────────

def _ctc_token_spans(
    log_probs: torch.Tensor,  # [T, C]
    targets: list[int],
    blank: int,
) -> tuple[list[tuple[int, int]], float]:
    """Return (start_frame, end_frame) per target token + mean path score."""
    if not targets or log_probs.numel() == 0:
        return [], float("-inf")
    # torchaudio wants CPU or CUDA float; batch=1
    lp = log_probs.unsqueeze(0).contiguous()  # [1,T,C]
    # forced_align may require CPU on some builds — try device first
    tgt = torch.tensor([targets], dtype=torch.int32, device=lp.device)
    try:
        from torchaudio.functional import forced_align
        labels, scores = forced_align(lp, tgt, blank=blank)
    except Exception:
        # fallback CPU
        from torchaudio.functional import forced_align
        try:
            labels, scores = forced_align(lp.cpu(), tgt.cpu(), blank=blank)
        except Exception:
            return [], float("-inf")
        labels = labels.to(log_probs.device)
        scores = scores.to(log_probs.device)
    labels = labels[0]
    scores = scores[0]
    path_score = float(scores.mean().item()) if scores.numel() else float("-inf")

    from torchaudio.functional import merge_tokens

    merged = merge_tokens(labels, scores, blank=blank)
    if [span.token for span in merged] != targets:
        return [], path_score
    return [(span.start, span.end - 1) for span in merged], path_score


def _merge_char_spans_to_words(
    char_spans: list[tuple[int, int]],
    word_char_counts: list[int],
    ms_per_frame: float,
) -> list[list[int]]:
    segs = []
    idx = 0
    for wi, n in enumerate(word_char_counts):
        if n <= 0:
            segs.append([wi + 1, 0, 0])
            continue
        chunk = char_spans[idx : idx + n]
        idx += n
        if not chunk:
            segs.append([wi + 1, 0, 0])
            continue
        start_f = chunk[0][0]
        end_f = chunk[-1][1]
        start_ms = int(round(start_f * ms_per_frame))
        end_ms = int(round((end_f + 1) * ms_per_frame))
        segs.append([wi + 1, start_ms, max(end_ms, start_ms + 1)])
    # karaoke: ends become next starts (hold model) after minor cleanup
    for i in range(1, len(segs)):
        if segs[i][1] < segs[i - 1][1]:
            segs[i][1] = segs[i - 1][1]
    for i in range(len(segs) - 1):
        # end at next start for continuous hold
        segs[i][2] = max(segs[i][1] + 1, segs[i + 1][1])
    return segs


def _char_keyframes(
    char_spans: list[tuple[int, int]],
    word_char_counts: list[int],
    ms_per_frame: float,
) -> list[list[list[float]]]:
    """Absolute CTC character edges, including blank-span reveal plateaus."""
    out = []
    idx = 0
    for n in word_char_counts:
        chunk = char_spans[idx : idx + n]
        idx += n
        points = []
        previous_end = None
        for i, (start_f, end_f) in enumerate(chunk):
            start_ms = round(start_f * ms_per_frame)
            end_ms = round((end_f + 1) * ms_per_frame)
            if previous_end is None or start_ms > previous_end:
                points.append([start_ms, i / max(n, 1)])
            points.append([end_ms, (i + 1) / max(n, 1)])
            previous_end = end_ms
        out.append(points)
    return out


# ── model wrappers ─────────────────────────────────────────────────────────

@dataclass
class CtcModel:
    name: str
    model: object
    processor: object
    device: str
    blank_id: int
    vocab: dict[str, int]
    ms_per_frame: float


_MODEL_CACHE: dict[str, CtcModel] = {}


def get_ctc_model(model_id: str, device: str | None = None) -> CtcModel:
    if model_id in _MODEL_CACHE:
        return _MODEL_CACHE[model_id]
    from transformers import Wav2Vec2ForCTC, Wav2Vec2Processor

    repository, separator, revision = model_id.partition("@")
    revision_arg = revision if separator else None
    device = device or ("cuda" if torch.cuda.is_available() else "cpu")
    processor = Wav2Vec2Processor.from_pretrained(repository, revision=revision_arg)
    model = Wav2Vec2ForCTC.from_pretrained(repository, revision=revision_arg)
    model.eval().to(device)
    vocab = processor.tokenizer.get_vocab()
    blank = model.config.pad_token_id if model.config.pad_token_id is not None else 0
    # wav2vec2 typically 20ms frames after 320-sample stride at 16k
    ms_per_frame = 20.0
    cm = CtcModel(model_id, model, processor, device, blank, vocab, ms_per_frame)
    _MODEL_CACHE[model_id] = cm
    return cm


def _map_chars_to_ids(
    text: str,
    vocab: dict[str, int],
    *,
    strict: bool = False,
) -> list[int] | None:
    ids = []
    for ch in text:
        if ch == " ":
            # skip spaces in token stream — word boundaries handled externally
            continue
        if ch in vocab:
            ids.append(vocab[ch])
        elif ch.lower() in vocab:
            ids.append(vocab[ch.lower()])
        elif strict:
            return None
    return ids


@torch.inference_mode()
def ctc_force_align_words(
    audio_path: str | Path,
    words: list[str],
    model_id: str = "jonatasgrosman/wav2vec2-large-xlsr-53-arabic",
    keep_diacritics: bool = False,
    return_score: bool = False,
    waveform: np.ndarray | None = None,
    sr: int | None = None,
    return_keyframes: bool = False,
    strict_target: bool = False,
) -> list[list[int]] | tuple[list[list[int]], float]:
    """Align known words via CTC; return [[pos, start_ms, end_ms], ...].

    If return_score=True, also return mean CTC path log-prob (higher=better).
    If return_keyframes=True, returns ``segments, score, keyframes`` where each
    keyframe is ``[absolute_end_ms, word_progress]`` for one acoustic CTC unit.
    Optional waveform/sr avoids reloading audio (used for pad-shift tests).
    """
    cm = get_ctc_model(model_id)
    if waveform is None:
        y, sr = load_mono_16k(audio_path)
    else:
        y = waveform
        sr = sr or 16000
    if len(y) < 400:
        dur = max(1, int(1000 * len(y) / sr))
        n = max(1, len(words))
        step = dur // n
        segs = [[i + 1, i * step, (i + 1) * step if i + 1 < n else dur] for i in range(n)]
        if return_keyframes:
            return segs, float("-inf"), [[[seg[2], 1.0]] for seg in segs]
        return (segs, float("-inf")) if return_score else segs

    inputs = cm.processor(y, sampling_rate=sr, return_tensors="pt", padding=True)
    input_values = inputs.input_values.to(cm.device)
    logits = cm.model(input_values).logits[0]  # [T, C]
    log_probs = torch.log_softmax(logits.float(), dim=-1)

    norm_words = []
    for w in words:
        nw = letters_only(w) if not keep_diacritics else strip_tashkeel(w)
        nw = nw.replace(" ", "")
        if not nw and not strict_target:
            nw = "ا"
        norm_words.append(nw)

    mapped_words: list[list[int] | None] = []
    for nw in norm_words:
        ids = _map_chars_to_ids(nw, cm.vocab, strict=strict_target)
        mapped_words.append(ids if ids else None)
    if strict_target and any(ids is None for ids in mapped_words):
        if return_keyframes:
            return [], float("-inf"), []
        return ([], float("-inf")) if return_score else []
    # if a word maps to nothing, use alef id if present
    fallback = _map_chars_to_ids("ا", cm.vocab) or [1]
    mapped_words = [m if m else fallback for m in mapped_words]
    flat_ids = [i for w in mapped_words for i in w]
    if not flat_ids:
        dur = int(1000 * len(y) / sr)
        n = len(words)
        step = max(1, dur // max(n, 1))
        segs = [[i + 1, i * step, (i + 1) * step if i + 1 < n else dur] for i in range(n)]
        if return_keyframes:
            return segs, float("-inf"), [[[seg[2], 1.0]] for seg in segs]
        return (segs, float("-inf")) if return_score else segs

    # Estimate real frame shift from T vs audio length
    T = log_probs.size(0)
    ms_per_frame = (1000.0 * len(y) / sr) / max(T, 1)

    spans, path_score = _ctc_token_spans(log_probs, flat_ids, cm.blank_id)
    if len(spans) != len(flat_ids):
        if return_keyframes:
            return [], float("-inf"), []
        return ([], float("-inf")) if return_score else []
    counts = [len(w) for w in mapped_words]
    segs = _merge_char_spans_to_words(spans, counts, ms_per_frame)
    keyframes = _char_keyframes(spans, counts, ms_per_frame)

    dur = int(1000 * len(y) / sr)
    for s in segs:
        s[1] = max(0, min(s[1], dur))
        s[2] = max(s[1] + 1, min(s[2], dur))
    if segs:
        segs[-1][2] = max(segs[-1][2], segs[-1][1] + 1)
    if return_keyframes:
        return segs, path_score, keyframes
    return (segs, path_score) if return_score else segs


def pad_silence(y: np.ndarray, sr: int, pad_ms: float) -> np.ndarray:
    n = int(sr * pad_ms / 1000.0)
    if n <= 0:
        return y
    return np.concatenate([np.zeros(n, dtype=np.float32), y])


def boundary_energy_rise(
    y: np.ndarray,
    sr: int,
    starts_ms: list[float],
    win_ms: float = 30.0,
) -> list[float]:
    """For each start, energy after − energy before (positive = speech onset).

    Non-circular quality: good boundaries should show an energy rise.
    """
    rises = []
    win = int(sr * win_ms / 1000)
    for s in starts_ms:
        i = int(sr * s / 1000)
        a0 = max(0, i - win)
        a1 = i
        b0 = i
        b1 = min(len(y), i + win)
        pre = float(np.mean(y[a0:a1] ** 2)) + 1e-12 if a1 > a0 else 1e-12
        post = float(np.mean(y[b0:b1] ** 2)) + 1e-12 if b1 > b0 else 1e-12
        rises.append(10.0 * np.log10(post / pre))  # dB
    return rises


# ── energy-based methods ───────────────────────────────────────────────────

def energy_uniform_align(
    audio_path: str | Path,
    words: list[str],
) -> list[list[int]]:
    """Split voiced regions by cumulative energy proportional to word length."""
    from metrics import energy_onsets

    y, sr = load_mono_16k(audio_path)
    dur_ms = int(1000 * len(y) / sr)
    if not words:
        return []
    # find speech span via energy
    frame = int(sr * 0.02)
    hop = int(sr * 0.01)
    n = 1 + max(0, (len(y) - frame) // hop)
    rms = np.array([
        np.sqrt(np.mean(y[i * hop : i * hop + frame] ** 2) + 1e-12)
        for i in range(n)
    ])
    thr = float(np.max(rms)) * 0.08
    speech = np.where(rms > thr)[0]
    if speech.size == 0:
        start_ms, end_ms = 0, dur_ms
    else:
        start_ms = int(speech[0] * hop * 1000 / sr)
        end_ms = int(min(dur_ms, (speech[-1] * hop + frame) * 1000 / sr))

    # weights by letter count
    weights = np.array([max(1, len(letters_only(w))) for w in words], dtype=np.float64)
    weights /= weights.sum()
    span = max(1, end_ms - start_ms)
    segs = []
    t = float(start_ms)
    for i, w in enumerate(weights):
        nxt = t + w * span
        segs.append([i + 1, int(t), int(nxt) if i + 1 < len(words) else end_ms])
        t = nxt
    segs[-1][2] = end_ms
    return segs


def refine_starts_to_onsets(
    segs: list[list[int]],
    audio_path: str | Path,
    window_ms: float = 80.0,
    y: np.ndarray | None = None,
    sr: int | None = None,
) -> list[list[int]]:
    """Hypothesis: snap each word start to nearest energy onset within window."""
    from metrics import energy_onsets

    if y is None:
        y, sr = load_mono_16k(audio_path)
    onsets = energy_onsets(y, sr)
    if onsets.size == 0:
        return [list(s) for s in segs]
    out = [list(s) for s in segs]
    for i, s in enumerate(out):
        start = float(s[1])
        cand = onsets[(onsets >= start - window_ms) & (onsets <= start + window_ms)]
        if cand.size:
            best = float(cand[np.argmin(np.abs(cand - start))])
            lo = out[i - 1][1] + 1 if i > 0 else 0
            hi = (out[i + 1][1] - 1) if i + 1 < len(out) else out[i][2] - 10
            best = max(lo, min(best, hi))
            out[i][1] = int(round(best))
    for i in range(len(out) - 1):
        out[i][2] = max(out[i][1] + 1, out[i + 1][1])
    return out


def snap_lead_in(
    segs: list[list[int]],
    audio_path: str | Path,
    max_lead_ms: float = 400.0,
    thr_ratio: float = 0.08,
    y: np.ndarray | None = None,
    sr: int | None = None,
) -> list[list[int]]:
    """Hypothesis: first word should start at first speech energy, not t=0 silence."""
    if not segs:
        return segs
    if y is None:
        y, sr = load_mono_16k(audio_path)
    frame = int(sr * 0.02)
    hop = int(sr * 0.01)
    n = 1 + max(0, (len(y) - frame) // hop)
    rms = np.array([
        np.sqrt(np.mean(y[i * hop : i * hop + frame] ** 2) + 1e-12)
        for i in range(n)
    ])
    thr = float(np.max(rms)) * thr_ratio
    speech = np.where(rms > thr)[0]
    if speech.size == 0:
        return [list(s) for s in segs]
    first_ms = int(speech[0] * hop * 1000 / sr)
    out = [list(s) for s in segs]
    # only pull first start forward/back within max_lead
    if abs(out[0][1] - first_ms) <= max_lead_ms:
        out[0][1] = max(0, first_ms)
        if len(out) > 1:
            out[0][2] = max(out[0][1] + 1, out[1][1])
        else:
            out[0][2] = max(out[0][2], out[0][1] + 1)
    return out


def trim_trailing_silence(
    segs: list[list[int]],
    audio_path: str | Path,
    thr_ratio: float = 0.06,
) -> list[list[int]]:
    """Hypothesis: last-word end should not include trailing silence."""
    y, sr = load_mono_16k(audio_path)
    if not segs:
        return segs
    out = [list(s) for s in segs]
    frame = int(sr * 0.02)
    hop = int(sr * 0.01)
    n = 1 + max(0, (len(y) - frame) // hop)
    rms = np.array([
        np.sqrt(np.mean(y[i * hop : i * hop + frame] ** 2) + 1e-12)
        for i in range(n)
    ])
    thr = float(np.max(rms)) * thr_ratio
    # last frame above thr
    speech = np.where(rms > thr)[0]
    if speech.size:
        end_ms = int((speech[-1] * hop + frame) * 1000 / sr)
        out[-1][2] = max(out[-1][1] + 1, min(out[-1][2], end_ms))
    return out


def equal_split(audio_path: str | Path, words: list[str]) -> list[list[int]]:
    dur = int(audio_duration_ms(audio_path))
    n = max(1, len(words))
    step = max(1, dur // n)
    return [[i + 1, i * step, (i + 1) * step if i + 1 < n else dur] for i in range(len(words))]


# ── ensemble ───────────────────────────────────────────────────────────────

def ensemble_median(methods: list[list[list[int]]]) -> list[list[int]]:
    """Median start/end per position across methods (first-pass only)."""
    if not methods:
        return []
    n = min(len(m) for m in methods)
    out = []
    for i in range(n):
        starts = [m[i][1] for m in methods]
        ends = [m[i][2] for m in methods]
        pos = methods[0][i][0]
        out.append([pos, int(np.median(starts)), int(np.median(ends))])
    for i in range(len(out) - 1):
        if out[i][2] > out[i + 1][1]:
            out[i][2] = out[i + 1][1]
        if out[i][2] <= out[i][1]:
            out[i][2] = out[i][1] + 1
    return out
