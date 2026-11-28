"""Acoustic models: audio -> per-frame CTC log-probabilities, and word -> token ids.

Every backend returns emissions with one extra final column (the "star" token,
log-prob 0) that stands for audio the text does not explain. Heavy imports
(torch, transformers) happen only when a backend is constructed.
"""

from __future__ import annotations

import subprocess
from functools import lru_cache

import numpy as np

from .text import letters

SAMPLE_RATE = 16_000


def decode_audio(path) -> np.ndarray:
    """Any audio file -> mono float32 at 16 kHz (needs ffmpeg)."""
    raw = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-i", str(path), "-map", "0:a:0",
         "-f", "f32le", "-ac", "1", "-ar", str(SAMPLE_RATE), "-"],
        capture_output=True, check=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


class Backend:
    name: str
    stride_s: float
    blank: int
    star: int
    word_delimiter: int | None = None

    def emissions(self, audio: np.ndarray) -> np.ndarray:
        raise NotImplementedError

    def tokens(self, word: str) -> list[int]:
        raise NotImplementedError


def _device(torch):
    return "cuda" if torch.cuda.is_available() else "cpu"


class MmsAligner(Backend):
    """MMS-300m forced-aligner (1130 languages), Arabic romanised through uroman."""

    name = "MahmoudAshraf/mms-300m-1130-forced-aligner"

    def __init__(self, device: str | None = None):
        import torch
        from ctc_forced_aligner import load_alignment_model

        self._torch = torch
        self.device = device or _device(torch)
        dtype = torch.float16 if self.device == "cuda" else torch.float32
        self.model, self.tokenizer = load_alignment_model(self.device, dtype=dtype)
        vocab = {k.lower(): v for k, v in self.tokenizer.get_vocab().items()}
        self.vocab = vocab
        self.blank = vocab["<blank>"]
        self.star = len(vocab)
        self.stride_s = self.model.config.inputs_to_logits_ratio / SAMPLE_RATE
        self._tokens = lru_cache(maxsize=None)(self._tokens_uncached)

    def emissions(self, audio: np.ndarray) -> np.ndarray:
        from ctc_forced_aligner import generate_emissions

        wave = self._torch.from_numpy(audio).to(self.model.dtype).to(self.device)
        emissions, _ = generate_emissions(self.model, wave, batch_size=4)
        return emissions.float().cpu().numpy()

    def _tokens_uncached(self, text: str) -> tuple[int, ...]:
        from ctc_forced_aligner.text_utils import get_uroman_tokens

        romanised = get_uroman_tokens([text], "ara")[0].split()
        return tuple(self.vocab[t] for t in romanised if t in self.vocab)

    def tokens(self, word: str) -> list[int]:
        return list(self._tokens(letters(word)))


class _ChunkedHF(Backend):
    """A Hugging Face CTC model over character vocabularies, run in overlapping 20 s chunks."""

    name: str
    target_lang: str | None = None

    def __init__(self, device: str | None = None, window_s: float = 20.0, context_s: float = 2.0):
        import torch
        from transformers import AutoModelForCTC, AutoProcessor

        self._torch = torch
        self.device = device or _device(torch)
        kwargs = {"target_lang": self.target_lang} if self.target_lang else {}
        self.processor = AutoProcessor.from_pretrained(self.name, **kwargs)
        if self.target_lang:
            self.processor.tokenizer.set_target_lang(self.target_lang)
        self.model = AutoModelForCTC.from_pretrained(self.name, **kwargs).to(self.device).eval()
        if self.device == "cuda":
            self.model = self.model.half()
        vocab = self.processor.tokenizer.get_vocab()
        self.vocab = vocab
        self.blank = vocab.get("<pad>", 0)
        self.word_delimiter = vocab.get("|")
        self.star = len(vocab)
        self.stride_s = self.model.config.inputs_to_logits_ratio / SAMPLE_RATE
        self.window_s, self.context_s = window_s, context_s

    def emissions(self, audio: np.ndarray) -> np.ndarray:
        torch = self._torch
        ratio = self.model.config.inputs_to_logits_ratio
        window = int(self.window_s * SAMPLE_RATE) // ratio * ratio
        context = int(self.context_s * SAMPLE_RATE) // ratio * ratio
        out = []
        with torch.inference_mode():
            for start in range(0, len(audio), window):
                lo, hi = max(0, start - context), min(len(audio), start + window + context)
                chunk = audio[lo:hi]
                chunk = (chunk - chunk.mean()) / np.sqrt(chunk.var() + 1e-7)  # what the processor does
                x = torch.from_numpy(chunk.astype(np.float32))[None].to(self.device).to(self.model.dtype)
                logits = self.model(x).logits[0].float()
                first = (start - lo) // ratio
                keep = min(window // ratio, logits.shape[0] - first)
                out.append(torch.log_softmax(logits[first:first + keep], dim=-1).cpu().numpy())
        emissions = np.concatenate(out)
        want = int(np.ceil(len(audio) / ratio))
        emissions = emissions[:want]
        return np.concatenate([emissions, np.zeros((len(emissions), 1), np.float32)], axis=1)

    def tokens(self, word: str) -> list[int]:
        return [self.vocab[c] for c in letters(word) if c in self.vocab]


class XlsrArabic(_ChunkedHF):
    """wav2vec2 XLSR-53 fine-tuned on Arabic characters: independent of MMS in data and vocabulary."""

    name = "jonatasgrosman/wav2vec2-large-xlsr-53-arabic"


class Mms1bArabic(_ChunkedHF):
    """MMS-1b-all with its Arabic adapter: a third opinion, trained on other data than either of the above."""

    name = "facebook/mms-1b-all"
    target_lang = "ara"


BACKENDS = {"mms": MmsAligner, "xlsr": XlsrArabic, "mms1b": Mms1bArabic}
