// A right-click menu: openMenu(event, items) from anywhere, rendered once.
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { create } from "zustand";

export type MenuItem =
  | { label: string; run: () => void; danger?: boolean; checked?: boolean; disabled?: boolean; hint?: string }
  | { head: string }
  | "-";
const useMenu = create<{ at: [number, number] | null; items: MenuItem[] }>(() => ({ at: null, items: [] }));

export function openMenu(e: { clientX: number; clientY: number; preventDefault: () => void; stopPropagation: () => void }, items: MenuItem[]) {
  e.preventDefault(); e.stopPropagation();
  useMenu.setState({ at: [e.clientX, e.clientY], items });
}
export const closeMenu = () => useMenu.setState({ at: null });

export function MenuHost() {
  const { at, items } = useMenu();
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<[number, number]>([0, 0]);
  useLayoutEffect(() => {
    if (!at || !ref.current) return;
    const r = ref.current.getBoundingClientRect();
    setPos([Math.max(6, Math.min(at[0], innerWidth - r.width - 6)), Math.max(6, Math.min(at[1], innerHeight - r.height - 6))]);
  }, [at, items]);
  useEffect(() => {
    if (!at) return;
    const off = (e: Event) => { if (!ref.current?.contains(e.target as Node)) closeMenu(); };
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") { e.stopPropagation(); closeMenu(); } };
    addEventListener("pointerdown", off, true); addEventListener("keydown", key, true); addEventListener("blur", closeMenu);
    addEventListener("wheel", off, { passive: true }); addEventListener("resize", closeMenu);
    return () => { removeEventListener("pointerdown", off, true); removeEventListener("keydown", key, true); removeEventListener("blur", closeMenu); removeEventListener("wheel", off); removeEventListener("resize", closeMenu); };
  }, [at]);
  if (!at) return null;
  return (
    <div ref={ref} role="menu" style={{ left: pos[0], top: pos[1] }}
      className="fixed z-50 flex min-w-[190px] max-w-[calc(100vw-12px)] max-h-[calc(100dvh-12px)] flex-col overflow-y-auto overscroll-contain rounded-lg border border-line bg-panel2 p-1 shadow-2xl shadow-black/60 [&>*]:shrink-0">
      {items.map((it, i) =>
        it === "-" ? <div key={i} className="mx-1 my-1 h-px bg-line" /> :
        "head" in it ? <div key={i} className="px-2.5 pb-0.5 pt-1.5 text-[11px] text-faint">{it.head}</div> :
        <button key={i} disabled={it.disabled} onClick={() => { closeMenu(); it.run(); }}
          className={`flex items-center gap-2 rounded px-2.5 py-1.5 text-left hover:bg-white/5 disabled:opacity-40 ${it.danger ? "text-danger" : ""}`}>
          <span className="w-3.5 text-gold">{it.checked ? "✓" : ""}</span>
          <span className="flex-1">{it.label}</span>
          {it.hint && <span className="text-[11px] text-faint">{it.hint}</span>}
        </button>,
      )}
    </div>
  );
}
