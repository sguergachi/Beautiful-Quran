import { memo, useMemo } from 'react'
import { GeneratedRosette } from '../theme/GeneratedOrnament'
import { chapterOrnamentSeed, generateChapterOrnament } from '../theme/ornamentGenerator'

type Props = {
  chapterNumber: number
  ayahCount: number
  nameArabic: string
  nameTransliteration: string
}

/** Android's compact title: the chapter's own medallion above its two name lines.
 * Static geometry stays out of the word-boundary render path.
 */
export const OrnateSurahTitle = memo(function OrnateSurahTitle({
  chapterNumber,
  ayahCount,
  nameArabic,
  nameTransliteration,
}: Props) {
  const ornament = useMemo(
    () => generateChapterOrnament(chapterOrnamentSeed(chapterNumber, ayahCount)),
    [chapterNumber, ayahCount],
  )
  return (
    <div className="ornate-surah-title">
      <GeneratedRosette
        spec={ornament.rosette}
        className="reader-top-medallion"
        built
        animated={false}
        ruleWidth={5}
        hairWidth={5}
        brightGold="var(--gold-bright)"
        deepGold="var(--gold-deep)"
        embossDark="var(--emboss-dark)"
        embossLight="var(--emboss-light)"
      />
      <div className="ornate-surah-title-text">
        <p className="ornate-ar" lang="ar" dir="rtl">{`سُورَةُ ${nameArabic}`}</p>
        <p className="ornate-en">
          {chapterNumber} · {nameTransliteration.toUpperCase()}
        </p>
      </div>
    </div>
  )
})
