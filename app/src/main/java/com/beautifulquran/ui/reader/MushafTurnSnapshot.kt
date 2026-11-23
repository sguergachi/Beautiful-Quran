package com.beautifulquran.ui.reader

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareBufferRenderer
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.theme.LocalWideGamut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.math.ceil

/**
 * Pictures of the pager's still leaves, taken while nothing moves, so a turn
 * slides pictures instead of setting type.
 *
 * Every Madinah page has its own face, and each word is one large glyph. A
 * leaf's glyphs are about half of the glyph atlas HWUI keeps on the GPU (its
 * size follows the screen's), so two leaves on screen at once do not fit:
 * every frame of a turn evicted one leaf's glyphs to draw the other's and
 * uploaded them again — ~130 ms of `glTexSubImage2D` per swipe on the
 * emulator, spread over every frame of it, a render-thread 95th percentile of
 * 10 ms against the 8.3 ms a 120 Hz frame has. Drawn from its picture, the
 * incoming leaf asks the atlas for nothing, and the settled leaf, which goes
 * on setting live type, has the atlas to itself.
 *
 * Only an Arabic leaf one turn from the resting one, carrying static ink, is
 * ever pictured, so a wash, a search flash or a selection — all of which live
 * on the resting or the voice's leaf — always draw live. English prose shares
 * small glyphs across leaves and never overran the atlas; its leaves fade in
 * on layers of their own as the book is measured, and are left alone. Any re-record of a
 * pictured leaf, at rest or mid-turn, retires its picture until it has been
 * still again for [quietMs].
 *
 * A picture is rendered the way the window is — Display P3 on a wide window,
 * whose accents are P3, sRGB otherwise — and asynchronously on the render
 * thread: the UI thread only hands over the display list.
 */
internal class MushafTurnSnapshots {
    /** One picture at a time, each on a frame of its own. */
    val capture = Mutex()

    /**
     * Bumped after a picture is taken. Taking it ran the pictured leaf's glyphs
     * through the atlas; the settled leaf reads this while drawing, so it is
     * recorded and drawn once more at rest and its own glyphs are the freshest
     * in the atlas when the next turn begins.
     */
    val warmEpoch = mutableIntStateOf(0)

    private val root = RenderNode("MushafTurnPicture")
    private var renderer: HardwareRenderer? = null

    /**
     * Renders [layer], [width] × [height] px, into a hardware bitmap in the
     * window's colour space; null where the platform cannot, which leaves the
     * leaf drawing live. Only API 34's buffer renderer takes a colour space,
     * so an older wide-gamut window is never pictured: a P3 accent rendered
     * into sRGB would turn the page clipped.
     */
    suspend fun take(
        layer: GraphicsLayer,
        width: Int,
        height: Int,
        density: Density,
        wideGamut: Boolean,
    ): ImageBitmap? {
        val wide = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        if (wideGamut && !wide) return null
        return withContext(NonCancellable) {
            try {
                root.setPosition(0, 0, width, height)
                val canvas = root.beginRecording(width, height)
                try {
                    canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.BlendMode.CLEAR)
                    CanvasDrawScope().draw(
                        density,
                        LayoutDirection.Ltr,
                        Canvas(canvas),
                        Size(width.toFloat(), height.toFloat()),
                    ) { drawLayer(layer) }
                } finally {
                    root.endRecording()
                }
                if (wide) {
                    renderToBuffer(width, height, ColorSpace.get(
                        if (wideGamut) ColorSpace.Named.DISPLAY_P3 else ColorSpace.Named.SRGB,
                    ))
                } else {
                    renderThroughReader(width, height)
                }
            } finally {
                // The leaf's layer must not stay parented here between pictures.
                root.discardDisplayList()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun renderToBuffer(width: Int, height: Int, colorSpace: ColorSpace): ImageBitmap? {
        val buffer = HardwareBuffer.create(
            width,
            height,
            HardwareBuffer.RGBA_8888,
            1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val bufferRenderer = HardwareBufferRenderer(buffer)
        try {
            bufferRenderer.setContentRoot(root)
            val drawn = suspendCancellableCoroutine { done ->
                bufferRenderer.obtainRenderRequest()
                    .setColorSpace(colorSpace)
                    .draw(Dispatchers.Default.asExecutor()) { result ->
                        // Waited for here, off the UI thread: the bitmap is
                        // sampled by HWUI, which knows nothing of this fence.
                        val fence = result.fence
                        val ready = try {
                            result.status == HardwareBufferRenderer.RenderResult.SUCCESS &&
                                (!fence.isValid || fence.await(FenceTimeout))
                        } finally {
                            fence.close()
                        }
                        done.resume(ready)
                    }
            }
            if (!drawn) return null
            return Bitmap.wrapHardwareBuffer(buffer, colorSpace)?.asImageBitmap()
        } finally {
            bufferRenderer.close()
            buffer.close()
        }
    }

    /** API 30–33, sRGB windows only: HWUI renders an ImageReader surface in sRGB. */
    private suspend fun renderThroughReader(width: Int, height: Int): ImageBitmap? {
        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val renderer = renderer ?: HardwareRenderer().also {
            // Transparent: the picture is laid over the same paper the leaf is.
            it.isOpaque = false
            it.setContentRoot(root)
            renderer = it
        }
        try {
            renderer.setSurface(reader.surface)
            val image = suspendCancellableCoroutine<android.media.Image?> { done ->
                reader.setOnImageAvailableListener(
                    { ready -> done.resume(ready.acquireLatestImage()) },
                    Handler(Looper.getMainLooper()),
                )
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            } ?: return null
            return image.use { frame ->
                val buffer = frame.hardwareBuffer ?: return null
                buffer.use { hardware ->
                    Bitmap.wrapHardwareBuffer(hardware, ColorSpace.get(ColorSpace.Named.SRGB))?.asImageBitmap()
                }
            }
        } finally {
            renderer.setSurface(null)
            reader.close()
        }
    }

    fun release() {
        renderer?.destroy()
        renderer = null
        root.discardDisplayList()
    }
}

private class LeafPicture {
    var bitmap: ImageBitmap? = null

    /** The record generation [bitmap] was taken from. */
    var bitmapGeneration = -1

    /** Bumped by every live record whose content may differ from the last. */
    var generation = 0
    var lastDrawWasPicture = false

    /** The generation of the last live record; -1 before the first. */
    val recorded = MutableStateFlow(-1)
}

/** Releases [snapshots]' renderer when the pager leaves composition. */
@Composable
internal fun rememberMushafTurnSnapshots(): MushafTurnSnapshots {
    val snapshots = remember { MushafTurnSnapshots() }
    DisposableEffect(snapshots) { onDispose { snapshots.release() } }
    return snapshots
}

/**
 * Draws the leaf from its picture while [turning], when it is [pictured] and a
 * picture of its current content exists; otherwise draws it live and, once it
 * has been still, takes one. [turning] is read in draw: it must only change
 * when a turn starts or ends.
 */
@Composable
internal fun Modifier.mushafTurnSnapshot(
    snapshots: MushafTurnSnapshots,
    pictured: Boolean,
    turning: () -> Boolean,
    bleed: Dp,
): Modifier {
    val layer = rememberGraphicsLayer()
    val picture = remember { LeafPicture() }
    val turningNow = rememberUpdatedState(turning)
    val density = LocalDensity.current
    val wideGamut = LocalWideGamut.current
    LaunchedEffect(picture, pictured, density, wideGamut) {
        picture.bitmap = null
        picture.bitmapGeneration = -1
        if (!pictured) return@LaunchedEffect
        picture.recorded.collectLatest { generation ->
            if (generation < 0) return@collectLatest
            if (picture.bitmap != null && picture.bitmapGeneration == generation) return@collectLatest
            delay(quietMs())
            snapshotFlow { turningNow.value() }.first { !it }
            snapshots.capture.withLock {
                withFrameNanos { }
                if (generation != picture.generation || turningNow.value()) return@withLock
                val size = layer.size
                if (size.width <= 0 || size.height <= 0) return@withLock
                val taken = snapshots.take(layer, size.width, size.height, density, wideGamut)
                if (taken != null && generation == picture.generation) {
                    picture.bitmap = taken
                    picture.bitmapGeneration = generation
                    snapshots.warmEpoch.intValue++
                }
            }
        }
    }
    return this.then(
        remember(snapshots, picture, layer, pictured, bleed) {
            Modifier.drawWithContent {
                val bleedX = ceil(SideBleed.toPx()).toInt()
                val bleedY = ceil(bleed.toPx()).toInt()
                val moving = pictured && turningNow.value()
                val bitmap = picture.bitmap
                if (moving && bitmap != null &&
                    picture.bitmapGeneration == picture.generation &&
                    // Asked to draw again mid-turn with nothing of ours
                    // changed: something on the leaf did. Live from here.
                    !picture.lastDrawWasPicture
                ) {
                    drawImage(bitmap, topLeft = Offset(-bleedX.toFloat(), -bleedY.toFloat()))
                    picture.lastDrawWasPicture = true
                    return@drawWithContent
                }
                if (!pictured) snapshots.warmEpoch.intValue
                // The turn ending is the one live record that changes nothing.
                val unchanged = picture.lastDrawWasPicture && !moving
                if (!unchanged) picture.generation++
                picture.lastDrawWasPicture = false
                layer.record(
                    size = IntSize(size.width.toInt() + 2 * bleedX, size.height.toInt() + 2 * bleedY),
                ) {
                    translate(bleedX.toFloat(), bleedY.toFloat()) {
                        this@drawWithContent.drawContent()
                    }
                }
                layer.topLeft = IntOffset(-bleedX, -bleedY)
                drawLayer(layer)
                if (pictured) picture.recorded.value = picture.generation
            }
        },
    )
}

/**
 * How long a leaf must go unrecorded before it is pictured: past the longest
 * fade it may have begun on that record. Those fades run on layers of their
 * own, whose frames never record the leaf again, so a picture taken partway
 * through one would keep it half-done for the next turn.
 */
private fun quietMs(): Long =
    maxOf(InkEngine.tuning.inkFadeMs, InkEngine.tuning.recessMs, MushafLeafFadeMs).toLong() + QuietMarginMs

private const val QuietMarginMs = 120L

/** Longer than any render; a picture not ready by then is not used. */
private val FenceTimeout = Duration.ofSeconds(2)

/** Room either side of the leaf for a glyph that overhangs its margin. */
private val SideBleed = 8.dp
