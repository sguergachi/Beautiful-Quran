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
 *
 * A gather is drawn **one strip at a time** (each verse, then the gold
 * footer) and the strips are stitched. Rasterising the whole sheet as
 * one wrap aborted the process — HWUI will not draw a view that tall.
 */
object ShareImageRenderer {

    /** Portrait share width in px. */
    const val WIDTH_PX = 1080

    private const val LAYOUT_ATTEMPTS = 8

    /**
     * Draw [segmentCount] strips via [content] `(index) ->` and stitch them
     * top to bottom. Each strip is 1080 × wrap; the GPU never sees the
     * combined height.
     */
    suspend fun renderSegments(
        activity: Activity,
        segmentCount: Int,
        content: @Composable (index: Int) -> Unit,
    ): Bitmap {
        require(segmentCount > 0)
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val decor = activity.window.decorView as? ViewGroup
                if (decor == null) {
                    cont.resumeWithException(IllegalStateException("No decor view"))
                    return@suspendCancellableCoroutine
                }

                val parts = ArrayList<Bitmap>(segmentCount)
                val host = FrameLayout(activity).apply {
                    visibility = View.INVISIBLE
                }
                val composeView = ComposeView(activity).apply {
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnDetachedFromWindow,
                    )
                }
                host.addView(
                    composeView,
                    ViewGroup.LayoutParams(WIDTH_PX, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                decor.addView(
                    host,
                    ViewGroup.LayoutParams(WIDTH_PX, ViewGroup.LayoutParams.WRAP_CONTENT),
                )

                fun recycleParts() {
                    parts.forEach { it.recycle() }
                    parts.clear()
                }

                fun cleanup() {
                    recycleParts()
                    try {
                        decor.removeView(host)
                    } catch (_: Exception) {
                        // Already detached.
                    }
                }

                cont.invokeOnCancellation { cleanup() }

                lateinit var present: (Int) -> Unit

                fun capture(index: Int, attempt: Int) {
                    if (!cont.isActive) {
                        cleanup()
                        return
                    }
                    try {
                        val widthSpec = View.MeasureSpec.makeMeasureSpec(
                            WIDTH_PX,
                            View.MeasureSpec.EXACTLY,
                        )
                        val heightSpec = View.MeasureSpec.makeMeasureSpec(
                            0,
                            View.MeasureSpec.UNSPECIFIED,
                        )
                        composeView.measure(widthSpec, heightSpec)
                        var height = composeView.measuredHeight
                        if (height <= 0 && attempt < LAYOUT_ATTEMPTS) {
                            composeView.post { capture(index, attempt + 1) }
                            return
                        }
                        height = height.coerceAtLeast(1)
                        composeView.layout(0, 0, WIDTH_PX, height)
                        host.measure(
                            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                        )
                        host.layout(0, 0, WIDTH_PX, height)

                        val bitmap = Bitmap.createBitmap(
                            WIDTH_PX,
                            height,
                            Bitmap.Config.ARGB_8888,
                        )
                        composeView.draw(Canvas(bitmap))
                        parts += bitmap

                        val next = index + 1
                        if (next < segmentCount) {
                            present(next)
                            return
                        }

                        val stitched = stitchBitmaps(parts)
                        recycleParts()
                        try {
                            decor.removeView(host)
                        } catch (_: Exception) {
                            // Already detached.
                        }
                        if (cont.isActive) cont.resume(stitched)
                    } catch (t: Throwable) {
                        cleanup()
                        if (cont.isActive) cont.resumeWithException(t)
                    }
                }

                present = { index ->
                    composeView.setContent {
                        BeautifulQuranTheme(themeMode = ThemeMode.LIGHT) {
                            content(index)
                        }
                    }
                    composeView.post { composeView.post { capture(index, 0) } }
                }

                present(0)
            }
        }
    }

    suspend fun render(
        activity: Activity,
        content: @Composable () -> Unit,
        widthPx: Int = WIDTH_PX,
    ): Bitmap = renderSegments(activity, 1) { content() }
}

/** Combined sheet height from per-strip wrap heights. */
internal fun shareImageStitchHeight(heights: IntArray): Int {
    var h = 0
    for (x in heights) h += x.coerceAtLeast(0)
    return h
}

/** Paint [parts] top to bottom onto one bitmap. Does not recycle [parts]. */
internal fun stitchBitmaps(parts: List<Bitmap>): Bitmap {
    require(parts.isNotEmpty())
    val width = parts.maxOf { it.width.coerceAtLeast(1) }
    val height = shareImageStitchHeight(
        IntArray(parts.size) { parts[it].height },
    ).coerceAtLeast(1)
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    var y = 0
    for (part in parts) {
        canvas.drawBitmap(part, 0f, y.toFloat(), null)
        y += part.height
    }
    return out
}
