import { appStore } from '../../store/appStore'
import { BackChevron } from '../kit/BackChevron'
import { PaperSwitch } from '../kit/PaperSwitch'
import { rearmEducation } from '../../data/education'

/** Instructions for the gestures the browser reader supports. */
export function ReadingHelp({ hintsEnabled, onBack }: { hintsEnabled: boolean; onBack: () => void }) {
  return (
    <>
      <button type="button" className="back settings-back" aria-label="Back" onClick={onBack}><BackChevron /></button>
      <h1>Reading help</h1>
      <section className="settings-section reading-help">
        <h2>On the page</h2>
        <dl>
          <dt>Explore a word</dt><dd>Press and hold an Arabic word for its root and dictionary entry. On a desktop, you can also right-click it.</dd>
          <dt>Move between verses</dt><dd>In Scroll, tap or drag the small rail along the page edge. In Mushaf, swipe a leaf or scroll sideways to turn the page.</dd>
          <dt>Keep your place</dt><dd>Tap the outer margin beside a verse to add its ruby bookmark. Tap the ribbon again to remove it. The ruby ribbon on Chapters opens your saved passages.</dd>
          <dt>Share a verse</dt><dd>Tap a verse number to gather it, then tap other verses to add them. Share text or an image, or select one verse and copy its link. Links open paused.</dd>
        </dl>
        <h2>With a keyboard</h2>
        <dl className="reading-shortcuts">
          <dt>Space</dt><dd>Play or pause</dd>
          <dt>↑ / ↓</dt><dd>Previous / next verse</dd>
          <dt>Page Up / Down</dt><dd>Move five verses</dd>
          <dt>Home / End</dt><dd>First / last verse</dd>
          <dt>← / →</dt><dd>Turn a Mushaf leaf; move between words when a word has focus</dd>
          <dt>Enter</dt><dd>Listen from the focused word</dd>
          <dt>Shift + Enter</dt><dd>Explore the focused word</dd>
          <dt>/</dt><dd>Search this chapter</dd>
          <dt>B</dt><dd>Bookmark the reading verse</dd>
          <dt>Escape</dt><dd>Close the current view or return to Chapters</dd>
        </dl>
      </section>
      <section className="settings-section">
        <PaperSwitch id="reading-hints" label="Reading hints" checked={hintsEnabled} onChange={(enabled) => {
          if (enabled) rearmEducation()
          appStore.updateSettings({ educationGuidesEnabled: enabled })
        }} />
        <p className="settings-caption">Brief lessons appear once. Turn this on again to replay them.</p>
      </section>
    </>
  )
}
