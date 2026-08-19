/** Runtime public asset prefix (e.g. `/` or `/Beautiful-Quran/app/`). */
export const assetUrl = (path: string): string => {
  const base = import.meta.env.BASE_URL || '/'
  const clean = path.replace(/^\//, '')
  return `${base}${clean}`
}
