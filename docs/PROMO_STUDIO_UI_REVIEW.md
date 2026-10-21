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
| Window layout | Stored wide panes could consume the preview, and a tall timeline could hide playback controls after resizing. | Pane widths fit the current window without replacing preferred widths. Timeline height reserves at least 96 px for the preview; its toolbar is included in the timeline's height. |
| Compact windows | Both side panes could crowd out the central editor. | At widths of 1,000 CSS px or less, one pane opens over the editor with a dismissible backdrop. |
| Toolbars | Playback and editing occupied separate rows, with different button heights and uneven icon alignment. | One toolbar joins playback/timecode, edit/key navigation, camera actions and timeline zoom. Controls share a 32 px height, 4 px within-group gaps and centered SVG icons. Camera actions collapse into a menu below 1,140 px of toolbar width; below 680 px, Tools also contains editing commands. The row stays single-line. Compact headers keep aspect selection in Look → Canvas. |
| Look | Background controls were split between tabs; blur could not reach sharp, and thumbnail labels were internal IDs. | Background, Phone, Canvas and Sound form the main groups. Background separates Image adjustments from Movement; Blur and Brightness share Animate's playhead-aware properties. Blur reaches zero through the renderer. Short background names and selected-state semantics make the picker readable. |
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
input cancellation. Offline gates are the 31 Python regressions, 26 Node tests,
strict TypeScript/production build and Android JVM suite. See the studio README
for the build/test commands.

The unified toolbar was checked at 19 viewport widths from 320 to 1,920 px,
with side panes open and closed (38 combinations). Checks require one row,
equal button heights, aligned control centres and no horizontal clipping.
Interaction checks cover split/undo, previous/next key, loop, camera/Tools
menus, keyboard activation, Fit, typed timecodes and a short viewport. Below
500 px the slider is folded away while Fit and every editing command remain
reachable; the timecode keeps its current time, with total duration in its tooltip.

Look controls were checked at 1440, 1100, 800, 600, 390 and 320 px, including
stacked field labels and all numeric inputs. Real keyboard checks cover zero
blur, a sharp source plate, single-step undo/redo, brightness, animated blur at
the playhead, neighboring-key preservation, and matching values in Animate.
A rendered striped-image regression confirms zero blur retains detail.
