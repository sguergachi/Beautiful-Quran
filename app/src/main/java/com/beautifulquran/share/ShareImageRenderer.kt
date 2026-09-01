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

    /**
     * Software-canvas budget (~32 MB ARGB). HWUI also refuses bitmaps whose
     * long side exceeds [MAX_BITMAP_SIDE]; going over either crashes the app.
     */
    internal const val MAX_BITMAP_PIXELS = 8_388_608

    /** GLES texture limit on many phones; Pixel can do more, this does not crash. */
    internal const val MAX_BITMAP_SIDE = 8192

    /** Ignore stub wrap-heights so a 1 px first layout cannot 20× the sheet. */
    internal const val MIN_LAYOUT_HEIGHT_PX = 64

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
                visibility = View.INVISIBLE
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            val composeView = ComposeView(activity).apply {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
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
                var bitmap: Bitmap? = null
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
                    if (height < MIN_LAYOUT_HEIGHT_PX && attempt < LAYOUT_ATTEMPTS) {
                        composeView.post { capture(attempt + 1) }
                        return
                    }
                    height = height.coerceAtLeast(1)
                    if (!scaled) {
                        val next = shareImageScale(verseCount, WIDTH_PX, height)
                        scaled = true
                        if (next > scaleState.floatValue + 0.01f) {
                            scaleState.floatValue = next
                            composeView.post { composeView.post { capture(0) } }
                            return
                        }
                    }
                    val fit = shareImageFit(widthPx, height)
                    composeView.layout(0, 0, widthPx, height)
                    host.measure(
                        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                    )
                    host.layout(0, 0, widthPx, height)

                    bitmap = Bitmap.createBitmap(
                        fit.widthPx,
                        fit.heightPx,
                        Bitmap.Config.ARGB_8888,
                    )
                    val canvas = Canvas(bitmap)
                    if (fit.widthPx != widthPx || fit.heightPx != height) {
                        canvas.scale(
                            fit.widthPx / widthPx.toFloat(),
                            fit.heightPx / height.toFloat(),
                        )
                    }
                    composeView.draw(canvas)
                    cleanup()
                    if (cont.isActive) cont.resume(bitmap)
                } catch (t: Throwable) {
                    bitmap?.recycle()
                    if (scaleState.floatValue > 1.01f) {
                        scaleState.floatValue = 1f
                        scaled = true
                        composeView.post { capture(0) }
                        return
                    }
                    cleanup()
                    if (cont.isActive) cont.resumeWithException(t)
                }
            }

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
 * scale that still fits. Never below 1×. A stub wrap-height stays 1×
 * so a 1 px first layout cannot explode the sheet.
 */
internal fun shareImageScale(
    verseCount: Int,
    widthPx: Int,
    heightPx: Int,
    maxPixels: Int = ShareImageRenderer.MAX_BITMAP_PIXELS,
    maxSide: Int = ShareImageRenderer.MAX_BITMAP_SIDE,
): Float {
    if (heightPx < ShareImageRenderer.MIN_LAYOUT_HEIGHT_PX) return 1f
    val want = verseCount.coerceAtLeast(1).toFloat()
    val w = widthPx.coerceAtLeast(1).toFloat()
    val h = heightPx.coerceAtLeast(1).toFloat()
    val byPixels = sqrt(maxPixels / (w * h))
    val bySide = min(maxSide / w, maxSide / h)
    return min(want, min(byPixels, bySide)).coerceAtLeast(1f)
}

/**
 * Final bitmap size. Always fits [maxPixels] and [maxSide] so Canvas /
 * HWUI cannot abort the process. May downscale a too-tall 1× wrap.
 */
internal fun shareImageFit(
    widthPx: Int,
    heightPx: Int,
    maxPixels: Int = ShareImageRenderer.MAX_BITMAP_PIXELS,
    maxSide: Int = ShareImageRenderer.MAX_BITMAP_SIDE,
): ShareImageSize {
    val w = widthPx.coerceAtLeast(1)
    val h = heightPx.coerceAtLeast(1)
    val scale = minOf(
        1f,
        maxSide / w.toFloat(),
        maxSide / h.toFloat(),
        sqrt(maxPixels.toFloat() / (w.toFloat() * h.toFloat())),
    )
    return ShareImageSize(
        widthPx = (w * scale).toInt().coerceAtLeast(1),
        heightPx = (h * scale).toInt().coerceAtLeast(1),
    )
}

internal data class ShareImageSize(val widthPx: Int, val heightPx: Int)
