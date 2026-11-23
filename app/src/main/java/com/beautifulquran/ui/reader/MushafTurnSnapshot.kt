package com.beautifulquran.ui.reader

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Picture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.theme.LocalWideGamut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Pictures of the pager's still leaves, so a turn slides pictures instead of
 * setting type.
 *
 * Every Madinah page has its own face, and each word is one large glyph. A
 * leaf's glyphs are about half of the glyph atlas HWUI keeps on the GPU (its
 * size follows the screen's), so two leaves on screen at once do not fit:
 * every frame of a turn evicted one leaf's glyphs to draw the other's and
 * uploaded them again — ~130 ms of `glTexSubImage2D` per swipe on the
 * emulator, spread over every frame of it, a render-thread 95th percentile of
 * 10 ms against the 8.3 ms a 120 Hz frame has. Drawn from its picture, a leaf
 * asks the atlas for nothing.
 *
 * Every Arabic leaf not carrying live ink is pictured: a run of quick flips
 * never rests, and each leaf it reaches has to be a picture by the time it
 * slides in. A wash, a search flash or a selection live on the resting or the
 * voice's leaf, which always draw live. English prose shares small glyphs
 * across leaves and never overran the atlas; its leaves are left alone. Any
 * re-record of a pictured leaf retires its picture until it is taken again.
 *
 * A picture never touches the render thread, which is drawing the turn: the
* leaf's next draw records itself into a [Picture] as well, which is
 * rasterised in software on a background thread, in the window's colour space
 * (Display P3 on a wide window, whose accents are P3), and uploaded from there.
 */
internal class MushafTurnSnapshots {
    /** One recording a frame, so taking pictures never stacks up on one draw. */
    val capture = Mutex()

    private val raster = Dispatchers.Default.limitedParallelism(1)

    /** [picture], [width] × [height] px, as a hardware bitmap; null if the platform declines. */
    suspend fun rasterize(picture: Picture, width: Int, height: Int, wideGamut: Boolean): ImageBitmap? =
        withContext(raster) {
            val space = ColorSpace.get(if (wideGamut) ColorSpace.Named.DISPLAY_P3 else ColorSpace.Named.SRGB)
            val soft = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, space)
            try {
                android.graphics.Canvas(soft).drawPicture(picture)
                // Uploaded here, on the platform's uploader rather than on
                // the render thread the first time it is drawn.
                soft.copy(Bitmap.Config.HARDWARE, false)?.asImageBitmap()
            } finally {
                soft.recycle()
            }
        }
}

private class LeafPicture {
    var bitmap: ImageBitmap? = null

    /** The record generation [bitmap] was taken from. */
    var bitmapGeneration = -1

    /** The generation being rasterised now; -1 when none is. */
    var takingGeneration = -1

    /** Bumped by every live record whose content may differ from the last. */
    var generation = 0
    var lastDrawWasPicture = false

    /** The generation of the last live record; -1 before the first. */
    val recorded = MutableStateFlow(-1)

    /** Bumped to ask the next draw for a recording; read in draw. */
    val request = mutableIntStateOf(0)
    var handled = 0

    /** Bumped to draw a picture that arrived mid-turn; read in draw. */
    val arrived = mutableIntStateOf(0)

    /** Whether the leaf has drawn unpictured — live ink — since it was composed. */
    var wasLive = false
}

@Composable
internal fun rememberMushafTurnSnapshots(): MushafTurnSnapshots = remember { MushafTurnSnapshots() }

/**
 * Draws the leaf from its picture when it is [pictured] and a picture of its
 * current content exists; otherwise draws it live and, once it is [still],
 * takes one.
 *
 * Whether a turn is under way is deliberately not read here. A pictured leaf
 * is a neighbour — off screen at rest — so it can show its picture at rest as
 * well as mid-turn, and a turn starting or ending never redraws it. When it
 * did, that redraw could not be told from a change to the leaf: the start of
 * a turn threw away a picture still being rasterised, and the end of one had
 * to be assumed to change nothing, which a change landing on the same frame
 * would have turned into a stale picture.
 */
@Composable
internal fun Modifier.mushafTurnSnapshot(
    snapshots: MushafTurnSnapshots,
    pictured: Boolean,
    still: () -> Boolean,
    overlay: LeafOverlay,
    bleed: Dp,
): Modifier {
    val layer = rememberGraphicsLayer()
    val picture = remember { LeafPicture() }
    val stillNow = rememberUpdatedState(still)
    val density = LocalDensity.current
    val wideGamut = LocalWideGamut.current
    if (!pictured) SideEffect { picture.wasLive = true }
    LaunchedEffect(picture, pictured, density, wideGamut) {
        picture.bitmap = null
        picture.bitmapGeneration = -1
        if (!pictured) return@LaunchedEffect
        // A leaf that carried live ink may still be finishing a fade it began
        // then (see [quietMs]); one pictured from its first frame has none.
        val settle = picture.wasLive
        picture.recorded.collectLatest { generation ->
            if (generation < 0) return@collectLatest
            if (picture.bitmapGeneration == generation || picture.takingGeneration == generation) {
                return@collectLatest
            }
            if (settle) delay(quietMs())
            snapshotFlow { stillNow.value() }.first { it }
            // The draw this asks for records the leaf and hands the recording
            // to [take]; holding the lock across that frame keeps it the only
            // recording made in it.
            snapshots.capture.withLock {
                withFrameNanos { }
                picture.request.intValue++
                withFrameNanos { }
            }
        }
    }
    val scope = rememberCoroutineScope()
    // Launched by the draw, not by the collector above: the draw's own record
    // emits a new generation, which restarts the collector.
    val take = remember(picture, scope, snapshots, wideGamut) {
        { recording: Picture, at: Int ->
            picture.takingGeneration = at
            scope.launch {
                try {
                    val bitmap = snapshots.rasterize(recording, recording.width, recording.height, wideGamut)
                    if (bitmap != null && at == picture.generation) {
                        picture.bitmap = bitmap
                        picture.bitmapGeneration = at
                        // The leaf is drawing live until now; show it the picture.
                        picture.arrived.intValue++
                    }
                } finally {
                    if (picture.takingGeneration == at) picture.takingGeneration = -1
                }
            }
            Unit
        }
    }
    return this.then(
        remember(snapshots, picture, layer, pictured, overlay, bleed, take) {
            Modifier.onPlaced { overlay.leaf = it }.drawWithContent {
                val bleedX = ceil(SideBleed.toPx()).toInt()
                val bleedY = ceil(bleed.toPx()).toInt()
                val request = picture.request.intValue
                picture.arrived.intValue
                val bitmap = picture.bitmap
                if (pictured && bitmap != null &&
                    picture.bitmapGeneration == picture.generation &&
                    // Asked to draw again with nothing of ours changed:
                    // something on the leaf did. Live from here.
                    !picture.lastDrawWasPicture
                ) {
                    drawImage(bitmap, topLeft = Offset(-bleedX.toFloat(), -bleedY.toFloat()))
                    overlay.drawOver(this)
                    picture.lastDrawWasPicture = true
                    return@drawWithContent
                }
                // Every live record may carry new content: nothing of ours
                // redraws a leaf showing a valid picture.
                picture.generation++
                picture.lastDrawWasPicture = false
                val width = size.width.toInt() + 2 * bleedX
                val height = size.height.toInt() + 2 * bleedY
                layer.record(size = IntSize(width, height)) {
                    translate(bleedX.toFloat(), bleedY.toFloat()) {
                        this@drawWithContent.drawContent()
                    }
                }
                layer.topLeft = IntOffset(-bleedX, -bleedY)
                drawLayer(layer)
                if (!pictured) return@drawWithContent
                if (request != picture.handled && width > 0 && height > 0) {
                    picture.handled = request
                    // The same content again, into a recording the background
                    // can rasterise: a software canvas, so the leaf's own
                    // layers draw their content into it rather than a node.
                    val recording = Picture()
                    MushafPicturePass.recording = true
                    try {
                        draw(this, layoutDirection, Canvas(recording.beginRecording(width, height)), Size(width.toFloat(), height.toFloat())) {
                            translate(bleedX.toFloat(), bleedY.toFloat()) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    } finally {
                        MushafPicturePass.recording = false
                        recording.endRecording()
                    }
                    take(recording, picture.generation)
                }
                picture.recorded.value = picture.generation
            }
        },
    )
}

/**
 * What a leaf's picture leaves out, drawn live over it instead.
 *
 * Its words rasterise in software exactly as the GPU sets them (the largest
 * difference measured is 1/255), but its ornaments do not: the title band's
 * hairline weave is anti-aliased differently, and the basmalah — one glyph
 * the width of the measure — came out in visibly different strokes. Neither
 * troubles the glyph atlas, so each keeps a layer of its own, left out of the
 * picture and drawn over it, exactly as recorded live.
 */
internal class LeafOverlay {
    /** The leaf's coordinates, where its picture is drawn. */
    var leaf: LayoutCoordinates? = null
    val items = mutableListOf<LeafOverlayItem>()

    fun drawOver(scope: DrawScope) {
        val leaf = leaf?.takeIf { it.isAttached } ?: return
        items.forEach { item ->
            val coords = item.coords?.takeIf { it.isAttached } ?: return@forEach
            val at = leaf.localPositionOf(coords, Offset.Zero)
            scope.translate(at.x, at.y) { drawLayer(item.layer) }
        }
    }
}

internal class LeafOverlayItem(val layer: GraphicsLayer) {
    var coords: LayoutCoordinates? = null
}

internal val LocalLeafOverlay = staticCompositionLocalOf<LeafOverlay?> { null }

/** Set while a leaf records its picture; UI thread only. */
internal object MushafPicturePass {
    var recording = false
}

/**
 * Keeps this node out of its leaf's picture: it draws through a layer of its
 * own, which the leaf draws over the picture while it shows one.
 */
@Composable
internal fun Modifier.drawnLiveOverPicture(): Modifier {
    val overlay = LocalLeafOverlay.current ?: return this
    val layer = rememberGraphicsLayer()
    val item = remember(layer) { LeafOverlayItem(layer) }
    DisposableEffect(overlay, item) {
        overlay.items += item
        onDispose { overlay.items -= item }
    }
    return this
        .onPlaced { item.coords = it }
        .drawWithContent {
            if (MushafPicturePass.recording) return@drawWithContent
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
}

/**
 * How long a leaf that carried live ink must go unrecorded before it is
 * pictured: past the longest fade it may have begun on that record. Those
 * fades run on layers of their own, whose frames never record the leaf again,
 * so a picture taken partway through one would keep it half-done.
 */
private fun quietMs(): Long =
    maxOf(InkEngine.tuning.inkFadeMs, InkEngine.tuning.recessMs, MushafLeafFadeMs).toLong() + QuietMarginMs

private const val QuietMarginMs = 120L

/** Room either side of the leaf for a glyph that overhangs its margin. */
private val SideBleed = 8.dp
