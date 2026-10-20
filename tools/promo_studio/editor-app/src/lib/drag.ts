// Follow one pointer until it is released. The pointer is captured on `host`,
// an element that outlives re-renders, so rebuilding the rows under the
// pointer never drops the gesture and a release outside the window still
// ends it. `edge`: a scroll box to scroll when the pointer nears its sides
// (kept scrolling while the pointer rests there).
export function track(
  ev: { pointerId: number; clientX: number; clientY: number },
  host: HTMLElement,
  move: (e: PointerEvent) => void,
  up?: (e: PointerEvent) => void,
  opts: { cursor?: "ew" | "col" | "row" | "grab"; edge?: { box: HTMLElement; left: number } } = {},
) {
  try { host.setPointerCapture(ev.pointerId); } catch { /* synthetic events have no live pointer */ }
  document.body.classList.add("dragging");
  if (opts.cursor) document.body.classList.add(`cur-${opts.cursor}`);
  let last: PointerEvent | null = null, timer: number | undefined;
  const nudge = () => {
    if (!opts.edge || !last) return false;
    const r = opts.edge.box.getBoundingClientRect(), left = r.left + opts.edge.left + 28, right = r.right - 28;
    const d = last.clientX < left ? last.clientX - left : last.clientX > right ? last.clientX - right : 0;
    if (!d) return false;
    opts.edge.box.scrollLeft += d * 0.5;
    move(last);
    return true;
  };
  const mv = (e: PointerEvent) => {
    if (e.pointerId !== ev.pointerId) return;
    last = e;
    move(e);
    if (opts.edge && timer == null && nudge()) timer = window.setInterval(() => { if (!nudge()) { clearInterval(timer); timer = undefined; } }, 30);
  };
  const end = (e: PointerEvent) => {
    if (e.pointerId !== ev.pointerId) return;
    clearInterval(timer);
    host.removeEventListener("pointermove", mv);
    host.removeEventListener("pointerup", end);
    host.removeEventListener("pointercancel", end);
    try { if (host.hasPointerCapture(ev.pointerId)) host.releasePointerCapture(ev.pointerId); } catch { /* gone */ }
    document.body.classList.remove("dragging", "cur-ew", "cur-col", "cur-row", "cur-grab");
    up?.(e);
  };
  host.addEventListener("pointermove", mv);
  host.addEventListener("pointerup", end);
  host.addEventListener("pointercancel", end);
}
