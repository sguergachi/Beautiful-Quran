"""The shipped paper-ink paper tile, and the tool that regenerates it.

`app/src/main/assets/paper-ink/paper.a8` is the production `PaperInkSubstrate`
tile, one wicking byte per reference px. The app reads it instead of building
the tile at runtime; PaperInkSubstrateTest fails if it drifts from the Kotlin
generator. After changing the generator, regenerate it (JDK 21 + kotlinc):

  python3 tools/paper_ink_substrate.py --write
"""
from pathlib import Path
import subprocess
import sys
import tempfile

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / 'app/src/main/assets/paper-ink/paper.a8'
TILE = 720


def paper():
    """The shipped tile as a TILE x TILE uint8 array of wicking preference."""
    return np.frombuffer(ASSET.read_bytes(), dtype='uint8').reshape(TILE, TILE)


def generate():
    """Run the production Kotlin generator; returns the tile's bytes."""
    with tempfile.TemporaryDirectory(prefix='bq-ink-substrate-') as name:
        folder = Path(name)
        runner = folder / 'Main.kt'
        runner.write_text('''
import com.beautifulquran.ui.theme.paperink.PaperInkSubstrate
fun main() {
    System.out.write(PaperInkSubstrate.tile())
    System.out.flush()
}
''')
        jar = folder / 'substrate.jar'
        subprocess.run(['kotlinc', str(ROOT / 'app/src/main/java/com/beautifulquran/ui/theme/paperink/PaperInkSubstrate.kt'),
                        str(runner), '-include-runtime', '-d', str(jar)], check=True, capture_output=True)
        return subprocess.run(['java', '-jar', str(jar)], check=True, capture_output=True).stdout


if __name__ == '__main__':
    if sys.argv[1:] != ['--write']:
        sys.exit(__doc__)
    data = generate()
    assert len(data) == TILE * TILE, len(data)
    ASSET.write_bytes(data)
    print(ASSET, len(data))
