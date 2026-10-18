package com.beautifulquran.ui.reader

import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.beautifulquran.QuranApp
import com.beautifulquran.R
import com.beautifulquran.data.ThemeMode
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.domain.TajweedPacing
import com.beautifulquran.domain.HighlightClock
import com.beautifulquran.domain.EnglishWordAlignment
import com.beautifulquran.ui.theme.BeautifulQuranTheme
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.quietClickable
import androidx.media3.common.Player
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Native, three-verse Paper ink-fill experiment; intentionally separate from the reader. */
class PaperInkStudyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_BeautifulQuran)
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        if (Build.VERSION.SDK_INT < 33) { finish(); return }
        setContent {
            BeautifulQuranTheme(themeMode = ThemeMode.LIGHT) {
                val app = application as QuranApp
                val study by produceState<PaperInkStudy?>(null) {
                    value = withContext(Dispatchers.IO) { loadStudy(app) }
                }
                study?.let { PaperInkStudyPage(app, it) }
            }
        }
    }

    override fun onStop() {
        (application as QuranApp).player.apply { pause(); setRepeatMode(Player.REPEAT_MODE_OFF) }
        super.onStop()
    }
}

@RequiresApi(33)
private class PaperInkWord(
    val width: Float,
    val height: Float,
    val baseline: Float,
    val motion: PaperInkClock,
    val ayah: Int,
    val pacing: TajweedPacing.Curve?,
    val shader: RuntimeShader,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = this@PaperInkWord.shader }
}

@RequiresApi(33)
private class PaperInkLine(val words: List<PaperInkWord>, gap: Float) {
    val width = words.sumOf { it.width.toDouble() }.toFloat() + (words.size - 1) * gap
}

@RequiresApi(33)
private class PaperInkStudy(val reciter: Reciter, val words: List<PaperInkWord>, val englishWords: List<PaperInkWord>) {
    val clock = HighlightClock()
    val allWords = words + englishWords
    val maxWidth = words.groupBy { it.ayah }.values.maxOf { line ->
        line.sumOf { it.width.toDouble() }.toFloat() + (line.size - 1) * 24f
    }
    val arabicLines = words.groupBy { it.ayah }.values.map { listOf(PaperInkLine(it, 24f)) }
    val englishLines = englishWords.groupBy { it.ayah }.values.map { verse ->
        val lines = mutableListOf<List<PaperInkWord>>()
        var line = mutableListOf<PaperInkWord>()
        var width = 0f
        for (word in verse) {
            if (line.isNotEmpty() && width + word.width > maxWidth) {
                lines.add(line)
                line = mutableListOf()
                width = 0f
            }
            line.add(word)
            width += word.width + 8f
        }
        lines.add(line)
        lines.map { PaperInkLine(it, 8f) }
    }
    val sequence = PaperInkSequence()
    var lastMediaMs = 0L
}

@RequiresApi(33)
private suspend fun loadStudy(app: QuranApp): PaperInkStudy {
    val reciter = app.repository.reciters().first { it.id == 14 }
    val verses = app.repository.surahContent(1).ayahs.take(3)
    val words = verses.flatMap { ayah -> ayah.words.map { ayah.number to it } }
    val timings = app.repository.defaultTimings(14, 1)
    val manifest = JSONArray(app.assets.open("paper-ink-study/words.json").bufferedReader().use { it.readText() })
    check(manifest.length() == words.size)
    val source = app.assets.open("paper-ink-study/ink.agsl").bufferedReader().use { it.readText() }
    val t = InkEngine.tuning
    fun shader(key: String): RuntimeShader {
        val shader = RuntimeShader(source)
        for ((name, file) in listOf("field" to "field", "normals" to "normal")) {
            val bitmap = app.assets.open("paper-ink-study/word-$key-$file.png").use {
                requireNotNull(BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false }))
            }
            val input = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            input.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            shader.setInputBuffer(name, input)
        }
        return shader
    }
    val arabicWords = words.mapIndexed { i, (ayah, word) ->
        val spec = manifest.getJSONObject(i)
        check(spec.getInt("ayah") == ayah && spec.getString("arabic") == word.arabic &&
            spec.getInt("position") == word.position)
        val segments = timings.getValue(ayah)
        val span = segments.first { it.position == word.position }
        val end = segments.firstOrNull { it.position == word.position + 1 }?.startMs ?: span.endMs
        val curve = if (t.tajweedPacing) TajweedPacing.curve(
            word.arabic, (span.endMs - span.startMs).toFloat() / (end - span.startMs),
            TajweedPacing.Hold(
                madd = t.holdMadd, ghunnah = t.holdGhunnah, waqf = t.holdWaqf,
                connect = t.holdConnect, isAyahFinal = words.getOrNull(i + 1)?.first != ayah,
                cruiseCap = t.cruiseCap, waqfShare = t.waqfShare,
                waqfLengthScale = t.waqfLengthScale, creep = t.holdCreep,
            ), words.getOrNull(i - 1)?.takeIf { it.first == ayah }?.second?.arabic,
        ) else null
        PaperInkWord(
            spec.getDouble("width").toFloat(), spec.getDouble("height").toFloat(),
            spec.getDouble("baseline").toFloat(), PaperInkClock(span.startMs, end, t.glintFadeMs), ayah,
            curve, shader("$ayah-${word.position}"),
        )
    }
    val english = JSONArray(app.assets.open("paper-ink-study/english.json").bufferedReader().use { it.readText() })
    val englishWords = verses.flatMap { verse ->
        val specs = (0 until english.length()).map { english.getJSONObject(it) }
            .filter { it.getInt("ayah") == verse.number }
        check(specs.joinToString(" ") { it.getString("text") } == verse.translation)
        val alignment = EnglishWordAlignment.align(verse.translation, verse.words.map { it.translation },
            verse.words.map { it.lemma }, verse.words.map { it.arabic })
        val windows = paperInkEnglishWindows(specs.map { it.getInt("start") until it.getInt("end") },
            verse.translation.length, alignment, timings.getValue(verse.number))
        specs.mapIndexed { index, spec ->
            val span = windows[index]
            PaperInkWord(spec.getDouble("width").toFloat(), spec.getDouble("height").toFloat(),
                spec.getDouble("baseline").toFloat(), PaperInkClock(span.startMs, span.endMs, t.glintFadeMs),
                verse.number, null, shader("en-${verse.number}-${spec.getInt("position")}"))
        }
    }
    return PaperInkStudy(reciter, arabicWords, englishWords)
}

@RequiresApi(33)
private enum class InkPreview(val label: String) {
    Voice("Follow voice"), Wet("Hold fill"), Dry("Dry ink"),
}

@RequiresApi(33)
@Composable
private fun PaperInkStudyPage(app: QuranApp, study: PaperInkStudy) {
    val state by app.player.state.collectAsStateWithLifecycle()
    val speed = remember { mutableFloatStateOf(1f) }
    val tick = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val preview = remember { mutableStateOf(InkPreview.Voice) }
    val english = remember { mutableStateOf(false) }
    val thickness = remember { mutableFloatStateOf(.25f) }
    val fontScale = remember { mutableFloatStateOf(.91f) }
    val fillAmount = remember { mutableFloatStateOf(1f) }
    val reflection = remember { mutableFloatStateOf(.5f) }
    val softness = remember { mutableFloatStateOf(0f) }
    val paper = Color(0xFFFAF3E8)
    val repeatColor = LocalQuranAccents.current.repeatInk
    LaunchedEffect(study) {
        app.player.playSurah(1, 3, 1, study.reciter, "Al-Fatihah", preserveRepeatRange = false, autoplay = false)
        app.player.setSpeed(1f)
        app.player.setRepeatMode(Player.REPEAT_MODE_OFF)
    }
    LaunchedEffect(state.nowPlaying?.ayah, state.positionEvents.inkId) {
        val item = state.nowPlaying
        if (item?.surahId == 1 && item.reciterId == 14 && item.ayah in 1..3) {
            val armed = study.sequence.observe(item.ayah, state.positionEvents.inkId)
            app.player.setRepeatMode(if (armed) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF)
        }
    }
    LaunchedEffect(state.isPlaying, state.positionEvents.clockId) {
        val until = SystemClock.uptimeMillis() + InkEngine.tuning.glintFadeMs
        do {
            withFrameNanos { tick.longValue = SystemClock.uptimeMillis() }
        } while (state.isPlaying || SystemClock.uptimeMillis() < until)
    }
    Column(
        Modifier.fillMaxSize().background(paper).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Al-Fatihah", style = MaterialTheme.typography.titleMedium)
            Text("1:1–3", style = MaterialTheme.typography.titleMedium)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val now = tick.longValue
            val belongs = state.nowPlaying?.let { it.surahId == 1 && it.ayah in 1..3 && it.reciterId == 14 } == true
            // The service clears nowPlaying at EOF; keep the completed ink for dry-down.
            val media = if (belongs) {
                study.clock.sample(state.positionEvents.clockId, app.player.positionMs).also { study.lastMediaMs = it }
            } else if (state.nowPlaying == null && study.lastMediaMs > 0) {
                maxOf(study.lastMediaMs, app.player.positionMs)
            } else 0L
            val currentAyah = if (belongs) state.nowPlaying!!.ayah else study.sequence.ayah
            val scale = min(size.width / (study.maxWidth + 16f), size.height / 1080f) * fontScale.floatValue
            val native = drawContext.canvas.nativeCanvas
            for (word in study.allWords) {
                val position = when {
                    word.ayah < currentAyah -> word.motion.endMs
                    word.ayah == currentAyah -> media
                    else -> 0L
                }
                word.motion.update(position, state.isPlaying && belongs && word.ayah == currentAyah, now)
            }
            val verses = if (english.value) study.englishLines else study.arabicLines
            val gap = if (english.value) 8f else 24f
            for ((row, lines) in verses.withIndex()) {
                for ((lineIndex, line) in lines.withIndex()) {
                    var x = (size.width + line.width * scale * if (english.value) -1 else 1) / 2
                    val baseline = size.height / 2 + ((row - 1f) * 360f +
                        (lineIndex - (lines.size - 1f) / 2) * 140f) * scale
                    for (word in line.words) {
                        val progress = when (preview.value) {
                            InkPreview.Wet -> fillAmount.floatValue
                            InkPreview.Dry -> 1f
                            InkPreview.Voice -> if (english.value) word.motion.progress else
                                word.pacing?.at(word.motion.progress) ?: InkEngine.sweepEasing.transform(word.motion.progress)
                        }
                        val repeat = word.ayah == 2 && study.sequence.repeatedMiddle
                        val wet = when (preview.value) {
                            InkPreview.Voice -> word.motion.wet
                            InkPreview.Wet -> 1f
                            InkPreview.Dry -> 0f
                        }
                        word.shader.setFloatUniform("progress", progress)
                        word.shader.setFloatUniform("wet", wet)
                        word.shader.setFloatUniform("reflectionStrength", reflection.floatValue)
                        word.shader.setFloatUniform("lightSoftness", softness.floatValue)
                        word.shader.setFloatUniform("retained", if (repeat) 1f else 0f)
                        word.shader.setFloatUniform("repeatPass", if (repeat) 1f else 0f)
                        word.shader.setFloatUniform("repeatColor", repeatColor.red, repeatColor.green, repeatColor.blue)
                        word.shader.setFloatUniform("weight", thickness.floatValue * 3.5f)
                        word.shader.setFloatUniform("aa", .9f / scale)
                        if (!english.value) x -= word.width * scale
                        word.shader.setFloatUniform("origin", x / scale - 362.5f,
                            baseline / scale - word.baseline - size.height / (2 * scale))
                        native.save()
                        native.translate(x, baseline - word.baseline * scale)
                        native.scale(scale, scale)
                        native.drawRect(0f, 0f, word.width, word.height, word.paint)
                        native.restore()
                        x += if (english.value) (word.width + gap) * scale else -gap * scale
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Paper ink", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    listOf("Arabic", "English").forEachIndexed { index, label ->
                        val active = english.value == (index == 1)
                        Text(label, Modifier.semantics { selected = active }.quietClickable {
                            english.value = index == 1
                        }.padding(vertical = 8.dp), color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Inside out · liquid ink · verse 2 repeats", style = MaterialTheme.typography.bodySmall)
            InkStudyDial("Ink pooling", thickness)
            InkStudyDial("Font size", fontScale, .5f..1f)
            InkStudyDial("Reflection", reflection)
            InkStudyDial("Softness", softness)
            InkStudyDial("Inspect fill", fillAmount, onChange = {
                if (preview.value != InkPreview.Wet) app.player.pause()
                preview.value = InkPreview.Wet
            })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InkPreview.entries.forEach { mode ->
                    Text(mode.label, Modifier.quietClickable {
                        preview.value = mode
                        if (mode != InkPreview.Voice) app.player.pause()
                    }.padding(vertical = 8.dp), color = if (preview.value == mode)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (state.isPlaying) "Pause" else "Play", Modifier.quietClickable {
                    preview.value = InkPreview.Voice
                    app.player.togglePlayPause()
                }.padding(vertical = 12.dp))
                Text("Again", Modifier.quietClickable {
                    preview.value = InkPreview.Voice
                    study.sequence.reset()
                    study.lastMediaMs = 0
                    app.player.setRepeatMode(Player.REPEAT_MODE_OFF)
                    app.player.seekToWordAndPlay(1, 0)
                }.padding(vertical = 12.dp))
                Text(if (speed.floatValue == 1f) "1×" else "½×", Modifier.quietClickable {
                    speed.floatValue = if (speed.floatValue == 1f) .5f else 1f
                    app.player.setSpeed(speed.floatValue)
                }.padding(vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun InkStudyDial(
    label: String,
    value: MutableFloatState,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    onChange: () -> Unit = {},
) {
    val changed by rememberUpdatedState(onChange)
    val ink = MaterialTheme.colorScheme.primary
    val span = range.endInclusive - range.start
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.weight(1f).height(48.dp).semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(value.floatValue, range)
            setProgress { value.floatValue = it.coerceIn(range); changed(); true }
        }.pointerInput(value, range) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun adjust(x: Float) {
                    val fraction = ((x - 8.dp.toPx()) / (size.width - 16.dp.toPx())).coerceIn(0f, 1f)
                    value.floatValue = range.start + fraction * span
                    changed()
                }
                adjust(down.position.x)
                down.consume()
                do {
                    val change = awaitPointerEvent().changes.first { it.id == down.id }
                    adjust(change.position.x)
                    change.consume()
                } while (change.pressed)
            }
        }) {
            val left = 8.dp.toPx()
            val width = size.width - left * 2
            for (i in 0..20) {
                val x = left + width * i / 20
                val half = if (i % 5 == 0) 6.dp.toPx() else 3.dp.toPx()
                drawLine(ink.copy(alpha = .3f), Offset(x, center.y - half), Offset(x, center.y + half), 1.dp.toPx())
            }
            drawCircle(ink, 4.dp.toPx(), Offset(left + width * (value.floatValue - range.start) / span, center.y))
        }
        Text("${(value.floatValue * 100).roundToInt()}%", Modifier.width(44.dp), textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall)
    }
}
