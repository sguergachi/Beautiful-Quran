# QCF V2 page fonts (tracked, not bundled)

These are the split `qcf-v2-fonts.tar.xz.part*` archives of the 604 QCF/QPC V2
per-page Mushaf fonts. `syncQcfFonts` extracts them into generated assets as
`.qcf` (same SFNT bytes, not `.ttf`) so aapt deflates them in the APK.

The app currently renders Arabic-only mode with the responsive Hafs renderer
only; the QCF ("Mushaf") renderer and its `QcfFontProvider` were removed. These
archives are retained so a future full Mushaf-style reader can re-enable QCF
rendering without re-downloading the fonts.

To re-bundle: move the parts back into `app/src/main/assets/`, restore the
`noCompress += "part0"/"part1"` entries in `app/build.gradle.kts`, and restore
the QCF render path (see git history around `QcfFontProvider`).

Regenerate with `scripts/fetch_qcf_v2_fonts.sh`.

See `docs/CONNECTED_ARABIC_RENDERING.md` → "QCF renderer status" for context.
