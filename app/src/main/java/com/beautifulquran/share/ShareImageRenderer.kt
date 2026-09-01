package com.beautifulquran.share

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.beautifulquran.data.ThemeMode
import com.beautifulquran.ui.theme.BeautifulQuranTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offscreen Compose → [Bitmap] for share images.
 *
 * Hosts a thin card (not full ReaderScreen) under a temporary invisible child
 * of the activity decor so measure/layout and fonts resolve correctly.
 * Full-ink stills only for PR2 — wash-frame probes for video can reuse this
 * attach path later.
 */
object ShareImageRenderer {

    /** Portrait share width in px. */
    const val WIDTH_PX = 1080

    /**
     * Soft cap so a long gather cannot produce an enormous PNG.
     * A taller card is still this tall: [ShareImageCard] pins the gold
     * chapter footer in the last rows and clips verses above it.
     */
    const val MAX_HEIGHT_PX = 1920

    private const val LAYOUT_ATTEMPTS = 8

    suspend fun render(
        activity: Activity,
        content: @Composable () -> Unit,
        widthPx: Int = WIDTH_PX,
        maxHeightPx: Int = MAX_HEIGHT_PX,
    ): Bitmap = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val decor = activity.window.decorView as? ViewGroup
            if (decor == null) {
                cont.resumeWithException(IllegalStateException("No decor view"))
                return@suspendCancellableCoroutine
            }

            val host = FrameLayout(activity).apply {
                // Invisible but still laid out and drawn (GONE would skip both).
                visibility = View.INVISIBLE
            }
            val composeView = ComposeView(activity).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindow,
                )
                setContent {
                    BeautifulQuranTheme(themeMode = ThemeMode.LIGHT) {
                        content()
                    }
                }
            }
            host.addView(
                composeView,
                ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            decor.addView(
                host,
                ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT),
            )

            fun cleanup() {
                try {
                    decor.removeView(host)
                } catch (_: Exception) {
                    // Already detached.
                }
            }

            cont.invokeOnCancellation { cleanup() }

            fun capture(attempt: Int) {
                if (!cont.isActive) {
                    cleanup()
                    return
                }
                try {
                    val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    composeView.measure(widthSpec, heightSpec)
                    var height = composeView.measuredHeight
                    if (height <= 0 && attempt < LAYOUT_ATTEMPTS) {
                        composeView.post { capture(attempt + 1) }
                        return
                    }
                    val fit = shareImageFit(height, maxHeightPx)
                    if (fit.pinFooter) {
                        // Exact height, not AT_MOST: wrap-content would still
                        // overflow, and drawing the bitmap would crop the
                        // gold chapter footer off the bottom.
                        composeView.measure(
                            widthSpec,
                            View.MeasureSpec.makeMeasureSpec(
                                fit.heightPx,
                                View.MeasureSpec.EXACTLY,
                            ),
                        )
                    }
                    height = fit.heightPx
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

/** How a measured card fits the share bitmap cap. */
internal data class ShareImageFit(
    val heightPx: Int,
    val pinFooter: Boolean,
)

/**
 * Long gathers keep a 1080×[max] sheet. The card must then pin its footer
 * so the chapter reference stays in frame.
 */
internal fun shareImageFit(measuredHeightPx: Int, maxHeightPx: Int): ShareImageFit {
    val measured = measuredHeightPx.coerceAtLeast(1)
    return if (measured > maxHeightPx) {
        ShareImageFit(heightPx = maxHeightPx, pinFooter = true)
    } else {
        ShareImageFit(heightPx = measured, pinFooter = false)
    }
}
