# Play on to the next chapter (#916)

Settings → **Play on to the next chapter** (off by default). When a chapter's
recitation plays out, the next chapter starts by itself, with its basmalah, in
the same reciter.

## Which chapter is next

The queue is the Chapters page read top to bottom: **pinned chapters in the
order they were pinned, then every other chapter in book order**. What plays
next is always what the reader can see below the current row. Pins are stored in
pin order (`Settings.pinnedSurahIds` is a list) and the green band shows them in
that order, so the order is never hidden. A new pin lands at the foot of the
band. Installs from before pins had an order keep book order.

- The last pinned chapter hands on to the first unpinned one (Al-Fatihah unless
  pinned), then down the page. A pinned chapter is never replayed from the list.
- The foot of the page (114, or the last unpinned chapter) is the end: playback
  stops as it always did.
- Search does not change the queue; it follows the full page, not a filter.

Pure rule: `chapterAfter` in `playback/ChapterOrder.kt` (tested in
`ChapterOrderTest`).

## How it fires

`PlayerUiState.finishedSurahId` is set when Media3 reaches `STATE_ENDED` and
cleared as soon as anything is loaded. `MainActivity` collects it and, with the
setting on, calls `readerViewModel.load(next, startPlaybackAtAyah = 1,
keepContent = true)` — exactly the path a tap on a chapter takes — then points
the sheet at the new chapter when one is open. The Continue place follows
because listening position is recorded by that same path.

Things that do not fire it: pausing, closing the session (`stop()`), a repeat
range or single-ayah loop (those never reach `STATE_ENDED`), a playback error.

## Limits

- Autoplay is driven from the activity's view model. If Android destroys the
  activity while audio is still playing (swiped away from recents), the chapter
  finishes and playback stops there.
- The hand-off swaps the chapter in place (outgoing text stays on the glass
  until the new one is ready). It does not yet reuse the NEXT footer's flying
  chapter header; that only fits when the next chapter is n+1.
- Android only. The web port does not have the setting yet.

## Keeping the pins (hold to pin, grip to carry)

Because pin order is the queue, pins are managed on Home (`ui/home/PinReorder.kt`
holds the rules, `holdToPin` and `ReorderHandle` in `HomeScreen.kt` the hands).
Swiping a row to pin it was tried and dropped: horizontal swipes belong to
moving between screens.

- **Hold a row** to pin it (or unpin it, on the band), as ever: the row fills
  with wet ink and holding it full is the confirmation.
- **Drag a pinned row's grip** (three rules in the end margin) to reorder. The
  row lifts at once in wet ink and follows the finger; the others make room
  and the list scrolls on near its ends. The order is stored when it is set
  down. Only the grip moves a row, so the row's own tap and hold keep their
  meanings.

TalkBack gets Pin / Unpin and Move up / Move down actions; Shift+Enter still
pins from a keyboard. Search lists chapters in place off the band, so rows
there have no grip and are tap-only.
