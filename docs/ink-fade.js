/* Beautiful Quran — web port of ui/theme/Fade.kt ink bloom.
 *
 * The reader highlight is a smootherstep wash (letterFadeIn) with a
 * ~0.5 word-width feather (visible travelling front, ~1–2 letters). For the
 * marketing site (accelerated, not audio-timed) we animate that as per-word
 * opacity on the same curve:
 *
 *   alpha(p) = resting + (1 − resting) × inkSmootherstep(p)
 *
 * Words are staggered in reveal.js (~200 ms between, 300 ms bloom).
 *
 * Why opacity, not mask-image / background-clip:text:
 * Android Chrome + Firefox both failed the earlier mask/clip ports and fell
 * back to a whole-paragraph fade. Element opacity is universally reliable,
 * matches the Fade.kt softerstep toe/shoulder, and needs no Canvas.
 */
(function (root) {
  'use strict';

  /** Matches Fade.kt letterFadeIn default restingAlpha / DESIGN upcoming ink. */
  var DEFAULT_RESTING_ALPHA = 0.35;

  /**
   * smootherstep (6t⁵−15t⁴+10t³): zero first and second derivative at both ends.
   * Port of Fade.kt inkSmootherstep.
   */
  function inkSmootherstep(t) {
    var c = t < 0 ? 0 : t > 1 ? 1 : t;
    return c * c * c * (c * (c * 6 - 15) + 10);
  }

  /**
   * Ink strength at wash progress p ∈ [0,1].
   * Matches the letterFadeIn whole-word breath once the wide feather is
   * accounted for (most of a word's dwell is mid-bloom).
   */
  function inkAlpha(progress, restingAlpha) {
    var rest = restingAlpha == null ? DEFAULT_RESTING_ALPHA : restingAlpha;
    var p = progress < 0 ? 0 : progress > 1 ? 1 : progress;
    return rest + (1 - rest) * inkSmootherstep(p);
  }

  function applyWash(el, progress, rtl, restingAlpha) {
    // rtl is accepted for API parity with letterFadeIn; the site breath is
    // uniform across the word (directional lead comes from word stagger).
    void rtl;
    el.style.opacity = String(inkAlpha(progress, restingAlpha));
  }

  function clearWash(el) {
    el.style.opacity = '';
  }

  /**
   * Animate ink from upcoming → full on the Fade.kt smootherstep clock.
   * Progress advances linearly with time (like audio position/duration);
   * the curve lives in inkAlpha, not in the clock.
   *
   * @param {HTMLElement} el
   * @param {{ duration?: number, rtl?: boolean, restingAlpha?: number, onDone?: function }} opts
   */
  function animateWash(el, opts) {
    opts = opts || {};
    var duration = opts.duration != null ? opts.duration : 280;
    var rtl = !!opts.rtl;
    var resting =
      opts.restingAlpha != null ? opts.restingAlpha : DEFAULT_RESTING_ALPHA;
    var start = null;
    var raf = 0;

    function frame(now) {
      if (start == null) start = now;
      var t = Math.min(1, (now - start) / duration);
      applyWash(el, t, rtl, resting);
      if (t < 1) {
        raf = root.requestAnimationFrame(frame);
      } else {
        // Leave opacity at 1 via the .inked class; drop the inline so theme
        // and hover styles keep working.
        clearWash(el);
        if (opts.onDone) opts.onDone();
      }
    }

    applyWash(el, 0, rtl, resting);
    raf = root.requestAnimationFrame(frame);
    return function cancel() {
      if (raf) root.cancelAnimationFrame(raf);
    };
  }

  root.BeautifulQuranInk = {
    inkSmootherstep: inkSmootherstep,
    inkAlpha: inkAlpha,
    applyWash: applyWash,
    clearWash: clearWash,
    animateWash: animateWash,
    DEFAULT_RESTING_ALPHA: DEFAULT_RESTING_ALPHA,
  };
})(typeof window !== 'undefined' ? window : this);
