import type { RepeatMode, RepeatRange } from '../../playback/playlistNext'

export const REPEAT_CHOICES = [
  { value: 'off', label: 'Off' },
  { value: 'ayah', label: 'This ayah' },
  { value: 'surah', label: 'Whole surah' },
  { value: 'range', label: 'A range of ayahs' },
  { value: 'from_here', label: 'From this ayah' },
] as const
export type RepeatChoice = (typeof REPEAT_CHOICES)[number]['value']
export interface RepeatDraft {
  choice: RepeatChoice
  from: number
  to: number
  count: number
}
const clamp = (n: number, lo: number, hi: number) =>
  Math.max(lo, Math.min(hi, Math.trunc(n)))

/** Android RepeatSheet: retain the selected instrument even for identical spans. */
export function repeatDraft(
  mode: RepeatMode,
  range: RepeatRange | null,
  current: number,
  total: number,
  retained: RepeatChoice | null,
): RepeatDraft {
  const ayahs = Math.max(1, total),
    first = clamp(current, 1, ayahs)
  const choice = range
    ? retained === 'range' || retained === 'from_here'
      ? retained
      : range.first === range.last
        ? 'ayah'
        : range.first === first
          ? 'from_here'
          : 'range'
    : mode === 'range'
      ? 'off'
      : mode
  return {
    choice,
    from: clamp(range?.first ?? first, 1, ayahs),
    to: clamp(range?.last ?? ayahs, 1, ayahs),
    count: clamp(
      range && range.first === first ? range.last - range.first + 1 : 2,
      1,
      ayahs - first + 1,
    ),
  }
}
export function chooseRepeat(
  draft: RepeatDraft,
  choice: RepeatChoice,
  current: number,
  total: number,
): RepeatDraft {
  if (draft.choice === 'from_here' && choice === 'range')
    return {
      ...draft,
      choice,
      from: current,
      to: Math.min(total, current + draft.count - 1),
    }
  if (draft.choice === 'range' && choice === 'from_here')
    return {
      ...draft,
      choice,
      count: clamp(draft.to - current + 1, 1, total - current + 1),
    }
  return { ...draft, choice }
}
export function repeatSelection(
  draft: RepeatDraft,
  current: number,
  total: number,
): { mode: RepeatMode; range: RepeatRange | null } {
  const first = clamp(current, 1, Math.max(1, total))
  if (draft.choice === 'off' || draft.choice === 'surah')
    return { mode: draft.choice, range: null }
  if (draft.choice === 'ayah')
    return { mode: 'range', range: { first, last: first } }
  const from =
    draft.choice === 'from_here' ? first : clamp(draft.from, 1, total)
  const last = draft.choice === 'from_here' ? from + draft.count - 1 : draft.to
  return {
    mode: 'range',
    range: { first: from, last: clamp(last, from, total) },
  }
}
