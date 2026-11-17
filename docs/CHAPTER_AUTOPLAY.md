# Play on to the next chapter (#916)

Settings → Playback → **Play on to the next chapter** (off by default). The
reciter stays on Settings itself, under Reciter. When a chapter's
recitation plays out, the next chapter starts by itself, with its basmalah, in
the same reciter.

## Which chapter is next

The pinned chapters are a playlist first: **a pinned chapter hands to the
next pin in pin order**. Pins are stored in pin order
(`Settings.pinnedSurahIds` is a list) and the green band shows them in that
order, so the order is never hidden. A new pin lands at the foot of the
band. Installs from before pins had an order keep book order.

- The last pinned chapter hands on to **the book where it sits**: the next
  chapter in book order after it, skipping anything pinned (the band plays
  those in its own turn). A pinned chapter is never replayed from the list.
- The book's own end is the end: playback stops as it always did — unless
  the final chapter (114) is pinned, in which case it wraps to the top of
  the book.
- Search does not change the queue; it follows the full page, not a filter.

Pure rule: `chapterAfter` in `playback/ChapterOrder.kt` (tested in
`ChapterOrderTest`).

The reader's NEXT footer (and the page turn past a chapter's end) follows
the same queue for a pinned chapter: its NEXT is the next pin, then the book
where the last pin sits (`readerNextChapter`). An unpinned chapter's NEXT
stays the book's next chapter, so reading straight through the book never
skips a chapter. Play-on from an unpinned chapter skips pinned ones instead;
the two differ only there.

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
  chapter header.
- Android only. The web port does not have the setting yet.

## Keeping the pins (hold to pin, grip to carry)

Because pin order is the queue, pins are managed on Home (`ui/home/PinReorder.kt`
holds the rules, `holdToPin` and `ReorderHandle` in `HomeScreen.kt` the hands).
Swiping a row to pin it was tried and dropped: horizontal swipes belong to
moving between screens.

- **Hold a row** to pin it (or unpin it, on the band), as ever: the row fills
  with wet ink and holding it full is the confirmation.
- **Reorder from the band's head.** The grips stick out, so they stay put
  away: the PINNED flag carries an icon-only button (up and down arrows,
  on the quiet ink rung secondary buttons use) at its right end, shown
  once two chapters are pinned. Tapping it brings every pinned row's grip
  out (three rules ending on the content edge) and the button shows a
  check; tapping it puts the grips away.
  **Drag a grip** to reorder: the row lifts at once in wet ink and follows
  the finger; the others make room and the list scrolls on near its ends.
  The order is stored when it is set down. Only the grip moves a row, so
  the row's own tap and hold keep their meanings.

TalkBack gets Pin / Unpin and — while the grips are out — Move up / Move
down actions; Shift+Enter still pins from a keyboard. Search lists chapters
in place off the band, so rows there have no grip and are tap-only.

## Pause between verses

Settings → Playback → **Pause between verses** (None, 1, 2, 3 or 5 s;
`Settings.verseGapSeconds`). After each verse the recitation holds that long
in silence before the next begins, for repeating along or reflecting.

How (`playback/VerseGap.kt`): the service's ExoPlayer is set to
`pauseAtEndOfMediaItems`, so it stops exactly at the end of every verse file,
and `VerseGapPlayer` (the session's player, a `ForwardingSimpleBasePlayer`)
starts it again after the gap. No silence is spliced into the audio, so the
files, their clocks and the word timings are untouched.

- The session reports **playing** through the gap (the app's controller, the
  ink, the notification and lock screen never see a pause), as a reciter's own
  pause would read.
- Pause in the gap stays paused; play, a seek or a new chapter go on at once.
  Any other pause (audio focus, headphones unplugged) ends the gap.
- A partial wake lock covers the gap, since the player lets its own go while
  paused and the screen may be off.
- No gap after the basmalah lead-in, in the Timings/Tarji labs (silence
  skipping off), or after a chapter's last verse unless Play-on carries on.
