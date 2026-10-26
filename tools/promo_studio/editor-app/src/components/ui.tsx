// Small building blocks, styled once.
import { useEffect, useRef, useState, type ButtonHTMLAttributes, type ReactNode } from "react";

type Variant = "default" | "primary" | "ghost" | "danger" | "subtle";
const VARIANT: Record<Variant, string> = {
  default: "bg-panel2 border-line hover:border-line2 hover:bg-raised",
  primary: "bg-gold text-gold-ink border-gold font-semibold hover:brightness-110",
  ghost: "bg-transparent border-transparent hover:bg-panel2 text-mute hover:text-ink",
  danger: "bg-panel2 border-line text-danger hover:border-danger/60",
  subtle: "bg-transparent border-line hover:bg-panel2",
};

export function Button({ variant = "default", size = "md", className = "", active, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: "sm" | "md" | "icon"; active?: boolean }) {
  const sz = size === "sm" ? "h-7 px-2 text-xs" : size === "icon" ? "h-8 w-8 justify-center" : "h-8 px-3";
  return (
    <button
      {...rest}
      className={`press inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-md border disabled:pointer-events-none disabled:opacity-40 ${sz} ${VARIANT[variant]} ${active ? "!border-gold/60 !text-gold" : ""} ${className}`}
    />
  );
}

export function Segmented<T extends string>({ value, options, onChange, size = "md" }: { value: T; options: [T, ReactNode][]; onChange: (v: T) => void; size?: "sm" | "md" }) {
  return (
    <div className="inline-flex max-w-full flex-wrap rounded-md border border-line bg-panel p-0.5">
      {options.map(([v, label]) => (
        <button key={v} aria-pressed={v === value} onClick={() => onChange(v)}
          className={`press ${size === "sm" ? "h-6 px-2 text-xs" : "h-7 px-2.5"} rounded-[5px] ${v === value ? "bg-raised text-ink shadow-sm" : "text-mute hover:text-ink"}`}>
          {label}
        </button>
      ))}
    </div>
  );
}

export function Field({ label, hint, children }: { label: ReactNode; hint?: ReactNode; children: ReactNode }) {
  return (
    <label className="inspector-field mb-2.5 grid grid-cols-[104px_minmax(0,1fr)] items-center gap-2">
      <span className="text-mute">{label}</span>
      <div className="min-w-0">{children}</div>
      {hint && <p className="col-span-full -mt-1 text-[11.5px] leading-snug text-faint">{hint}</p>}
    </label>
  );
}

const inputCls = "h-8 min-w-0 max-w-full w-full rounded-md border border-line bg-panel2 px-2 outline-none focus:border-gold/70";
export function Select({ value, options, onChange, disabled }: { value: string | number; options: [string | number, string][]; onChange: (v: string) => void; disabled?: boolean }) {
  return (
    <select className={`${inputCls} disabled:opacity-40`} disabled={disabled} value={String(value)} onChange={(e) => onChange(e.target.value)}>
      {options.map(([v, l]) => <option key={String(v)} value={String(v)}>{l}</option>)}
    </select>
  );
}

// Commits on Enter / blur, so typing "1.5" does not save "1." on the way.
// Number fields scrub: drag sideways to change the value (Shift ×10,
// Alt ×0.1), a plain click types. A full range takes about 400 px; a step
// never takes less than 2 px. `onScrub` follows the drag live (one undo step
// is the caller's business, closed by `onScrubEnd`); without it the value
// commits on release.
function scrubber(input: HTMLInputElement, e: React.PointerEvent, start: number, o: { step?: number; min?: number; max?: number },
  show: (v: number) => void, done: (v: number | null) => void) {
  const step = o.step || 1, range = Number.isFinite(o.min) && Number.isFinite(o.max) ? o.max! - o.min! : 0;
  const unit = Math.max(step, range / 400) / 2, decimals = (String(step).split(".")[1] || "").length;
  const x0 = e.clientX;
  let moved = false, v = start;
  input.setPointerCapture(e.pointerId);
  const move = (m: PointerEvent) => {
    const dx = m.clientX - x0;
    if (!moved && Math.abs(dx) < 3) return;
    if (!moved) { moved = true; document.body.classList.add("cur-ew"); }
    const k = m.shiftKey ? 10 : m.altKey ? 0.1 : 1;
    let next = start + Math.round((dx * unit * k) / step) * step;
    if (Number.isFinite(o.min)) next = Math.max(o.min!, next);
    if (Number.isFinite(o.max)) next = Math.min(o.max!, next);
    next = +next.toFixed(Math.min(6, decimals + (m.altKey ? 1 : 0)));
    if (next !== v) { v = next; show(v); }
  };
  const up = () => {
    input.removeEventListener("pointermove", move); input.removeEventListener("pointerup", up); input.removeEventListener("pointercancel", up);
    document.body.classList.remove("cur-ew");
    done(moved ? v : null);
  };
  input.addEventListener("pointermove", move); input.addEventListener("pointerup", up); input.addEventListener("pointercancel", up);
}

export function TextInput({ value, onCommit, onScrub, onScrubEnd, className = "", ...rest }: {
  value: string | number; onCommit: (v: string) => void; onScrub?: (v: number) => void; onScrubEnd?: () => void;
  className?: string; placeholder?: string; type?: string; step?: number; min?: number; max?: number;
}) {
  const [draft, setDraft] = useState(String(value));
  const focused = useRef(false);
  const cancelled = useRef(false);
  useEffect(() => { if (!focused.current) setDraft(String(value)); }, [value]);
  const numeric = rest.type === "number";
  return (
    <input {...rest} className={`${inputCls} ${numeric ? "cursor-ew-resize focus:cursor-text" : ""} ${className}`} value={draft}
      onPointerDown={numeric ? (e) => {
        const input = e.currentTarget, start = Number(draft);
        if (focused.current || e.button !== 0 || !Number.isFinite(start)) return;
        e.preventDefault();                       // no focus or selection while it may become a drag
        scrubber(input, e, start, rest, (v) => { setDraft(String(v)); onScrub?.(v); }, (v) => {
          if (v == null) { input.focus(); input.select(); return; }
          if (onScrub) onScrubEnd?.(); else onCommit(String(v));
        });
      } : undefined}
      onFocus={() => { focused.current = true; cancelled.current = false; }}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={() => {
        focused.current = false;
        if (cancelled.current || (rest.type === "number" && (!draft.trim() || !Number.isFinite(Number(draft))))) setDraft(String(value));
        else if (draft !== String(value)) onCommit(draft);
      }}
      onKeyDown={(e) => { if (e.key === "Enter") (e.target as HTMLInputElement).blur(); if (e.key === "Escape") { e.stopPropagation(); cancelled.current = true; (e.target as HTMLInputElement).blur(); } }} />
  );
}

// A slider with a typed value beside it. onChange fires while dragging
// (the store folds a drag into one undo step); onDone ends the gesture.
export function Slider({ label, value, min, max, step, onChange, onDone, digits = 2 }: { label: string; value: number; min: number; max: number; step: number; onChange: (v: number) => void; onDone?: () => void; digits?: number }) {
  return (
    <div className="flex min-w-0 items-center gap-2">
      <input type="range" aria-label={label} className="h-4 w-0 min-w-0 flex-1" min={min} max={max} step={step} value={value}
        onChange={(e) => onChange(+e.target.value)} onPointerUp={onDone} onKeyUp={onDone} />
      <TextInput type="number" aria-label={`${label} value`} className="num !h-7 !w-[68px] shrink-0 !px-1.5 text-right" step={step} min={min} max={max}
        value={+value.toFixed(digits)} onScrub={onChange} onScrubEnd={onDone} onCommit={(v) => { const n = parseFloat(v); if (Number.isFinite(n)) { onChange(n); onDone?.(); } }} />
    </div>
  );
}

export function Progress({ value, tone = "gold", indeterminate }: { value: number; tone?: "gold" | "green" | "danger" | "blue"; indeterminate?: boolean }) {
  const color = { gold: "bg-gold", green: "bg-green", danger: "bg-danger", blue: "bg-blue" }[tone];
  return (
    <div className="h-1.5 overflow-hidden rounded-full bg-line">
      <div className={`h-full w-full origin-left rounded-full ${color} ${indeterminate ? "animate-pulse-soft" : "transition-transform duration-500 ease-linear"}`} style={indeterminate ? undefined : { transform: `scaleX(${Math.max(0, Math.min(1, value))})` }} />
    </div>
  );
}

export function Dot({ tone }: { tone: "green" | "gold" | "faint" | "orange" | "danger" | "blue" }) {
  const c = { green: "bg-green", gold: "bg-gold", faint: "bg-faint", orange: "bg-orange", danger: "bg-danger", blue: "bg-blue" }[tone];
  return <span className={`inline-block size-2 shrink-0 rounded-full ${c}`} />;
}

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="rounded border border-line2 bg-panel2 px-1.5 py-px font-sans text-[11px] text-mute">{children}</kbd>;
}

export function Section({ title, children, right }: { title: string; children: ReactNode; right?: ReactNode }) {
  return (
    <section className="mb-5">
      <div className="mb-2.5 flex items-center justify-between">
        <h3 className="text-[11px] font-semibold uppercase tracking-[0.12em] text-mute">{title}</h3>
        {right}
      </div>
      {children}
    </section>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="text-[12px] leading-relaxed text-faint">{children}</p>;
}

export const Icon = {
  play: <svg viewBox="0 0 16 16" className="size-4" fill="currentColor"><path d="M4 2.5v11l9.5-5.5z" /></svg>,
  pause: <svg viewBox="0 0 16 16" className="size-4" fill="currentColor"><path d="M3.5 2.5h3v11h-3zM9.5 2.5h3v11h-3z" /></svg>,
  loop: <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M3 9V7a3 3 0 0 1 3-3h7m0 0-2-2m2 2-2 2M13 7v2a3 3 0 0 1-3 3H3m0 0 2 2m-2-2 2-2" /></svg>,
  undo: <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M5 3 2 6l3 3M2.5 6H10a4 4 0 0 1 0 8H7" /></svg>,
  redo: <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="m11 3 3 3-3 3M13.5 6H6a4 4 0 0 0 0 8h3" /></svg>,
  side: <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.5"><rect x="2" y="3" width="12" height="10" rx="1.5" /><path d="M6 3v10" /></svg>,
  sideR: <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.5"><rect x="2" y="3" width="12" height="10" rx="1.5" /><path d="M10 3v10" /></svg>,
  phone: <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.5"><rect x="4.5" y="1.5" width="7" height="13" rx="1.5" /><path d="M7 12.5h2" /></svg>,
  jobs: <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M2.5 4h11M2.5 8h7M2.5 12h9" /></svg>,
  scissors: <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.4"><circle cx="4" cy="4" r="2" /><circle cx="4" cy="12" r="2" /><path d="M5.6 5.2 14 12M5.6 10.8 14 4" /></svg>,
  trash: <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.4"><path d="M3 4.5h10M6.5 4.5V3h3v1.5M4.5 4.5l.6 9h5.8l.6-9" /></svg>,
  key: <svg viewBox="0 0 16 16" className="size-3.5" fill="currentColor"><path d="M8 1.5 14.5 8 8 14.5 1.5 8z" /></svg>,
  plus: <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M8 3v10M3 8h10" /></svg>,
  search: <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="7" cy="7" r="4.5" /><path d="m10.5 10.5 3 3" /></svg>,
  rec: <svg viewBox="0 0 16 16" className="size-4" fill="currentColor"><circle cx="8" cy="8" r="5" /></svg>,
  x: <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="m4 4 8 8M12 4l-8 8" /></svg>,
  check: <svg viewBox="0 0 16 16" className="size-3.5" fill="none" stroke="currentColor" strokeWidth="2"><path d="m3 8.5 3 3 7-7" /></svg>,
  dots: <svg viewBox="0 0 16 16" className="size-4" fill="currentColor"><circle cx="3.5" cy="8" r="1.3" /><circle cx="8" cy="8" r="1.3" /><circle cx="12.5" cy="8" r="1.3" /></svg>,
  exportIcon: <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M8 10V2.5m0 0L5 5.5m3-3 3 3M3 10v3.5h10V10" /></svg>,
};
