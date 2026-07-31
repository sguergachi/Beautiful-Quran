#!/usr/bin/env python3
"""Quran-phoneme CTC experiment for true sub-word Timing V2 anchors."""
from __future__ import annotations

import json
import unicodedata
from dataclasses import dataclass
from pathlib import Path

import torch

from aligners import (
    _ctc_token_spans,
    _merge_char_spans_to_words,
    letters_only,
    load_mono_16k,
)

MODEL = "obadx/muaalem-model-v3_2"
MODEL_REVISION = "01a1ef9fbe40d144ef845101e89ff924aed3fef5"
PHONETIZER_REVISION = "fb64a1a8b0d7f5c38ffe26de0c69cc4a2b840950"


@dataclass(frozen=True)
class PhonemeTarget:
    token_ids: tuple[int, ...]
    word_token_counts: tuple[int, ...]
    word_progress: tuple[tuple[float, ...], ...]
    phonemes: str


def spatial_progress(groups: list[list[int]]) -> dict[int, float]:
    """Map acoustic units into their own rendered base-letter slots.

    Written-but-silent slots fold into the next measured unit (or the previous
    unit at word end), so they never create a synthetic timestamp.
    """
    voiced = [(i, sorted(set(indices))) for i, indices in enumerate(groups) if indices]
    if not voiced:
        return {}
    progress: dict[int, float] = {}
    previous_base = -1
    for order, (base, indices) in enumerate(voiced):
        start = 0.0 if order == 0 else (previous_base + 1) / len(groups)
        end = 1.0 if order == len(voiced) - 1 else (base + 1) / len(groups)
        for offset, phoneme_index in enumerate(indices, 1):
            progress[phoneme_index] = start + (end - start) * offset / len(indices)
        previous_base = base
    return progress


def _word_spans(text: str) -> list[tuple[int, int]]:
    spans = []
    start = 0
    for i, ch in enumerate(text + " "):
        if ch == " ":
            if i > start:
                spans.append((start, i))
            start = i + 1
    return spans


def _is_rendered_base(ch: str) -> bool:
    return ch != "ـ" and unicodedata.category(ch).startswith("L")


def build_target(
    surah: int,
    ayah: int,
    rendered_words: list[str],
    vocab: dict[str, int],
) -> PhonemeTarget | None:
    """Build a strict Hafs phoneme target and map it back to word progress."""
    try:
        from quran_transcript import Aya, MoshafAttributes, quran_phonetizer
    except ImportError as error:
        raise RuntimeError(
            "install quran-transcript at the pinned PHONETIZER_REVISION"
        ) from error

    text = Aya(surah, ayah).get().uthmani
    spans = _word_spans(text)
    canonical_words = [text[start:end] for start, end in spans]
    if (
        len(canonical_words) != len(rendered_words)
        or any(
            letters_only(canonical) != letters_only(rendered)
            for canonical, rendered in zip(canonical_words, rendered_words)
        )
    ):
        return None

    moshaf = MoshafAttributes(
        rewaya="hafs",
        madd_monfasel_len=4,
        madd_mottasel_len=4,
        madd_mottasel_waqf=4,
        madd_aared_len=4,
    )
    result = quran_phonetizer(text, moshaf, remove_spaces=True)
    if any(ch not in vocab for ch in result.phonemes):
        return None

    word_counts = []
    word_progress = []
    expected_start = 0
    for start, end in spans:
        bases = [i for i in range(start, end) if _is_rendered_base(text[i])]
        if not bases:
            return None
        groups = []
        for i, base in enumerate(bases):
            group_end = bases[i + 1] if i + 1 < len(bases) else end
            indices = []
            for source_index in range(base, group_end):
                mapping = result.mappings[source_index]
                if not mapping.deleted:
                    indices.extend(range(*mapping.pos))
            groups.append(indices)
        progress = spatial_progress(groups)
        if not progress:
            return None
        first = min(progress)
        last = max(progress) + 1
        if first != expected_start or sorted(progress) != list(range(first, last)):
            return None
        word_counts.append(last - first)
        word_progress.append(tuple(progress[i] for i in range(first, last)))
        expected_start = last

    if expected_start != len(result.phonemes):
        return None
    return PhonemeTarget(
        token_ids=tuple(vocab[ch] for ch in result.phonemes),
        word_token_counts=tuple(word_counts),
        word_progress=tuple(word_progress),
        phonemes=result.phonemes,
    )


def phoneme_keyframes(
    spans: list[tuple[int, int]],
    counts: tuple[int, ...],
    progress: tuple[tuple[float, ...], ...],
    ms_per_frame: float,
) -> list[list[list[float]]]:
    """Emit acoustic phoneme edges and explicit CTC-blank plateaus."""
    out = []
    index = 0
    for count, word_progress in zip(counts, progress):
        chunk = spans[index : index + count]
        index += count
        points = []
        previous_end = None
        previous_progress = 0.0
        for (start_frame, end_frame), end_progress in zip(chunk, word_progress):
            start_ms = round(start_frame * ms_per_frame)
            end_ms = round((end_frame + 1) * ms_per_frame)
            if previous_end is None or start_ms > previous_end:
                points.append([start_ms, previous_progress])
            points.append([end_ms, end_progress])
            previous_end = end_ms
            previous_progress = end_progress
        out.append(points)
    return out


def token_error_rate(reference: list[int], prediction: list[int]) -> float:
    """Levenshtein error rate for free-decode calibration."""
    if not reference:
        return 0.0 if not prediction else 1.0
    row = list(range(len(prediction) + 1))
    for i, expected in enumerate(reference, 1):
        next_row = [i]
        for j, actual in enumerate(prediction, 1):
            next_row.append(
                min(
                    next_row[-1] + 1,
                    row[j] + 1,
                    row[j - 1] + (expected != actual),
                )
            )
        row = next_row
    return row[-1] / len(reference)


def windowed_token_spans(
    log_probs: torch.Tensor,
    target: PhonemeTarget,
    windows: list[list[int]],
    ms_per_frame: float,
    margin_ms: int = 80,
) -> tuple[list[tuple[int, int]], list[float], list[float]]:
    """Align each word independently inside an acoustic word window."""
    if len(windows) != len(target.word_token_counts):
        return [], [], []
    all_spans = []
    scores = []
    error_rates = []
    target_index = 0
    for window, count in zip(windows, target.word_token_counts):
        word_ids = list(target.token_ids[target_index : target_index + count])
        target_index += count
        first_frame = max(0, int((window[1] - margin_ms) / ms_per_frame))
        last_frame = min(
            len(log_probs),
            int((window[2] + margin_ms) / ms_per_frame) + 1,
        )
        local = log_probs[first_frame:last_frame]
        spans, score = _ctc_token_spans(local, word_ids, blank=0)
        if len(spans) != count:
            return [], [], []
        all_spans.extend(
            (start + first_frame, end + first_frame) for start, end in spans
        )
        scores.append(score)

        core_first = max(0, int(window[1] / ms_per_frame))
        core_last = min(len(log_probs), int(window[2] / ms_per_frame) + 1)
        decoded = []
        previous = None
        for token in log_probs[core_first:core_last].argmax(dim=-1).tolist():
            if token != 0 and token != previous:
                decoded.append(token)
            previous = token
        error_rates.append(token_error_rate(word_ids, decoded))
    return all_spans, scores, error_rates


class QuranPhonemeAligner:
    """Pinned build-time phoneme model; never shipped in the Android app."""

    def __init__(self, device: str = "cuda"):
        try:
            from huggingface_hub import hf_hub_download
            from quran_muaalem.modeling.modeling_multi_level_ctc import (
                Wav2Vec2BertForMultilevelCTC,
            )
            from transformers import AutoFeatureExtractor
        except ImportError as error:
            raise RuntimeError(
                "install quran-muaalem==0.1.0 and huggingface-hub"
            ) from error

        self.device = device
        self.dtype = torch.float16 if device.startswith("cuda") else torch.float32
        vocab_path = hf_hub_download(MODEL, "vocab.json", revision=MODEL_REVISION)
        with open(vocab_path, encoding="utf-8") as source:
            self.vocab = json.load(source)["phonemes"]
        self.processor = AutoFeatureExtractor.from_pretrained(
            MODEL, revision=MODEL_REVISION
        )
        self.model = Wav2Vec2BertForMultilevelCTC.from_pretrained(
            MODEL,
            revision=MODEL_REVISION,
            dtype=self.dtype,
        ).to(device).eval()

    @torch.inference_mode()
    def align(
        self,
        audio_path: str | Path,
        rendered_words: list[str],
        surah: int,
        ayah: int,
    ) -> tuple[list[list[int]], float, list[list[list[float]]], float]:
        target = build_target(surah, ayah, rendered_words, self.vocab)
        if target is None:
            return [], float("-inf"), [], 1.0
        wave, rate = load_mono_16k(audio_path)
        inputs = self.processor(wave, sampling_rate=rate, return_tensors="pt")
        output = self.model(
            input_features=inputs.input_features.to(self.device, dtype=self.dtype),
            attention_mask=inputs.attention_mask.to(self.device),
        ).logits["phonemes"][0]
        log_probs = torch.log_softmax(output.float(), dim=-1)
        spans, score = _ctc_token_spans(log_probs, list(target.token_ids), blank=0)
        if len(spans) != len(target.token_ids):
            return [], float("-inf"), [], 1.0

        greedy = output.argmax(dim=-1).tolist()
        decoded = []
        previous = None
        for token in greedy:
            if token != 0 and token != previous:
                decoded.append(token)
            previous = token
        error_rate = token_error_rate(list(target.token_ids), decoded)
        ms_per_frame = (1000.0 * len(wave) / rate) / max(len(output), 1)
        segments = _merge_char_spans_to_words(
            spans, list(target.word_token_counts), ms_per_frame
        )
        keyframes = phoneme_keyframes(
            spans,
            target.word_token_counts,
            target.word_progress,
            ms_per_frame,
        )
        return segments, score, keyframes, error_rate

    @torch.inference_mode()
    def align_in_word_windows(
        self,
        audio_path: str | Path,
        rendered_words: list[str],
        surah: int,
        ayah: int,
        windows: list[list[int]],
    ) -> tuple[list[list[list[float]]], list[float], list[float]]:
        """Reclock phonemes locally while keeping the character word clock."""
        target = build_target(surah, ayah, rendered_words, self.vocab)
        if target is None:
            return [], [], []
        wave, rate = load_mono_16k(audio_path)
        inputs = self.processor(wave, sampling_rate=rate, return_tensors="pt")
        output = self.model(
            input_features=inputs.input_features.to(self.device, dtype=self.dtype),
            attention_mask=inputs.attention_mask.to(self.device),
        ).logits["phonemes"][0]
        log_probs = torch.log_softmax(output.float(), dim=-1)
        ms_per_frame = (1000.0 * len(wave) / rate) / max(len(output), 1)
        spans, scores, error_rates = windowed_token_spans(
            log_probs, target, windows, ms_per_frame
        )
        if len(spans) != len(target.token_ids):
            return [], [], []
        return (
            phoneme_keyframes(
                spans,
                target.word_token_counts,
                target.word_progress,
                ms_per_frame,
            ),
            scores,
            error_rates,
        )
