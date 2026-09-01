package com.beautifulquran.share

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import com.beautifulquran.data.ThemeMode
import com.beautifulquran.ui.theme.BeautifulQuranTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Offscreen Compose → [Bitmap] for share images.
 *
 * Hosts a thin card (not full ReaderScreen) under a temporary invisible child
 * of the activity decor so measure/layout and fonts resolve correctly.
 * Full-ink stills only for PR2 — wash-frame probes for video can reuse this
 * attach path later.
 */
object ShareImageRenderer {

    /** One-verse portrait share width in px. A longer gather scales up from this. */
    const val WIDTH_PX = 1080

    /** Logical card width so 28.sp is 84 px at 1×, independent of the phone. */
    internal const val CARD_WIDTH_DP = 360f

    /** Software-canvas budget (~64 MB ARGB). Long gathers scale down to fit. */
    internal const val MAX_BITMAP_PIXELS = 16_777_216

    internal const val MAX_BITMAP_SIDE = 16_384

    private const val BASE_DENSITY = WIDTH_PX / CARD_WIDTH_DP

    private const val LAYOUT_ATTEMPTS = 8

    suspend fun render(
        activity: Activity,
        content: @Composable () -> Unit,
        verseCount: Int = 1,
    ): Bitmap = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val decor = activity.window.decorView as? ViewGroup
            if (decor == null) {
                cont.resumeWithException(IllegalStateException("No decor view"))
                return@suspendCancellableCoroutine
            }

            val scaleState = mutableFloatStateOf(1f)
            val host = FrameLayout(activity).apply {
                // Invisible but still laid out and drawn (GONE would skip both).
                visibility = View.INVISIBLE
            }
            val composeView = ComposeView(activity).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindow,
                )
                setContent {
                    val density = BASE_DENSITY * scaleState.floatValue
                    CompositionLocalProvider(
                        LocalDensity provides Density(density, fontScale = 1f),
                    ) {
                        BeautifulQuranTheme(themeMode = ThemeMode.LIGHT) {
                            content()
                        }
                    }
                }
            }
            host.addView(
                composeView,
                ViewGroup.LayoutParams(WIDTH_PX, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            decor.addView(
                host,
                ViewGroup.LayoutParams(WIDTH_PX, ViewGroup.LayoutParams.WRAP_CONTENT),
            )

            fun cleanup() {
                try {
                    decor.removeView(host)
                } catch (_: Exception) {
                    // Already detached.
                }
            }

            cont.invokeOnCancellation { cleanup() }

            var scaled = false
            fun capture(attempt: Int) {
                if (!cont.isActive) {
                    cleanup()
                    return
                }
                try {
                    val widthPx = shareImageWidthPx(scaleState.floatValue)
                    val wrap = ViewGroup.LayoutParams(
                        widthPx,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    composeView.layoutParams = wrap
                    host.layoutParams = wrap
                    val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    composeView.measure(widthSpec, heightSpec)
                    var height = composeView.measuredHeight
                    if (height <= 0 && attempt < LAYOUT_ATTEMPTS) {
                        composeView.post { capture(attempt + 1) }
                        return
                    }
                    // As tall as the gather — do not crop verses or the
                    // chapter footer to a phone-screen height.
                    height = height.coerceAtLeast(1)
                    if (!scaled) {
                        val next = shareImageScale(verseCount, WIDTH_PX, height)
                        scaled = true
                        if (next > scaleState.floatValue + 0.01f) {
                            scaleState.floatValue = next
                            // Two posts: density change recomposes, then we measure.
                            composeView.post { composeView.post { capture(0) } }
                            return
                        }
                    }
                    composeView.layout(0, 0, widthPx, height)
                    host.measure(
                        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                    )
                    host.layout(0, 0, widthPx, height)

                    val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    composeView.draw(canvas)
                    cleanup()
                    if (cont.isActive) cont.resume(bitmap)
                } catch (e: Exception) {
                    cleanup()
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }

            // Composition is async; a few posts let the first frame settle.
            composeView.post { capture(0) }
        }
    }
}

/** Pixel width of the sheet at [scale]. One verse is [WIDTH_PX]. */
internal fun shareImageWidthPx(scale: Float): Int =
    (ShareImageRenderer.WIDTH_PX * scale).roundToInt().coerceAtLeast(1)

/**
 * Extra pixels per gathered verse. Two ayahs render at 2×, three at 3×,
 * until the bitmap would exceed [maxPixels] or [maxSide] — then the
 * scale that still fits. Never below 1×: a long gather stays wrap-height
 * at 1080 rather than shrinking the type.
 */
internal fun shareImageScale(
    verseCount: Int,
    widthPx: Int,
    heightPx: Int,
    maxPixels: Int = ShareImageRenderer.MAX_BITMAP_PIXELS,
    maxSide: Int = ShareImageRenderer.MAX_BITMAP_SIDE,
): Float {
    val want = verseCount.coerceAtLeast(1).toFloat()
    val w = widthPx.coerceAtLeast(1).toFloat()
    val h = heightPx.coerceAtLeast(1).toFloat()
    val byPixels = sqrt(maxPixels / (w * h))
    val bySide = min(maxSide / w, maxSide / h)
    return min(want, min(byPixels, bySide)).coerceAtLeast(1f)
}
