package com.beautifulquran.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where a word's ink stands, row by row — not just the box around it.
 *
 * A line spaced by bounding boxes is not evenly spaced. Arabic words nest: a
 * word closing on a deep bowl (ن, ل, ي) leaves a pocket of paper under its
 * tail that the next word's opening letter sits inside, which is exactly how
 * the print sets it. The bounding box knows nothing about that pocket, so the
 * pair is pushed apart by the length of the tail.
 *
 * Measured on page 7 line 2 — one line — the paper two words may share before
 * their ink meets runs from 0.005 em (هُمۡ ▸ يَحۡزَنُونَ, two flat ends) to
 * 0.345 em (وَٱلَّذِينَ ▸ كَفَرُوا۟, an open ن). Spaced by box, those two joins
 * are set the same and *look* three times apart: one pair reads as running
 * together, the next as a hole in the line. That is the whole of what reads as
 * uneven spacing on our page, and no choice of word space fixes it, because
 * the fault is per join, not per line.
 *
 * So each glyph is rastered once, at a reference size, and its left and right
 * ink edge recorded per scanline. The paper a pair may share is then the
 * closest their two profiles come over the rows they share. Held in em, it is
 * independent of type size and of how far the line's letterforms are narrowed
 * (a horizontal scale scales the nesting with everything else), so the work is
 * done once per glyph for the whole book.
 */
internal class MushafInkProfile(
    /** Row of the first inked scanline, relative to the baseline (negative above). */
    val firstRow: Int,
    /** Per row, the paper between the ink box's left edge and the first stroke, in em. */
    val leftInset: FloatArray,
    /** Per row, the paper between the last stroke and the ink box's right edge, in em. */
    val rightInset: FloatArray,
    /** [leftInset] with each row's stroke allowed to reach into its neighbours. */
    private val leftReach: FloatArray,
    /** [rightInset] with each row's stroke allowed to reach into its neighbours. */
    private val rightReach: FloatArray,
) {
    val rows: Int get() = leftInset.size

    /** How near this word's ink comes to its own left edge at [row], reaching. */
    fun leftReachAt(row: Int): Float = reachAt(leftReach, row)

    /** How near this word's ink comes to its own right edge at [row], reaching. */
    fun rightReachAt(row: Int): Float = reachAt(rightReach, row)

    /**
     * A row outside the word's own height is not blank to a neighbour: the
     * word's nearest stroke is simply that many rows away, and the reach keeps
     * counting past the ends of the array exactly as it does inside it.
     */
    private fun reachAt(reach: FloatArray, row: Int): Float {
        val i = row - firstRow
        return when {
            i < 0 -> reach[0] - i * MUSHAF_PROFILE_ROW_EM
            i >= reach.size -> reach[reach.size - 1] + (i - reach.size + 1) * MUSHAF_PROFILE_ROW_EM
            else -> reach[i]
        }
    }
}

/**
 * A word's insets, with every stroke allowed to reach a row either side of it.
 *
 * Read row by row, two words can be far apart on every scanline they share and
 * still touch. The tail of a لا sweeps left and *stops in mid-air*; the ت that
 * follows nests under it, opening a row or two lower. They share no scanline at
 * the point where they nearly meet, so a per-row reading of the join measures
 * the rows above and below — where both are wide open — and reports a quarter
 * of an em of clearance for two strokes three pixels apart. That is what was
 * still welding words on page 7 after the white itself was levelled.
 *
 * So each inset is spread down the column at one row of paper per row of
 * travel: a stroke one row away counts as one row further off, two rows as two.
 * It is the standard chamfer pass, and at that slope the result is the ordinary
 * diagonal a reader's eye follows between two letterforms — near enough to a
 * true distance for a join, and two passes over an array rather than a search.
 */
private fun mushafInkReach(inset: FloatArray): FloatArray {
    val reach = FloatArray(inset.size) {
        if (inset[it] == MUSHAF_PROFILE_BLANK) MUSHAF_PROFILE_REACH_MAX else inset[it]
    }
    for (i in 1 until reach.size) reach[i] = min(reach[i], reach[i - 1] + MUSHAF_PROFILE_ROW_EM)
    for (i in reach.size - 2 downTo 0) reach[i] = min(reach[i], reach[i + 1] + MUSHAF_PROFILE_ROW_EM)
    return reach
}

/**
 * What one join between two words offers, in em.
 *
 * Three numbers, because a join is judged three ways at once. [paper] is how
 * much white the join actually holds, averaged down the rows the two words
 * share — which is what the eye reads, and what the line is levelled by.
 * [closest] is the narrowest they come anywhere down their height, and [tight]
 * the mean of the narrowest quarter of their rows: the two collision tests.
 *
 * Spacing every join to the same [closest] is not even spacing, and that is
 * worth saying plainly because it is the trap this went through: an alif
 * standing beside a خ runs alongside it for half an em, so setting their one
 * nearest point to the page's word space welds them into a single mark, while
 * a يَ whose tail sweeps under its neighbour touches at a point and reads wide
 * open at the same distance. The join has to be set by the white it carries.
 *
 * Both collision tests are needed, and for opposite faults. Held to [closest]
 * alone, a pair that pinches at one row is thrown apart as if it ran alongside
 * — a hole in the line. Held to [tight] alone, a pair whose quarter reads open
 * because it nests deeply may still cross at its one pinch point: measured on
 * page 7 line 6, four joins came to within 0.06 em that way, which at reading
 * size is three pixels and reads as one word.
 */
internal class MushafInkJoin(
    val closest: Float,
    val tight: Float,
    val paper: Float,
) {
    /**
     * The least this join's two ink boxes may be set apart, in em.
     *
     * Negative where the words nest: a box may be drawn well inside its
     * neighbour's and the ink still stand clear.
     */
    val floorEm: Float
        get() = max(MUSHAF_MIN_WHITE_EM - tight, MUSHAF_HARD_WHITE_EM - closest)

    /**
     * The same floor with the headroom the fit reserves.
     *
     * A line condensed until its joins sit exactly on the floor has nothing
     * left to level with, and every join lands on its minimum — which is the
     * uneven setting the levelling exists to avoid. So the fit is asked for a
     * fifth more than the floor, and the levelling spends the difference.
     */
    val fitFloorEm: Float
        get() = max(
            MUSHAF_MIN_WHITE_EM * MUSHAF_FIT_WHITE_K - tight,
            MUSHAF_HARD_WHITE_EM * MUSHAF_FIT_WHITE_K - closest,
        )
}

/**
 * What [right] and [left] offer each other at the moment their ink boxes meet
 * — where [right] is the word nearer the fore-edge and [left] the one that
 * follows it across the line.
 */
internal fun mushafInkJoin(right: MushafInkProfile?, left: MushafInkProfile?): MushafInkJoin {
    if (right == null || left == null) return MUSHAF_NO_JOIN
    // The white the join carries is read straight off the scanlines the two
    // words both write on: that is the paper the eye sees between them, and a
    // row only one of them reaches says nothing about how wide the join looks.
    val from = max(right.firstRow, left.firstRow)
    val to = min(right.firstRow + right.rows, left.firstRow + left.rows)
    var sum = 0.0
    var rows = 0
    for (row in from until to) {
        val a = right.leftInset[row - right.firstRow]
        val b = left.rightInset[row - left.firstRow]
        if (a == MUSHAF_PROFILE_BLANK || b == MUSHAF_PROFILE_BLANK) continue
        // Capped: a tail that wanders half a word away is not paper this join
        // has to account for, and left uncapped it swamped every other row.
        sum += min(a + b, MUSHAF_JOIN_PAPER_CAP_EM).toDouble()
        rows++
    }
    val paper = if (rows == 0) 0f else (sum / rows).toFloat()
    // Whether they *collide* is a different question and gets a different
    // reading: over every row either of them reaches, and by the reach rather
    // than the scanline, so a stroke that stops short of the next word
    // diagonally counts as the near miss it is.
    val lo = min(right.firstRow, left.firstRow)
    val hi = max(right.firstRow + right.rows, left.firstRow + left.rows)
    if (hi <= lo) return MUSHAF_NO_JOIN
    val shares = FloatArray(hi - lo) { right.leftReachAt(lo + it) + left.rightReachAt(lo + it) }
    java.util.Arrays.sort(shares)
    val quarter = max(1, (shares.size * MUSHAF_JOIN_TIGHT_FRACTION).roundToInt())
    var tight = 0.0
    for (i in 0 until quarter) tight += shares[i].toDouble()
    // A stroke is measured to the raster's own grid, so give the join back one
    // row's worth of paper rather than let a rounding land two words touching.
    return MushafInkJoin(
        closest = (shares[0] - MUSHAF_PROFILE_SLACK_EM).coerceIn(0f, MUSHAF_JOIN_MAX_EM),
        tight = ((tight / quarter).toFloat() - MUSHAF_PROFILE_SLACK_EM)
            .coerceIn(0f, MUSHAF_JOIN_MAX_EM),
        paper = paper,
    )
}

/** Two words with no ink between them tell the line nothing. */
private val MUSHAF_NO_JOIN = MushafInkJoin(0f, 0f, 0f)

/**
 * The white a join is set to when the line has the paper for it, in em.
 *
 * Measured against the narrowest quarter of the join's rows, so it binds on a
 * pair that runs alongside — where the eye reads a channel, not a point — and
 * not on a pair that merely grazes.
 */
internal const val MUSHAF_MIN_WHITE_EM = 0.24f

/** No two words come closer than this anywhere, whatever the rest of the join reads. */
internal const val MUSHAF_HARD_WHITE_EM = 0.20f

/** The headroom the fit reserves over the floor, so levelling has room to work. */
private const val MUSHAF_FIT_WHITE_K = 1.20f

/** Past this level of paper a line is set with wider letters, not wider spaces. */
internal const val MUSHAF_MAX_WHITE_LEVEL_EM = 0.85f

/** The last setting before a line is left standing short and centred. */
internal const val MUSHAF_STRETCH_WHITE_LEVEL_EM = 1.25f

/** The share of a join's rows that decides whether it runs alongside. */
private const val MUSHAF_JOIN_TIGHT_FRACTION = 0.25f

/** Paper further out than this is not what a join is judged on. */
private const val MUSHAF_JOIN_PAPER_CAP_EM = 0.60f

/** No join gives up more than this, whatever a stray raster says. */
private const val MUSHAF_JOIN_MAX_EM = 0.55f

/**
 * The size the profiles are taken at.
 *
 * Small on purpose: one raster row is 1/48 em, twenty times finer than the
 * differences it has to tell apart, and a page's worth of words costs a few
 * milliseconds once.
 */
private const val MUSHAF_PROFILE_REF_PX = 48f

/** One raster row, in em: the reach's slope, and the join's slack. */
private const val MUSHAF_PROFILE_ROW_EM = 1f / MUSHAF_PROFILE_REF_PX

/** One raster row of paper, kept back so a measured join never closes to nothing. */
private const val MUSHAF_PROFILE_SLACK_EM = MUSHAF_PROFILE_ROW_EM

/** Further than any word reaches, so a blank row starts the chamfer at nothing. */
private const val MUSHAF_PROFILE_REACH_MAX = 4f

/** Marks a scanline the word leaves blank, so a join ignores it. */
internal const val MUSHAF_PROFILE_BLANK = Float.MAX_VALUE

/** Ink outside the em box: QCF words overhang their advance on both sides. */
private const val MUSHAF_PROFILE_PAD = 2

/** A stroke is a stroke at this coverage; below it is the antialiaser's fringe. */
private const val MUSHAF_PROFILE_INK = 40

/**
 * Profiles for one book, kept per page face.
 *
 * Keyed by the [Typeface] itself and held weakly: the page faces are cached by
 * [MushafQcfFonts] for as long as the reader may turn back to them, and when
 * one finally goes so does its glyph work.
 */
internal object MushafInkProfiles {

    private val faces = WeakHashMap<Typeface, HashMap<String, MushafInkProfile?>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = MUSHAF_PROFILE_REF_PX
        color = Color.WHITE
    }
    private val bounds = Rect()
    private var scratch: Bitmap? = null
    private var pixels: IntArray = IntArray(0)

    @Synchronized
    fun of(typeface: Typeface?, text: String): MushafInkProfile? {
        if (typeface == null || text.isEmpty()) return null
        val face = faces.getOrPut(typeface) { HashMap() }
        if (face.containsKey(text)) return face[text]
        val profile = measure(typeface, text)
        face[text] = profile
        return profile
    }

    private fun measure(typeface: Typeface, text: String): MushafInkProfile? {
        paint.typeface = typeface
        paint.textScaleX = 1f
        paint.getTextBounds(text, 0, text.length, bounds)
        if (bounds.isEmpty) return null
        val width = bounds.width() + MUSHAF_PROFILE_PAD * 2
        val height = bounds.height() + MUSHAF_PROFILE_PAD * 2
        val bitmap = scratchOf(width, height)
        bitmap.eraseColor(Color.TRANSPARENT)
        Canvas(bitmap).drawText(
            text,
            (MUSHAF_PROFILE_PAD - bounds.left).toFloat(),
            (MUSHAF_PROFILE_PAD - bounds.top).toFloat(),
            paint,
        )
        val stride = bitmap.width
        bitmap.getPixels(pixels, 0, stride, 0, 0, width, height)
        val left = FloatArray(bounds.height())
        val right = FloatArray(bounds.height())
        val edge = (width - MUSHAF_PROFILE_PAD - 1).toFloat()
        for (row in 0 until bounds.height()) {
            val base = (row + MUSHAF_PROFILE_PAD) * stride
            var first = -1
            var last = -1
            for (col in 0 until width) {
                if ((pixels[base + col] ushr 24) >= MUSHAF_PROFILE_INK) {
                    if (first < 0) first = col
                    last = col
                }
            }
            if (first < 0) {
                // A row the word does not reach shares all the paper there is;
                // the join is decided by the rows where both words do write.
                left[row] = MUSHAF_PROFILE_BLANK
                right[row] = MUSHAF_PROFILE_BLANK
            } else {
                left[row] = (first - MUSHAF_PROFILE_PAD) / MUSHAF_PROFILE_REF_PX
                right[row] = (edge - last) / MUSHAF_PROFILE_REF_PX
            }
        }
        return MushafInkProfile(
            firstRow = bounds.top,
            leftInset = left,
            rightInset = right,
            leftReach = mushafInkReach(left),
            rightReach = mushafInkReach(right),
        )
    }

    private fun scratchOf(width: Int, height: Int): Bitmap {
        val held = scratch
        if (held != null && held.width >= width && held.height >= height) return held
        val grown = Bitmap.createBitmap(
            max(width, held?.width ?: 0),
            max(height, held?.height ?: 0),
            Bitmap.Config.ARGB_8888,
        )
        scratch = grown
        pixels = IntArray(grown.width * grown.height)
        return grown
    }
}
