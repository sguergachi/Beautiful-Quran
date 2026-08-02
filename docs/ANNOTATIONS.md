# Verse annotations (ḥawāshī)

An **annotation** is a paragraph of commentary attached to one verse. Today the
only source is the reader's own hand; the model is built so a scholar's voice —
a tafsir, a lexical gloss — can join it later without reshaping storage or UI.

A reader's own writing on the page: a short note attached to one verse, kept
in the margin of the sheet it belongs to. This is the app's third piece of
user data, after settings and bookmarks.

**Status: built on Android (reader-written annotations only); web port pending.** Sections marked *Not yet built*
are spec, not description — everything else describes shipped behaviour.

## Why it exists, and what it is not

A mushaf that has been *studied* carries a hand in the margin — a scholar's
**ḥāshiya**: smaller script than the text, keyed to a spot on the page by a
tiny mark, written around the āyāt and never through them. That is exactly
the feature, and the tradition also fixes what it must not become.

It is **not** a comment box, a card, a bottom sheet, a "my notes" app, or a
second document parallel to the Quran. There is no title, no tag, no folder,
no rich text, no formatting bar. A note is a few sentences in the reader's
own hand, and it lives beside the verse that provoked it.

It is also not a translation or a tafsir. The app ships one voice for the
scripture and its gloss; the note is visibly, typographically the *reader's*
voice, and the two must never be confusable.

## The three commitments

1. **A note is a line in the page.** It is composed inside `AyahBlock`, below
   the translation, on the same inner reading spine. Nothing floats, nothing
   is layered, nothing expands a container. (DESIGN.md, "The sheet".)
2. **The margin lane is the reader's own ink.** The bookmark ribbon already
   owns the margin opposite the ayah selector. For now, its exposed saved
   ribbon is also the note's entry gesture, so one edge of the sheet holds
   everything the reader put there and the other stays navigation.
3. **Writing happens on the verse, in place.** The note is composed at the
   exact position it will permanently occupy. No editor surface, no separate
   screen, no "save" step.

## Reading a note

| State | What is on the page |
|---|---|
| Verse is not bookmarked | No note UI, even if writing for that verse remains stored. |
| Bookmarked verse has no note | Only the exposed ruby ribbon. |
| Bookmarked verse has a note | The exposed ruby ribbon and the note text below the verse's translation. |
| Note is long | The text truncates to three lines with a quiet ink ellipsis; tapping the note opens it in place (see "Writing"). |
| Another ayah is reciting | The ribbon vanishes with the rest of the chrome; the text recesses to upcoming ink with its verse, exactly like the translation. |

**Type.** **Cormorant Garamond Italic at weight 500**, 16 sp / 23 sp, a very
light red at 85 % opacity, on the inner spine, 12 dp/px below the translation.
No quotation marks, no "Note:" label, no attribution — the hand says it.

The face is the point. Italic alone is not enough: setting the note in the
app's *own* EB Garamond italic reads as **emphasis**, the same voice leaning,
because it is literally the same typeface as the translation above it.
Cormorant's italic is a genuinely different hand — looser `a` and `e`,
calligraphic `f` and `y`, higher stroke contrast, a wider pen — so the eye
reads a second person on the page rather than the app raising its voice. It is
also the historically right one: chancery cursive is what scribes actually
wrote ḥawāshī in, and what italic type was first cut from.

Two constraints make it work at reading size. Cormorant is a display face and
goes wispy small, so the note uses **weight 500** (a static instance cut from
the variable font, subset to latin + latin-ext at 132 KB) and sits a step
larger than the old EB italic at 16 sp with 0.15 sp letterspacing. Its dark
maroon inky red keeps the reader's own hand distinct from the scripture even
at 85 % opacity. This is a narrow, recorded exception to Cormorant's display-only rule
in [DESIGN.md](DESIGN.md) — do not generalise it to body copy.

**The ribbon.** Tap keeps its existing mark/unmark action. Press and hold on
an exposed saved ribbon opens the verse's note. The same 44 dp target serves
both gestures without adding a second margin glyph; a retracted unsaved tip
does not accept note entry.

On a reader's first bookmark, the ayah glides onto the reading line and briefly
becomes its own contextual lesson. Royal green enters from the paper edge
opposite the new ruby ribbon, owns that entire half of the physical screen from
top to bottom, then tapers toward the ribbon through one continuous per-pixel
wash: dark wet shoulder, long translucent tail, and restrained paper
absorption. A parchment geometric hold mark pulses on the live ribbon, and
a compact theme-paper “Got it” clearing at the thumb-reachable bottom
withdraws the bleed and returns the verse. A vertical drag on the untouched
ribbon half dismisses the lesson and scrolls normally. The reader remains in
place beneath this single paper surface; this is neither an overlay sheet nor a
floating callout. See `ContextualFeatureTip`.

This lesson is currently developer-gated and off by default. Settings →
Developer → **Contextual feature guides** enables it for testing;
**Replay bookmark guide** explicitly rearms it for the next bookmark added.

**Arrival** *(not yet built)*. A note that has just been written should fade in
word by word with the lyric fade — the ink literally arriving on the page. A
note already on the page when the verse scrolls into view is simply there; the
fade is for the moment of writing, not for every appearance.

## Writing a note

**Entry: press and hold the exposed bookmark ribbon.** Notes are currently
available only on bookmarked verses. A tap still marks or unmarks; the hold
opens the in-place editor without toggling the bookmark. The gold ayah mark
`﴿٧﴾` has no note gesture. Tapping an existing note's text opens the same
editor for revision.

Then, in place:

1. A caret appears on the note line beneath the verse, at exactly the
   position the finished note will occupy, and the keyboard rises. The focus
   engine reads the keyboard's completed inset before moving, then parks the
   field 16 dp above the IME in one slow, direction-locked glide. The landing is
   as low as safely possible so the page shows the largest available portion of
   the verse above it.
2. The reader writes. The line grows downward; the verse above never moves.
   The ruby delete cross follows the last writing line, staying beside the
   keyboard even when the beginning of a long note has scrolled away.
   Playback, if running, is **not** interrupted, but scripture taps cannot
   start or seek playback and bookmark ribbons cannot toggle while the field
   owns focus.
3. Tapping anywhere off the note, opening another verse's note, or leaving
   the sheet **commits**. There is no OK, no Save, no Cancel — paper has none
   of them, and an autosaved note cannot be lost to a mis-tap.
4. Committing an empty (or whitespace-only) note deletes it and the line
   closes; the verse remains bookmarked.

The draft is `rememberSaveable` and carries its own `(surah, ayah)`, so it
survives rotation and process death and can never commit onto whichever verse
happens to be loaded when it lands. Opening a second note commits the first
*before* switching, and the stale focus-loss that follows is ignored by an
identity guard — otherwise one verse's text writes itself onto another.

While composing, the other verses recede to upcoming ink and the ayah selector
rail and top app bar withdraw. The visible system status-bar inset remains, but
the active verse gains the rest of that vertical space and stays together with
its note as the only thought on the page.

The ruby cross in the note's margin deletes it without confirmation. Clearing
the text and tapping away does the same thing.

**Hard rules.** No dialog, no bottom sheet, no ink bleed, no ripple. The
cursor is a caret in the paper, not a text field: no box, no underline, no
placeholder chrome. A single quiet placeholder in faint italic ink is
allowed on an empty note and must read as an invitation, not a label.

## What a note is *not* attached to

Verse-level only, for now. A word-level note is tempting, but the word is
already owned by the Root Viewer, and keying a note to a word requires a
manuscript-style reference mark above the line that phone type sizes cannot
carry legibly. Revisit only with a real reading problem to solve.

A note is still keyed to the verse, but its current UI is deliberately scoped
to bookmarks: only an exposed saved ribbon can open it, and only bookmarked
verses show note text. Unmarking a verse hides rather than deletes its stored
writing; marking it again restores that writing. The ayah selector rail needs
no second note mark because every visible note already has the bookmark's ruby
bar.

## Where notes are read back

Notes surface in the existing **Bookmarks** sheet, not a fifth sheet. The
paper stack stays four sheets wide (DESIGN.md, "The sheet"), and the index is
already the app's answer to "what did I mark".

- A bookmarked verse that also carries a note shows the note as one italic
  line beneath its translation, truncated to two lines, on the index's inner
  40 dp/px spine.
- Tapping the note edits it in place. A bookmark without writing carries a
  quiet *Add note* action in its ayah line; holding its exposed ribbon opens
  the same editor. The field is the same chromeless caret-on-paper interaction
  as the reader and commits on focus loss, Done, or leaving the sheet. Blank
  text removes the note without removing the bookmark.
- Tapping the verse copy still returns to the verse in the reader.

*Not yet built:* Index search does not match note text (only reference,
chapter name, and verse text).

The header stays title + return only; no counts.

## Data

`data/AnnotationRepository.kt`, mirroring `BookmarkRepository`'s shape: a single
`StateFlow` the UI observes, its own `SharedPreferences` store, never
`quran.db` (which is read-only and versioned — invariant #1).

The bookmark store's `"surah:ayah:createdAt"` string-set encoding cannot
carry free text: notes contain colons, newlines, and emoji. Notes therefore
use **one preferences key per note**:

```
key:   "note:<surahId>:<ayah>"
value: the note text, verbatim
```

Keys are enumerated from `prefs.all` on load and parsed tolerantly — one
malformed key must never crash the reader (same rule as `Bookmark.decode`,
and covered by `AnnotationRepositoryTest`). No JSON, no Room, no serialization
dependency (invariant #5).

`Annotation(surahId, ayah, text, source)` is the model. The repository exposes
`annotations: StateFlow<List<Annotation>>` in reading order, `annotationFor(surahId, ayah, source)`,
and `write(surahId, ayah, text)` where blank text removes the entry.

Every annotation carries an [AnnotationSource]. Today only `READER` exists and
it is the only writable one; a bundled tafsir would be read-only and belong in
`quran.db`, with this store staying the writable half. The source travels in the
key as a **stable string** (`reader`), never an ordinal, so inserting or
reordering enum entries can never silently reattribute a reader's own writing to
a scholar. Unknown sources are dropped on read rather than guessed. Keys written
before the source dimension existed (`note:<surah>:<ayah>`) still load as
`READER`.

There is deliberately **no** `updatedAt`. Bookmarks carry one because a future
recents view was specified for them; nothing orders notes by time, and an
unused timestamp is a field that has to be kept correct forever for no reader.

**Scale.** SharedPreferences loads the whole file into memory at first
access. A few thousand short notes is well inside that budget; if the store
ever needs to grow past that, it becomes a small writable SQLite file of its
own and never a table inside the bundled asset.

## Switching it off

Settings → **Verse annotations** hides every annotation and disables the entry
gesture on saved ribbons. Stored writing is never deleted — switching it back
on brings it back wherever its verse is still bookmarked. It exists because a
reader who only wants the mushaf should be able to have exactly that, and
because a future scholar's gloss must be refusable too.

## Export *(not yet built)*

Notes are the only user data with no recovery path: the app is offline-first
with no accounts and no backend (invariant #6), so a lost device is a lost
hand. This is the highest-priority follow-up.

Settings → a quiet *Export notes* line writes a plain-text file through the
system document picker (SAF — no storage permission, no share sheet
required):

```
2:255 — Al-Baqarah
    the reader's note text, verbatim

7:31 — Al-A'raf
    …
```

Plain text, reading order, human-readable first and machine-parseable
second. Import is deliberately out of scope for v1; restoring is a manual
read, which is honest about what the format is.

## Web parity *(not yet built)*

Android shipped first; the web port is outstanding and the two must end up
feeling like one product (invariant #7's spirit). The web port stores notes in the same shape under
`localStorage`, keyed identically, alongside `web/src/data/settings.ts`. The
margin tick reuses the web `VerseBookmarkRibbon` lane, and the note line uses
the same italic EB Garamond at the reader's measure. The in-place editor is a
`contenteditable` line styled to nothing — no border, no background, no
resize handle.

## Open questions

1. **Entry gesture discoverability.** The exposed saved ribbon is a stronger
   target than the small ayah mark, but the hold itself remains learned
   behavior. A ruby qalam nib would advertise writing more explicitly at the
   cost of a second permanent affordance. Unresolved; the current build keeps
   the lane quiet and scopes note entry to bookmarked verses.
2. **Long notes on a phone.** In-place composition is the truest reading of the
   metaphor, but a 400-word note pushes the verse off-screen while writing.
   If that proves bad in the hand, the fallback is an ink bleed from the ayah
   mark onto a writing sheet (the [Root Viewer](ROOT_VIEWER.md) primitive) —
   proven code, one step further from the margin.
3. **Notes during recitation.** The note line recesses with its verse, and
   entering the editor does not pause playback. Whether a reader actually wants
   to write *while* listening needs testing with real use.

## Related

- [DESIGN.md](DESIGN.md) — the paper metaphor, the ruby rule, the bookmark
  ribbon and its margin lane, the bookmark index's alignment anchors.
- `data/AnnotationRepository.kt` — the store, mirroring `BookmarkRepository`'s shape.
- `ui/reader/VerseBookmarkRibbon.kt` — the margin lane. Only
  `VerseBookmarkRibbon` lives here now; the separate note tick it once shared
  geometry constants with is gone, and an annotated verse is marked by its note
  text rather than a margin glyph.
- `ui/reader/ReaderComponents.kt` — `verseAnnotationStyle` (the reader's hand, shared
  with the Bookmarks index) and `VerseAnnotationField`.
- `ui/theme/Type.kt` — `ScribeFontFamily`, and why it is not the EB italic.
  The face is OFL (same licence as every other bundled font); the Android cut
  is `res/font/cormorant_garamond_italic.ttf`, mirrored for web in
  `web/public/fonts/` with a `--font-scribe` variable already wired.
