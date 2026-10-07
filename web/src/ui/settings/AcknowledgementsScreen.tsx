import { BackChevron } from '../kit/BackChevron'

export type Acknowledgement = {
  title: string
  body: string
}

/** The sources this app stands on — mirrors Android's AcknowledgementsPage. */
export const ACKNOWLEDGEMENTS: Acknowledgement[] = [
  {
    title: 'Quran text and translation',
    body: 'Uthmani script and Saheeh International translation via the quran-json project, from Tanzil and Al Quran Cloud. Free with attribution.',
  },
  {
    title: 'Word-by-word gloss',
    body: 'Word-by-word translation, transliteration, and QCF layout from the Quran Foundation authenticated Content API, held in a seven-day local cache. Governed by the QF Developer Terms.',
  },
  {
    title: 'Roots and morphology',
    body: 'Root, lemma, and morphological annotation from the Quranic Arabic Corpus (corpus.quran.com), © Kais Dukes, University of Leeds. Free with attribution and link.',
  },
  {
    title: 'Word timings',
    body: 'Word-level audio timing data © the quran-align project contributors, CC-BY 4.0.',
  },
  {
    title: 'Yasser Al-Dosari timings',
    body: "Word timings from Qur'anic Universal Audio, CC-BY 4.0.",
  },
  {
    title: 'Repeat-aware timings',
    body: 'Bundled repeat topology from the quran.com legacy qdc audio API, normalized offline. Written QF permission requested before release.',
  },
  {
    title: 'Recitation audio',
    body: 'Streamed from everyayah.com. Free; all rights to the recitations belong to the respective reciters.',
  },
  {
    title: 'Arabic typeface',
    body: 'KFGQPC HAFS Uthmanic Script © King Fahd Glorious Quran Printing Complex, Madinah. Redistribution permission / official license confirmation pending.',
  },
]

/** The credits leaf: every source the app stands on, on its own sheet. */
export function AcknowledgementsScreen({ onBack }: { onBack: () => void }) {
  return (
    <div className="acknowledgements">
      <button type="button" className="back settings-back" aria-label="Back" onClick={onBack}>
        <BackChevron />
      </button>
      <h1>Acknowledgements</h1>
      <p className="settings-caption acknowledgements-caption">
        Every source this app stands on.
      </p>
      {ACKNOWLEDGEMENTS.map((entry) => (
        <section key={entry.title} className="acknowledgements-entry">
          <h2 className="acknowledgements-title">{entry.title}</h2>
          <p className="acknowledgements-body">{entry.body}</p>
        </section>
      ))}
      <p className="settings-caption acknowledgements-foot">
        This app is free, ad-free, and collects no data.
      </p>
    </div>
  )
}
