import darkWordmark from '../../../../data/branding/quran-foundation-dark.svg'
import whiteWordmark from '../../../../data/branding/quran-foundation-white.svg'

/** Supplied QF wordmarks retain their own proportions and colors in both paper themes. */
export function QfLogo({ alt = 'Quran Foundation' }: { alt?: string }) {
  return <span className="qf-brand">
    <img className="qf-brand-dark" src={darkWordmark} width="227" height="61" alt={alt} />
    <img className="qf-brand-white" src={whiteWordmark} width="335" height="60" alt={alt} />
  </span>
}
