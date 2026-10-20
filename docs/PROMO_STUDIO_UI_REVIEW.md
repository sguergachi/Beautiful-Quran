# Promo studio UI review

The October 20, 2026 review covers the browser editor in
`tools/promo_studio/editor-app`, including Animate, Recording, Look, the video
list, preview, playback toolbar, timeline, Emulators, Jobs, Shortcuts and
context menus.

## Findings and fixes

| Surface | Finding | Result |
|---|---|---|
| Animate | The slider's intrinsic width expanded the grid's `1fr` track beyond the inspector. `overflow-x-hidden` concealed the numeric field. | The track uses `minmax(0,1fr)`; the range shrinks while the numeric field keeps its width. |
| Inspector fields | Fixed label columns and segmented choices competed for narrow space. Edit inputs also imposed intrinsic widths. | Inputs shrink within their tracks; choices wrap; labels stack in inspectors no wider than 310 px. |
| Recording | Record/select and split/whole-take rows could overflow; long spec paths did not wrap. | Actions wrap and paths remain readable. |
| Window layout | Stored wide panes could consume the preview, and a tall timeline could hide playback controls after resizing. | Pane widths fit the current window without replacing preferred widths. Timeline height reserves preview and measured playback-toolbar space. |
| Compact windows | Both side panes could crowd out the central editor. | At widths of 1,000 CSS px or less, one pane opens over the editor with a dismissible backdrop. |
| Toolbars | Header, playback and timeline controls extended outside narrow panes. | Controls wrap; the timeline zoom group stays together. Compact headers keep aspect selection in Look → Frame. |
| Video list | Footer text overflowed at minimum width; titles lacked a full-text tooltip; actions required hover. | The batch action has the shared export icon and wrapping text; titles have tooltips and actions remain visible. |
| Drawers | Fixed drawer widths, shortcut columns and long progress messages could exceed the window. | Drawers fit the viewport, shortcut columns stack, and job/status text wraps. |
| Context menus | Large menus could start above the window; scrolling dismissed them. | Menus stay inside the viewport, scroll vertically, and close on window resize. |
| Empty preview | The record prompt could extend outside a narrow preview. | Prompt and action wrap inside the preview. |
| Messages | Long errors could overflow or stack above the window. | Messages wrap, scroll within bounded space, and can be dismissed. |

The native video preview, directional ink wash, compositor geometry, export
settings and video specs are unchanged by these layout fixes.

## Review matrix

Chromium checks measured control bounds against their enclosing pane/window,
with screenshots used to inspect the resulting layout. Vertical scrolling in
inspectors/drawers and horizontal scrolling inside the timeline remain
intentional. Truncated video titles expose their complete text on hover.

| Viewport (CSS px) | Inspector width preference |
|---|---:|
| 1920 × 1080 | 340 |
| 1440 × 900 | 280 (minimum) |
| 1280 × 800 | 340 |
| 1100 × 700 | 560, with a 460 px video-list preference |
| 1024 × 768 | 340 |
| 1000 × 700 | 340 |
| 800 × 600 | 340 |
| 640 × 480 | 340 |
| 390 × 844 | 340 |
| 360 × 640 | 340 |

Each size was checked across all three inspector tabs, the workspace controls,
and all three drawers: 70 layout combinations. Smaller CSS viewports also cover
the reflow required when browser zoom reduces the usable window width.

Additional states cover single/multiple selected keys, scrolling to the final
Look field, menus opened at the bottom-right corner, an absent take, long
queued-job messages and long error messages. These include 360 × 480. Short
viewport menus are checked for scrolling without dismissal. Review is scoped to
Chromium layout and interaction; it is not a cross-browser or assistive-technology
certification.

The existing 25 real-input browser checks cover ruler/playhead dragging, edge
scrolling, key movement and selection, easing, split/trim/delete, pane and label
resizing, typed timecodes, undo, blank videos, duplicate freshness and numeric
input cancellation. Offline gates are the 16 Python regressions, 10 Node tests,
strict TypeScript/production build and Android JVM suite. See the studio README
for the build/test commands.
