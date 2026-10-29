"""Use the production pure Kotlin paper tile in offline shader tests/renders.

Requires JDK 21 and kotlinc in addition to the renderer's Python dependencies.
The compiled helper is temporary; no generated material is checked in.
"""
from functools import lru_cache
from pathlib import Path
import subprocess
import tempfile

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
_TEMP = None


@lru_cache(maxsize=1)
def _helper():
    global _TEMP
    _TEMP = tempfile.TemporaryDirectory(prefix='bq-ink-substrate-')
    folder = Path(_TEMP.name)
    runner = folder / 'Main.kt'
    runner.write_text('''
import com.beautifulquran.ui.theme.paperink.PaperInkSubstrate
fun main() {
    val out = java.io.DataOutputStream(java.io.BufferedOutputStream(System.out))
    PaperInkSubstrate.tile().forEach { out.writeInt(it) }
    out.flush()
}
''')
    jar = folder / 'substrate.jar'
    subprocess.run(['kotlinc', str(ROOT / 'app/src/main/java/com/beautifulquran/ui/theme/paperink/PaperInkSubstrate.kt'),
                    str(runner), '-include-runtime', '-d', str(jar)], check=True, capture_output=True)
    return jar


TILE = 720


@lru_cache(maxsize=1)
def paper():
    """The production shared paper tile, RGBA uint8, TILE x TILE reference px."""
    raw = subprocess.run(['java', '-jar', str(_helper())], check=True, capture_output=True).stdout
    packed = np.frombuffer(raw, dtype='>u4').reshape(TILE, TILE)
    return np.stack([(packed >> shift) & 255 for shift in (16, 8, 0, 24)], axis=-1).astype('uint8')
