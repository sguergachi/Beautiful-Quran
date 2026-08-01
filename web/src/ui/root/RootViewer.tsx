import { useEffect, useId, useMemo, useState } from 'react'
import { appStore, useAppSelector, type RootViewerState } from '../../store/appStore'
import { IconClose, IconVolumeUp } from '../icons/PlaybackIcons'
import { featureSummary, posLabel, spacedRoot } from './morphologyLabels'
import {
  DICTIONARY_PREVIEW_SENSES,
  dictionaryGlosses,
  dictionaryNeedsExpand,
  wiktionaryArabicUrl,
} from './dictionaryText'
import {
  LEXICON_PREVIEW_CHARS,
  lexiconArticleText,
  lexiconBlocks,
  lexiconFormCount,
  lexiconRootSense,
  lexiconRuns,
} from './lexiconText'
import { laneLexiconUrl, rootViewerReferences } from './rootViewerReferences'
import {
  initialRootSections,
  relatedRootForms,
  rootOccurrenceSections,
  ROOT_CHAPTER_PREVIEW_LIMIT,
  ROOT_OCCURRENCE_PREVIEW_LIMIT,
  ROOT_RELATED_FORM_PREVIEW_LIMIT,
} from './rootViewerSections'

/** Exit hole duration — keep in sync with `.ink-bleed[data-closing]`. */
const BLEED_OUT_MS = 420

/** Lane prose: Arabic mushaf face, quiet citations; glue "see" to its target. */
function LexiconProse({ text }: { text: string }) {
  const runs = lexiconRuns(text)
  return (
    <>
      {runs.map((run, index) =>
        run.isArabic ? (
          <span key={index} lang="ar" className="root-lexicon-arabic">
            {run.text}
          </span>
        ) : run.isCitation ? (
          <span key={index} className="root-lexicon-citation">
            {run.text}
            {runs[index + 1]?.isArabic ? '\u2060' : null}
          </span>
        ) : (
          <span key={index}>{run.text}</span>
        ),
      )}
    </>
  )
}
const HELP = {
  root: 'Usually three consonants shared by a family of related words. A root points to a meaning family, not one fixed translation.',
  lemma:
    'The dictionary headword for this form. Other endings of the same word share this lemma; the root is the wider family.',
  dictionary: 'English senses from Wiktionary (CC BY-SA).',
  occurrences: 'Every Quran word annotated with this root. Shared roots suggest a family resemblance, but context decides the meaning.',
  related: 'Other dictionary headwords built from the same root. Their meanings may be related, but are not necessarily identical. Each English line is the rendering used most often for that form, not a dictionary definition.',
} as const

/** Lemma ⓘ — definition, plus a one-line Wiktionary credit when senses are shown. */
function lemmaHelp(hasDictionary: boolean): string {
  return hasDictionary ? `${HELP.lemma} ${HELP.dictionary}` : HELP.lemma
}

/** Lane intro plus the Perseus credit — shown in the section ⓘ, not under every entry. */
function lexiconHelp(page: number, credit: string): string {
  const pageMark = page > 0 ? `, p. ${page}` : ''
  return (
    "Edward Lane's Arabic-English Lexicon (1863–93), the deepest classical dictionary in English. " +
    'It describes the root across all of Arabic, not only the Quran, and cites the mediaeval ' +
    `lexicographers it draws on in parentheses. Edward William Lane, An Arabic-English Lexicon${pageMark}. ${credit}`
  )
}

function times(count: number) {
  return count === 1 ? 'once' : `${count} times`
}

function occurrences(count: number) {
  return `${count} ${count === 1 ? 'occurrence' : 'occurrences'}`
}

function ExplainedHeading({
  label,
  explanation,
  kind = 'label',
  id,
  iconNudgePx = 0,
}: {
  label: string
  explanation: string
  kind?: 'label' | 'section'
  id?: string
  /** Optical lift for the ⓘ, in px (ROOT needs a hair more than LEMMA). */
  iconNudgePx?: number
}) {
  const [open, setOpen] = useState(false)
  const helpId = useId()
  return (
    <div className="root-explained">
      <h2 id={id} className={kind === 'section' ? 'root-section-title' : 'root-label'}>
        <span>{label}</span>
        <button
          type="button"
          className="root-info-button"
          style={iconNudgePx ? { top: `${-iconNudgePx}px` } : undefined}
          aria-label={`Explain ${label.toLowerCase()}`}
          aria-expanded={open}
          aria-controls={helpId}
          onClick={() => setOpen((value) => !value)}
        >
          ⓘ
        </button>
      </h2>
      {open ? <p className="root-explanation" id={helpId}>{explanation}</p> : null}
    </div>
  )
}

/** Root lexicon ink bleed hosted inside the reader sheet. */
export function RootViewer() {
  const rv = useAppSelector((s) => s.rootViewer)
  const closing = useAppSelector((s) => s.rootViewerClosing)

  useEffect(() => {
    if (!closing) return
    const timer = window.setTimeout(() => appStore.finishCloseRootViewer(), BLEED_OUT_MS + 80)
    return () => window.clearTimeout(timer)
  }, [closing])

  return rv ? <RootViewerBleed closing={closing} rv={rv} /> : null
}

function RootViewerBleed({ closing, rv }: { closing: boolean; rv: RootViewerState }) {
  const sections = useMemo(() => rootOccurrenceSections(rv.occurrences), [rv.occurrences])
  const relatedForms = useMemo(
    () => relatedRootForms(rv.lemmas, rv.lemma, rv.pos),
    [rv.lemma, rv.lemmas, rv.pos],
  )
  const lemmaCount = rv.lemmas
    .filter((entry) => entry.lemma === rv.lemma)
    .reduce((total, entry) => total + entry.occurrenceCount, 0)
  const [openSurahId, setOpenSurahId] = useState<number | null>(rv.surahId)
  const [showAllOccurrences, setShowAllOccurrences] = useState(false)
  const [showAllChapters, setShowAllChapters] = useState(false)
  const [showAllForms, setShowAllForms] = useState(false)
  const [showWholeEntry, setShowWholeEntry] = useState(false)
  const [showAllDictionarySenses, setShowAllDictionarySenses] = useState(false)

  useEffect(() => {
    setOpenSurahId(rv.surahId)
    setShowAllOccurrences(false)
    setShowAllChapters(false)
    setShowAllForms(false)
    setShowWholeEntry(false)
    setShowAllDictionarySenses(false)
  }, [rv.root, rv.surahId, rv.lemma])

  const visibleSections = showAllChapters
    ? sections
    : initialRootSections(sections, rv.surahId)
  const visibleForms = showAllForms
    ? relatedForms
    : relatedForms.slice(0, ROOT_RELATED_FORM_PREVIEW_LIMIT)
  const references = useMemo(
    () => rootViewerReferences(rv.surahId, rv.ayah, rv.position, rv.root),
    [rv.ayah, rv.position, rv.root, rv.surahId],
  )
  const lexiconText = useMemo(
    () => (rv.lexicon ? lexiconArticleText(rv.lexicon.text, showWholeEntry) : ''),
    [rv.lexicon, showWholeEntry],
  )
  const lexiconEntryBlocks = useMemo(
    () => (lexiconText ? lexiconBlocks(lexiconText) : []),
    [lexiconText],
  )
  const rootSense = useMemo(
    () => (rv.lexicon ? lexiconRootSense(rv.lexicon.text) : null),
    [rv.lexicon],
  )
  const lexiconHasMultipleForms = useMemo(
    () => (rv.lexicon ? lexiconFormCount(rv.lexicon.text) > 1 : false),
    [rv.lexicon],
  )
  const dictionaryRows = useMemo(
    () => (rv.dictionary ? dictionaryGlosses(rv.dictionary, rv.pos) : []),
    [rv.dictionary, rv.pos],
  )
  const visibleDictionaryRows = showAllDictionarySenses
    ? dictionaryRows
    : dictionaryRows.slice(0, DICTIONARY_PREVIEW_SENSES)

  return (
    <div
      className="ink-bleed"
      data-closing={closing ? 'true' : undefined}
      style={{ ['--ox' as string]: '50%', ['--oy' as string]: '35%' }}
      onAnimationEnd={(event) => {
        if (event.target === event.currentTarget && closing && event.animationName === 'bleed-out') {
          appStore.finishCloseRootViewer()
        }
      }}
    >
      <div className="ink-bleed-inner">
        <div className="ink-bleed-chrome">
          <button type="button" className="close" aria-label="Close" onClick={() => appStore.closeRootViewer()}>
            <IconClose width="1.35em" height="1.35em" />
          </button>
        </div>

        <header className="root-opening root-prose-measure">
          <div className="root-word-title">
            <p className="root-word-arabic" lang="ar" dir="rtl">{rv.arabic}</p>
            <button
              type="button"
              className="root-word-speak"
              aria-label="Play word"
              data-playing={rv.isPlayingWord ? 'true' : undefined}
              onClick={() => void appStore.playRootViewerWord()}
            >
              <IconVolumeUp width="1.375rem" height="1.375rem" />
            </button>
          </div>
          {rv.translation ? <p className="root-word-gloss">{rv.translation}</p> : null}
          {rv.transliteration ? <p className="root-word-transliteration">{rv.transliteration}</p> : null}
        </header>

        <main>
          {(rv.root || rv.lemma || rv.pos) ? (
            <section className="root-analysis root-prose-measure" aria-label="Word analysis">
              <div className="root-analysis-group">
                <ExplainedHeading label="Root" explanation={HELP.root} iconNudgePx={2} />
                {rv.root ? (
                  <>
                    <p className="root-radicals" lang="ar" dir="rtl">{spacedRoot(rv.root)}</p>
                    {rootSense ? (
                      <p className="root-sense">
                        <LexiconProse text={rootSense} />
                      </p>
                    ) : null}
                    {lexiconHasMultipleForms ? (
                      <button
                        type="button"
                        className="root-text-action root-sense-more"
                        onClick={() => {
                          document
                            .getElementById('root-lexicon')
                            ?.scrollIntoView({ behavior: 'smooth', block: 'start' })
                        }}
                      >
                        See more detail
                      </button>
                    ) : null}
                  </>
                ) : (
                  <p className="root-no-root">No lexical root is annotated for this word.</p>
                )}
              </div>
              {rv.lemma ? (
                <div className="root-analysis-group">
                  <ExplainedHeading
                    label="Lemma"
                    explanation={lemmaHelp(!!rv.dictionary)}
                    iconNudgePx={1}
                  />
                  {rv.dictionary && dictionaryRows.length > 0 ? (
                    <div className="root-lemma-dict-block">
                      <div
                        className={
                          visibleDictionaryRows.length === 1
                            ? 'root-lemma-pair root-lemma-pair--single'
                            : 'root-lemma-pair'
                        }
                      >
                        <p className="root-form-lemma" lang="ar" dir="rtl">{rv.lemma}</p>
                        {visibleDictionaryRows.length === 1 ? (
                          <div className="root-lemma-elbow" aria-hidden="true">
                            <span className="root-lemma-elbow-arm" />
                            <span className="root-lemma-elbow-spine" />
                            <span className="root-lemma-elbow-arm" />
                          </div>
                        ) : (
                          <div className="root-lemma-connector" aria-hidden="true" />
                        )}
                        <div className="root-lemma-dictionary">
                          {visibleDictionaryRows.map((row, index) => {
                            const multiPos =
                              dictionaryRows.filter((r) => r.pos).length > 1 && !!row.pos
                            // First gloss owns the column baseline (locks to lemma);
                            // opening POS rides inline so nothing sits above it.
                            const gloss =
                              index === 0 && multiPos
                                ? `${row.pos} · ${row.gloss}`
                                : row.gloss
                            return (
                              <div key={index} className="root-lemma-sense">
                                {multiPos && index > 0 ? (
                                  <p className="root-lemma-pos">{row.pos}</p>
                                ) : null}
                                <p className="root-sense">{gloss}</p>
                              </div>
                            )
                          })}
                          {dictionaryNeedsExpand(dictionaryRows.length) ? (
                            <button
                              type="button"
                              className="root-text-action root-sense-more root-lemma-sense-more"
                              onClick={() => setShowAllDictionarySenses((value) => !value)}
                            >
                              {showAllDictionarySenses ? 'Show less' : 'Show more'}
                            </button>
                          ) : null}
                        </div>
                      </div>
                      {(rv.pos || featureSummary(rv.features)) ? (
                        <p className="root-form-grammar root-lemma-meta">
                          {[rv.pos ? posLabel(rv.pos) : '', featureSummary(rv.features)]
                            .filter(Boolean)
                            .join(' · ')}
                        </p>
                      ) : null}
                      {lemmaCount > 0 ? (
                        <p className="root-form-frequency root-lemma-meta">
                          This lemma occurs {times(lemmaCount)}.
                        </p>
                      ) : null}
                      <a
                        className="root-text-action root-sense-more root-lemma-dict-action"
                        href={wiktionaryArabicUrl(rv.dictionary.word)}
                        target="_blank"
                        rel="noreferrer"
                      >
                        Open on Wiktionary <span aria-hidden="true">↗</span>
                      </a>
                    </div>
                  ) : (
                    <>
                      <p className="root-form-lemma" lang="ar" dir="rtl">{rv.lemma}</p>
                      {(rv.pos || featureSummary(rv.features)) ? (
                        <p className="root-form-grammar root-lemma-meta">
                          {[rv.pos ? posLabel(rv.pos) : '', featureSummary(rv.features)]
                            .filter(Boolean)
                            .join(' · ')}
                        </p>
                      ) : null}
                      {lemmaCount > 0 ? (
                        <p className="root-form-frequency root-lemma-meta">
                          This lemma occurs {times(lemmaCount)}.
                        </p>
                      ) : null}
                    </>
                  )}
                </div>
              ) : null}
            </section>
          ) : null}

          {rv.root && rv.occurrenceCount > 0 ? (
            <section className="root-section root-occurrences" aria-labelledby="root-occurrences-title">
              <div className="root-prose-measure">
                <ExplainedHeading
                  id="root-occurrences-title"
                  kind="section"
                  label="Occurrences"
                  explanation={HELP.occurrences}
                />
                <p className="root-section-summary">
                  This root occurs {times(rv.occurrenceCount)} across {sections.length}{' '}
                  {sections.length === 1 ? 'chapter' : 'chapters'}.
                </p>
              </div>

              <div className="root-chapter-list">
                {visibleSections.map((section) => {
                  const open = section.surahId === openSurahId
                  const visible = showAllOccurrences && open
                    ? section.occurrences
                    : section.occurrences.slice(0, ROOT_OCCURRENCE_PREVIEW_LIMIT)
                  const hiddenCount = section.occurrences.length - visible.length
                  const regionId = `root-chapter-${section.surahId}`
                  return (
                    <section className="root-chapter" key={section.surahId}>
                      <button
                        type="button"
                        className="root-chapter-heading"
                        aria-label={`${section.surahId} ${section.surahName}, ${occurrences(section.occurrences.length)}, ${open ? 'collapse' : 'expand'}`}
                        aria-expanded={open}
                        aria-controls={regionId}
                        onClick={() => {
                          setOpenSurahId(open ? null : section.surahId)
                          setShowAllOccurrences(false)
                        }}
                      >
                        <span className="root-chapter-name"><i>{section.surahId}</i>{section.surahName}</span>
                        <span className="root-chapter-meta" aria-hidden="true">
                          <span className="root-chapter-count">{section.occurrences.length}</span>
                          <span className="root-chapter-disclosure">{open ? '⌄' : '‹'}</span>
                        </span>
                      </button>
                      {open ? (
                        <div className="root-chapter-occurrences" id={regionId}>
                          {visible.map((entry) => {
                            const current = entry.surahId === rv.surahId && entry.ayahNumber === rv.ayah && entry.position === rv.position
                            return (
                              <button
                                type="button"
                                className="root-occurrence"
                                data-current={current ? 'true' : undefined}
                                key={`${entry.surahId}:${entry.ayahNumber}:${entry.position}`}
                                onClick={() => {
                                  // Concordance jump: do not resume the pre-open session.
                                  appStore.closeRootViewer(false)
                                  appStore.openSurah(entry.surahId, entry.ayahNumber)
                                }}
                              >
                                <span className="root-occurrence-ref">{entry.surahId}:{entry.ayahNumber}{current ? ' · Here' : ''}</span>
                                <span className="root-occurrence-word">
                                  <span lang="ar" dir="rtl">{entry.arabic}</span>
                                  {entry.translation ? <span>{entry.translation}</span> : null}
                                </span>
                              </button>
                            )
                          })}
                          {(hiddenCount > 0 || (showAllOccurrences && section.occurrences.length > ROOT_OCCURRENCE_PREVIEW_LIMIT)) ? (
                            <button type="button" className="root-text-action" onClick={() => setShowAllOccurrences((value) => !value)}>
                              {hiddenCount > 0 ? `Show ${hiddenCount} more ${hiddenCount === 1 ? 'occurrence' : 'occurrences'}` : 'Show fewer occurrences'}
                            </button>
                          ) : null}
                        </div>
                      ) : null}
                    </section>
                  )
                })}
              </div>
              {sections.length > ROOT_CHAPTER_PREVIEW_LIMIT ? (
                <button type="button" className="root-text-action root-chapters-action" onClick={() => setShowAllChapters((value) => !value)}>
                  {showAllChapters ? 'Show fewer chapters' : `Show ${sections.length - visibleSections.length} more chapters`}
                </button>
              ) : null}
            </section>
          ) : null}

          {relatedForms.length > 0 ? (
            <section className="root-section root-related" aria-labelledby="root-related-title">
              <ExplainedHeading
                id="root-related-title"
                kind="section"
                label="Related forms"
                explanation={HELP.related}
              />
              <div className="root-related-list">
                {visibleForms.map((entry) => (
                  <div className="root-related-row" key={`${entry.lemma}:${entry.pos}`}>
                    <span className="root-related-form">
                      <span lang="ar" dir="rtl">{entry.lemma}</span>
                      <span>{posLabel(entry.pos)}</span>
                    </span>
                    <span className="root-related-count">{entry.occurrenceCount === 1 ? 'once' : `${entry.occurrenceCount}×`}</span>
                    {entry.gloss ? <p className="root-related-gloss">{entry.gloss}</p> : null}
                  </div>
                ))}
              </div>
              {relatedForms.length > ROOT_RELATED_FORM_PREVIEW_LIMIT ? (
                <button type="button" className="root-text-action" onClick={() => setShowAllForms((value) => !value)}>
                  {showAllForms ? 'Show fewer forms' : `Show ${relatedForms.length - visibleForms.length} more forms`}
                </button>
              ) : null}
            </section>
          ) : null}

          {rv.lexicon ? (
            <section
              id="root-lexicon"
              className="root-section root-lexicon"
              aria-labelledby="root-lexicon-title"
            >
              <ExplainedHeading
                id="root-lexicon-title"
                kind="section"
                label="Classical lexicon"
                explanation={lexiconHelp(rv.lexicon.page, rv.lexicon.credit)}
              />
              <div className="root-lexicon-entry">
                {lexiconEntryBlocks.map((block, blockIndex) => (
                  <div
                    key={blockIndex}
                    className={
                      block.form
                        ? 'root-lexicon-block root-lexicon-block--form'
                        : 'root-lexicon-block'
                    }
                  >
                    {block.form ? <h3 className="root-lexicon-form">{block.form}</h3> : null}
                    {block.text ? (
                      <p>
                        <LexiconProse text={block.text} />
                      </p>
                    ) : null}
                  </div>
                ))}
              </div>
              {rv.lexicon.text.length > LEXICON_PREVIEW_CHARS ? (
                <button
                  type="button"
                  className="root-text-action"
                  onClick={() => setShowWholeEntry((value) => !value)}
                >
                  {showWholeEntry ? 'Show less of the entry' : 'Read the whole entry'}
                </button>
              ) : null}
              <a
                className="root-lexicon-online"
                href={laneLexiconUrl(rv.root)}
                target="_blank"
                rel="noreferrer"
              >
                <span className="root-lexicon-online-title">
                  Open the full entry online <span aria-hidden="true">↗</span>
                </span>
                <span className="root-lexicon-online-description">
                  Lane on arabiclexicon.hawramani.com — the complete article for this root.
                </span>
              </a>
            </section>
          ) : null}

          <section className="root-section root-references" aria-labelledby="root-references-title">
            <h2 id="root-references-title" className="root-section-title">Learn more online</h2>
            <div className="root-reference-list">
              {references.map((reference) => (
                <a key={reference.url} className="root-reference" href={reference.url} target="_blank" rel="noreferrer">
                  <span className="root-reference-title">{reference.title} <span aria-hidden="true">↗</span></span>
                  <span className="root-reference-description">{reference.description}</span>
                </a>
              ))}
            </div>
          </section>
        </main>

        <p className="ink-bleed-attribution">
          Morphology from the Quranic Arabic Corpus v0.4 —{' '}
          <a href="https://corpus.quran.com" target="_blank" rel="noreferrer">corpus.quran.com</a>
        </p>
      </div>
    </div>
  )
}
