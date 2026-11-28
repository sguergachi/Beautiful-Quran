"""Where sound physically begins and ends around an aligned word.

CTC puts a token on the frame where the model is surest of it, which is a
little inside the sound, not at its edge. For cutting audio at verse seams (and
for judging how late a word really starts) the edges themselves are measured
from the short-time energy of the recording.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

SAMPLE_RATE = 16_000


@dataclass(frozen=True)
class EnergyConfig:
    hop_s: float = 0.010
    floor_margin_db: float = 6.0     # sound must exceed the surah's quiet floor by this much...
    level_drop_db: float = 24.0      # ...and be within this of the verse's own loudness
    hold_s: float = 0.040            # quiet must last this long to count as an edge
    max_tail_s: float = 1.5          # an offset is never searched this far past the aligned word
    max_lead_s: float = 0.5          # nor an onset this far before it


class Energy:
    """10 ms RMS of a recording, in dB."""

    def __init__(self, audio: np.ndarray, cfg: EnergyConfig = EnergyConfig()):
        self.cfg = cfg
        hop = int(cfg.hop_s * SAMPLE_RATE)
        usable = len(audio) // hop * hop
        frames = audio[:usable].reshape(-1, hop).astype(np.float64)
        self.db = 10 * np.log10(np.mean(frames * frames, axis=1) + 1e-10)
        self.floor = float(np.percentile(self.db, 10))

    def level(self, start_s: float, end_s: float) -> float:
        a, b = int(start_s / self.cfg.hop_s), max(int(end_s / self.cfg.hop_s), int(start_s / self.cfg.hop_s) + 1)
        return float(np.percentile(self.db[a:b], 90))

    def _quiet(self, level: float) -> float:
        return max(self.floor + self.cfg.floor_margin_db, level - self.cfg.level_drop_db)

    def offset(self, spike_end_s: float, limit_s: float, level: float) -> float:
        """First time at/after the aligned word's last token that sound stays below threshold."""
        hop, hold = self.cfg.hop_s, max(1, int(self.cfg.hold_s / self.cfg.hop_s))
        limit = min(limit_s, spike_end_s + self.cfg.max_tail_s)
        quiet = self._quiet(level)
        i, end = int(spike_end_s / hop), int(limit / hop)
        while i < min(end, len(self.db)):
            if np.all(self.db[i:i + hold] < quiet):
                return i * hop
            i += 1
        return limit

    def onset(self, spike_start_s: float, limit_s: float, level: float) -> float:
        """Last time before the aligned word's first token that sound was still below threshold."""
        hop, hold = self.cfg.hop_s, max(1, int(self.cfg.hold_s / self.cfg.hop_s))
        limit = max(limit_s, spike_start_s - self.cfg.max_lead_s)
        quiet = self._quiet(level)
        i, stop = int(spike_start_s / hop), int(limit / hop)
        while i > max(stop, 0):
            if np.all(self.db[max(0, i - hold):i] < quiet):
                return i * hop
            i -= 1
        return limit
