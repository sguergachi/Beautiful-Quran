package com.beautifulquran.ui.theme.paperink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.TextLayoutResult
import com.beautifulquran.domain.TajweedPacing
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The Paper theme's word ink, shared by every reader surface (docs/PAPER_INK.md).
 *
 * A surface supplies only a [Source]: one word laid out alone in the surface's
 * own hand, and where that layout sits. Everything else is here, once:
 * - capture: the word's glyphs rasterized on the UI thread (cheap);
 * - generation: [PaperInkField] on a background thread, packed into two small
 *   textures, cached by layout and sub-pixel phase;
 * - drawing: [drawWord] erases the word's printed pixels from the surface's
 *   layer and draws [Frame] through one AGSL shader.
 *
 * A word whose field is not ready keeps the surface's existing wash, so no
 * word is ever left unhighlighted (AGENTS.md invariant 10).
 */
object PaperInk {
    /** Mask samples per device pixel. */
    const val SCALE = 2

    /** RuntimeShader is Android 13+. Older devices keep the paper-cover wash. */
    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private var shaderSource: String? = null

    /** Called once from QuranApp. */
    fun install(context: Context) {
        if (!supported || shaderSource != null) return
        shaderSource = context.assets.open("paper-ink/ink.agsl").bufferedReader().use { it.readText() }
    }

    /** One word, laid out alone, and the host position of that layout's origin. */
    class Source(
        val layout: TextLayoutResult,
        /** Host-space position of [layout]'s origin; fractional is fine. */
        val originX: Float,
        val originY: Float,
        val rtl: Boolean,
        /** Letters to pace across when the text has none of its own (QCF glyph codes). */
        val letterCount: Int = 0,
    )

    /** The live values one frame of a word needs, all from its InkMotion. */
    class Frame(
        val progress: Float,
        val wet: Float,
        val repeatPass: Float,
        val repeatTint: Float,
        val retained: Float,
        val dryInk: Color,
        val wetInk: Color,
        val repeatColor: Color,
        val unreadAlpha: Float,
        val pooling: Float,
        val reflection: Float,
        val softness: Float,
        val leadFade: Float,
        val flowing: Boolean,
    )

    /** A generated word, ready to draw. */
    class Word internal constructor(
        val arrival: Bitmap,
        val normals: Bitmap,
        val erase: Bitmap,
        /** Host offset of the textures' top-left from the layout origin's integer cell. */
        val padX: Int,
        val padY: Int,
        val letterDuration: Float,
        val unit: Float,
    ) {
        val width: Int get() = normals.width
        val height: Int get() = normals.height
        internal val bytes: Int get() = arrival.byteCount + normals.byteCount + erase.byteCount
        internal var shader: Any? = null
    }

    private data class Key(val input: TextLayoutInput, val phaseX: Int, val phaseY: Int)

    private val cache = object : LruCache<Key, Word>(CACHE_BYTES) {
        override fun sizeOf(key: Key, value: Word): Int = value.bytes
    }
    private val pending = HashSet<Key>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "PaperInkField")
    }

    /** Bumped on the main thread whenever a field lands, so draws re-read. */
    val generation = mutableIntStateOf(0)

    private var captureFrame = 0L
    private var capturesThisFrame = 0

    /**
     * The generated word for [source], or null while it is being made. Reading
     * this subscribes the caller's draw to [generation]. Captures are rationed
     * per frame so warming a verse never stalls one.
     */
    fun word(source: Source): Word? {
        if (!supported || shaderSource == null) return null
        generation.intValue
        val key = key(source)
        cache.get(key)?.let { return it }
        if (key in pending) return null
        val now = SystemClock.uptimeMillis()
        if (now != captureFrame) {
            captureFrame = now
            capturesThisFrame = 0
        }
        if (capturesThisFrame >= CAPTURES_PER_FRAME) return null
        capturesThisFrame++
        val capture = capture(source) ?: return null
        pending += key
        worker.execute {
            val word = runCatching { generate(capture) }.getOrNull()
            main.post {
                pending -= key
                if (word != null) cache.put(key, word)
                generation.intValue++
            }
        }
        return null
    }

    private fun key(source: Source): Key {
        val fx = source.originX - floor(source.originX)
        val fy = source.originY - floor(source.originY)
        return Key(source.layout.layoutInput, (fx * PHASES).roundToInt() % PHASES, (fy * PHASES).roundToInt() % PHASES)
    }

    private class Capture(
        val alpha: FloatArray,
        val width: Int,
        val height: Int,
        val coverage: ByteArray,
        val coverageWidth: Int,
        val coverageHeight: Int,
        val centers: FloatArray,
        val baseline: Float,
        val rtl: Boolean,
        val fontPx: Float,
        val padX: Int,
        val padY: Int,
        val letterCount: Int,
    )

    private fun capture(source: Source): Capture? {
        val layout = source.layout
        val input = layout.layoutInput
        val fontPx = with(input.density) { input.style.fontSize.takeIf { it.isSp }?.toPx() }
            ?: (layout.size.height * 0.75f)
        if (layout.size.width <= 0 || layout.size.height <= 0) return null
        val phaseX = ((source.originX - floor(source.originX)) * PHASES).roundToInt() % PHASES / PHASES.toFloat()
        val phaseY = ((source.originY - floor(source.originY)) * PHASES).roundToInt() % PHASES / PHASES.toFloat()
        // Room for Hafs marks and tails, and Latin serifs, that ink past the box.
        val padX = ceil(fontPx * 0.45f).toInt()
        val padY = ceil(fontPx * 0.35f).toInt()
        val width = layout.size.width + padX * 2 + 1
        val height = layout.size.height + padY * 2 + 1
        fun raster(scale: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(width * scale, height * scale, Bitmap.Config.ALPHA_8)
            val canvas = Canvas(android.graphics.Canvas(bitmap))
            canvas.scale(scale.toFloat(), scale.toFloat())
            canvas.translate(padX + phaseX, padY + phaseY)
            layout.multiParagraph.paint(canvas, color = Color.Black)
            return bitmap
        }
        val coverageBitmap = raster(1)
        val coverage = ByteArray(width * height)
        coverageBitmap.copyPixelsToBuffer(ByteBuffer.wrap(coverage))
        coverageBitmap.recycle()
        val maskBitmap = raster(SCALE)
        val maskBytes = ByteArray(width * height * SCALE * SCALE)
        maskBitmap.copyPixelsToBuffer(ByteBuffer.wrap(maskBytes))
        maskBitmap.recycle()
        // A span may carry a translucent colour; the word's own ink is the mask.
        var peak = 0
        for (b in maskBytes) peak = maxOf(peak, b.toInt() and 0xFF)
        if (peak == 0) return null
        val alpha = FloatArray(maskBytes.size) { (maskBytes[it].toInt() and 0xFF) / peak.toFloat() }
        var coveragePeak = 0
        for (b in coverage) coveragePeak = maxOf(coveragePeak, b.toInt() and 0xFF)
        if (coveragePeak in 1..254) {
            for (i in coverage.indices) {
                coverage[i] = ((coverage[i].toInt() and 0xFF) * 255 / coveragePeak).coerceAtMost(255).toByte()
            }
        }
        val text = input.text.text
        val letters = letterOffsets(text, source.rtl)
        val centers = if (letters.isEmpty()) FloatArray(0) else FloatArray(letters.size) { k ->
            val start = letters[k]
            val end = letters.getOrNull(k + 1) ?: text.length
            val a = layout.getHorizontalPosition(start, usePrimaryDirection = true)
            val b = layout.getHorizontalPosition(end, usePrimaryDirection = true)
            ((a + b) / 2f + padX + phaseX) * SCALE
        }
        return Capture(
            alpha = alpha,
            width = width * SCALE,
            height = height * SCALE,
            coverage = coverage,
            coverageWidth = width,
            coverageHeight = height,
            centers = centers,
            baseline = (layout.firstBaseline + padY + phaseY) * SCALE,
            rtl = source.rtl,
            fontPx = fontPx,
            padX = padX,
            padY = padY,
            letterCount = if (letters.isEmpty()) source.letterCount else letters.size,
        )
    }

    /** The letters the wash paces across, in text order: TajweedPacing's own for Arabic. */
    internal fun letterOffsets(text: String, rtl: Boolean): IntArray =
        if (rtl) TajweedPacing.letterOffsets(text)
        else text.indices.filter { text[it].isLetterOrDigit() }.toIntArray()

    private fun generate(c: Capture): Word? {
        val result = PaperInkField.generate(
            c.alpha, c.width, c.height, SCALE, c.centers, c.baseline, c.rtl, c.fontPx, c.letterCount,
        ) ?: return null
        val n = c.width * c.height
        val fieldPixels = IntArray(n) { i ->
            val r = (result.insideOut[i] * 255f).roundToInt().coerceIn(0, 255)
            val g = (result.flowing[i] * 255f).roundToInt().coerceIn(0, 255)
            val b = (128f + result.distance[i] * 16f).roundToInt().coerceIn(0, 255)
            // Opaque data: premultiplication would corrupt the channels.
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val field = Bitmap.createBitmap(fieldPixels, c.width, c.height, Bitmap.Config.ARGB_8888)
        val w = c.coverageWidth
        val h = c.coverageHeight
        val normalPixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var nx = 0f
            var ny = 0f
            for (dy in 0 until SCALE) for (dx in 0 until SCALE) {
                val j = (y * SCALE + dy) * c.width + x * SCALE + dx
                nx += result.normalX[j]
                ny += result.normalY[j]
            }
            nx /= SCALE * SCALE
            ny /= SCALE * SCALE
            val r = (128f + nx * 127f).roundToInt().coerceIn(0, 255)
            val g = (128f + ny * 127f).roundToInt().coerceIn(0, 255)
            val b = c.coverage[i].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val normals = Bitmap.createBitmap(normalPixels, w, h, Bitmap.Config.ARGB_8888)
        val erase = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        erase.copyPixelsFromBuffer(ByteBuffer.wrap(c.coverage))
        return Word(field, normals, erase, c.padX, c.padY, result.letterDuration, c.fontPx / 160f)
    }

    private val erasePaint = android.graphics.Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val inkPaint = android.graphics.Paint()

    /**
     * Draw [word] for [source]: erase the printed glyph pixels the host already
     * drew ([erase]), then the ink. The host must be inside an offscreen layer
     * when erasing, so only its own ink is removed.
     */
    fun DrawScope.drawWord(word: Word, source: Source, frame: Frame, erase: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val left = floor(source.originX) - word.padX
        val top = floor(source.originY) - word.padY
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (erase) native.drawBitmap(word.erase, left, top, erasePaint)
            val shader = shaderFor(word)
            shader.setFloatUniform("progress", frame.progress)
            shader.setFloatUniform("wet", frame.wet)
            shader.setFloatUniform("repeatPass", frame.repeatPass)
            shader.setFloatUniform("repeatTint", frame.repeatTint)
            shader.setFloatUniform("retained", frame.retained)
            shader.setFloatUniform("dryInk", frame.dryInk.red, frame.dryInk.green, frame.dryInk.blue)
            shader.setFloatUniform("wetInk", frame.wetInk.red, frame.wetInk.green, frame.wetInk.blue)
            shader.setFloatUniform("repeatColor", frame.repeatColor.red, frame.repeatColor.green, frame.repeatColor.blue)
            shader.setFloatUniform("unreadAlpha", frame.unreadAlpha)
            shader.setFloatUniform("weight", frame.pooling * 3.5f * word.unit)
            shader.setFloatUniform("reflectionStrength", frame.reflection)
            shader.setFloatUniform("lightSoftness", frame.softness)
            shader.setFloatUniform("lead", frame.leadFade * 2f)
            shader.setFloatUniform("spreadMethod", if (frame.flowing) 1f else 0f)
            shader.setFloatUniform("origin", left / word.unit, top / word.unit)
            inkPaint.shader = shader
            native.save()
            native.translate(left, top)
            native.drawRect(0f, 0f, word.width.toFloat(), word.height.toFloat(), inkPaint)
            native.restore()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun shaderFor(word: Word): RuntimeShader {
        (word.shader as RuntimeShader?)?.let { return it }
        val shader = RuntimeShader(shaderSource!!)
        shader.setInputBuffer("field", android.graphics.BitmapShader(
            word.arrival, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP,
        ).apply { filterMode = android.graphics.BitmapShader.FILTER_MODE_LINEAR })
        shader.setInputBuffer("normals", android.graphics.BitmapShader(
            word.normals, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP,
        ).apply { filterMode = android.graphics.BitmapShader.FILTER_MODE_LINEAR })
        shader.setFloatUniform("fieldScale", SCALE.toFloat())
        shader.setFloatUniform("unit", word.unit)
        shader.setFloatUniform("letterDuration", word.letterDuration)
        shader.setFloatUniform("aa", 0.9f)
        word.shader = shader
        return shader
    }

    private const val PHASES = 8
    private const val CAPTURES_PER_FRAME = 3
    private const val CACHE_BYTES = 32 * 1024 * 1024
}
