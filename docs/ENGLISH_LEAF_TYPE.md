# The English Mushaf leaf: how its type was set

This records ten measured rounds that tuned Timeless Serif for the English
Mushaf leaf, so the numbers can be redone when the face, the page or the
reader changes. Everything here is **Android only** (the web leaf is a scrolling
column, not a fitted page). The lab that produced it reproduces the app's own
leaf fit in headless Chrome at the Pixel's density (2.625); it is calibrated so
the shipped Timeless face solves to the hand measured on the device (17.74 dp),
and the result was then verified against real device pixels.

## The question

Garamond read as easy on the eye; Timeless, set the same way, read scratchy.
The cause is not "Timeless is wrong for screens". Two things were different:

1. **Size on the page.** Timeless sets 18 % wider than Garamond (a lowercase
   advance of 0.519 em against 0.437). The leaf solves its hand so a full leaf
   fits the well, so a wider face gets a *smaller* hand: 17.74 dp against 19.78.
   Its x-height (0.51 em vs 0.405) is much taller, so its lowercase stands 14 %
   bigger than Garamond's while its lines sit only 2.83 x-heights apart
   (Garamond 3.46). Big lowercase, little air: dense.
2. **Colour.** At weight 400 the stem is 3.3 px against Garamond's 2.9, and the
   page measures 25 % darker (0.121 against 0.098 mean ink coverage).

## The ten rounds

| # | Variable | Finding |
|---|----------|---------|
| 1 | Baselines | Garamond / Timeless shipped / the tuning pasted by the owner. The owner's weight 375 + contrast 25 already cut colour 9 %. |
| 2 | Letter-spacing | −0.025 em squeezed Timeless: letter gap 2.2 px vs 2.8 at 0. Ease it to −0.0125 em; the word/letter ratio stays > 5. |
| 3 | Weight | 350 reaches Garamond's page colour at full ink; 365 is the shipped hand. Weight changes stems, not widths. |
| 4 | Contrast axis | Moving Text→Display thins hairlines 1.91 → 1.49 px (contrast 1.88 → 2.47). 25 gives 1.7 px, as Garamond's. |
| 5 | Leading | Leading is paid for in hand: the page re-fits. 1.62 em gives 3.15 x-heights, near Garamond's 3.46. |
| 6 | Capacity | Holding fewer characters per leaf gives a larger hand at a cost in leaves. 1,130 gives a hand 9 % over Garamond's x-height for 4 % more leaves. |
| 7 | Ink | Softer ink lowers contrast (15.6 → 8.5:1) and cuts colour, but it also costs the dignity the owner asked for. Kept at full ink. |
| 8 | Word space | Rivers are lowest at +0.03 em; the difference is inside noise, so the default stays. |
| 9 | Dark paper | Weight eases 30 on dark paper (see DESIGN.md); the leaf's tuning inherits it. |
| 10 | Integration | Four leaves (2:57–61, 2:254–257, 112, 55:1–13): the refined hand sits within 2 % of Garamond's colour on every one, and beats the shipped Timeless on evenness. |

## What shipped

`TypographyTuning.leaf` (Ink Lab → Typography → *Leaf*):

* weight 365, Text↔Display 25, tracking +0.0125 (the leaf adds its own −0.025
  squeeze, so it sets at −0.0125 em);
* leading 1.62 em (`TIMELESS_LEAF_LEADING_EM`);
* capacity 1,130 characters (`leafCapacityChars`), against Garamond's 1,180;
* the classic profile keeps its Garamond values untouched.

## Redoing it

1. Re-run the lab against a device capture of the same leaf and re-calibrate the
   well to the hand the device reports.
2. Fix a candidate; re-fit the page (hand, lines) under the app's fit; compare
   page colour, squint evenness, hairline thickness, pitch / x-height and leaf
   count against Garamond.
3. Bump `EnglishBookCache.FORMAT` whenever the capacity or leading changes.
