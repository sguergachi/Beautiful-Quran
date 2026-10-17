package com.beautifulquran.ui.reader

import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.view.WindowCompat
import com.beautifulquran.QuranApp
import com.beautifulquran.R
import com.beautifulquran.data.ThemeMode
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.domain.HighlightClock
import com.beautifulquran.domain.TajweedPacing
import com.beautifulquran.ui.theme.BeautifulQuranTheme
import com.beautifulquran.ui.theme.quietClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Native, one-verse Paper paint experiment; intentionally separate from the reader. */
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
        (application as QuranApp).player.pause()
        super.onStop()
    }
}

@RequiresApi(33)
private class PaperInkWord(
    val width: Float,
    val height: Float,
    val baseline: Float,
    val motion: PaperInkClock,
    val pacing: TajweedPacing.Curve?,
    val shader: RuntimeShader,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = this@PaperInkWord.shader }
}

@RequiresApi(33)
private class PaperInkStudy(val reciter: Reciter, val words: List<PaperInkWord>) {
    val clock = HighlightClock()
    val lines = words.chunked(2)
    var lastMediaMs = 0L
}

@RequiresApi(33)
private suspend fun loadStudy(app: QuranApp): PaperInkStudy {
    val reciter = app.repository.reciters().first { it.id == 14 }
    val words = app.repository.surahContent(1).ayahs.first().words
    val segments = app.repository.defaultTimings(14, 1).getValue(1)
    val manifest = JSONArray(app.assets.open("paper-ink-study/words.json").bufferedReader().use { it.readText() })
    check(manifest.length() == words.size && segments.map { it.position } == words.map { it.position })
    val source = app.assets.open("paper-ink-study/ink.agsl").bufferedReader().use { it.readText() }
    val t = InkEngine.tuning
    return PaperInkStudy(reciter, words.mapIndexed { i, word ->
        val spec = manifest.getJSONObject(i)
        check(spec.getInt("arrivalBits") == 16)
        check(spec.getString("arabic") == word.arabic && spec.getInt("position") == word.position)
        val span = segments[i]
        val end = segments.getOrNull(i + 1)?.startMs ?: span.endMs
        val curve = if (t.tajweedPacing) TajweedPacing.curve(
            word.arabic, (span.endMs - span.startMs).toFloat() / (end - span.startMs),
            TajweedPacing.Hold(
                madd = t.holdMadd, ghunnah = t.holdGhunnah, waqf = t.holdWaqf,
                connect = t.holdConnect, isAyahFinal = i == words.lastIndex,
                cruiseCap = t.cruiseCap, waqfShare = t.waqfShare,
                waqfLengthScale = t.waqfLengthScale, creep = t.holdCreep,
            ), words.getOrNull(i - 1)?.arabic,
        ) else null
        val shader = RuntimeShader(source)
        for ((name, file) in listOf("field" to "field", "normals" to "normal")) {
            val bitmap = app.assets.open("paper-ink-study/word-${word.position}-$file.png").use {
                requireNotNull(BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false }))
            }
            val input = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            input.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            shader.setInputBuffer(name, input)
        }
        PaperInkWord(
            spec.getDouble("width").toFloat(), spec.getDouble("height").toFloat(),
            spec.getDouble("baseline").toFloat(), PaperInkClock(span.startMs, end, t.glintFadeMs), curve, shader,
        )
    })
}

@RequiresApi(33)
private enum class InkPreview(val label: String) {
    Voice("Follow voice"), Wet("Hold wet"), Dry("Dry ink"),
}

@RequiresApi(33)
@Composable
private fun PaperInkStudyPage(app: QuranApp, study: PaperInkStudy) {
    val state by app.player.state.collectAsStateWithLifecycle()
    val speed = remember { mutableFloatStateOf(1f) }
    val tick = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val lightingActive = remember { mutableStateOf(false) }
    val preview = remember { mutableStateOf(InkPreview.Voice) }
    val tilt = rememberPaperInkTilt(lightingActive.value || preview.value == InkPreview.Wet)
    val paper = Color(0xFFFAF3E8)
    LaunchedEffect(study) {
        app.player.playSurah(1, 1, 1, study.reciter, "Al-Fatihah", preserveRepeatRange = false, autoplay = false)
        app.player.setSpeed(1f)
    }
    LaunchedEffect(state.isPlaying, state.positionEvents.clockId) {
        lightingActive.value = true
        val until = SystemClock.uptimeMillis() + InkEngine.tuning.glintFadeMs
        do {
            withFrameNanos { tick.longValue = SystemClock.uptimeMillis() }
        } while (state.isPlaying || SystemClock.uptimeMillis() < until)
        lightingActive.value = false
    }
    Column(
        Modifier.fillMaxSize().background(paper).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Al-Fatihah", style = MaterialTheme.typography.titleMedium)
            Text("1:1", style = MaterialTheme.typography.titleMedium)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val now = tick.longValue
            val belongs = state.nowPlaying?.let { it.surahId == 1 && it.ayah == 1 && it.reciterId == 14 } == true
            // The service clears nowPlaying at EOF; keep the completed ink for dry-down.
            val media = if (belongs) {
                study.clock.sample(state.positionEvents.clockId, app.player.positionMs).also { study.lastMediaMs = it }
            } else if (state.nowPlaying == null && study.lastMediaMs > 0) {
                maxOf(study.lastMediaMs, app.player.positionMs)
            } else 0L
            val scale = size.width / 725f
            val native = drawContext.canvas.nativeCanvas
            for ((row, pair) in study.lines.withIndex()) {
                val lineWidth = pair.sumOf { it.width.toDouble() }.toFloat() + 24f
                var x = (size.width + lineWidth * scale) / 2
                val baseline = size.height / 2 + (row - .5f) * 260f * scale
                for (word in pair) {
                    word.motion.update(media, state.isPlaying && belongs, now)
                    val progress = if (preview.value != InkPreview.Voice) 1f else
                        word.pacing?.at(word.motion.progress) ?: InkEngine.sweepEasing.transform(word.motion.progress)
                    val wet = when (preview.value) {
                        InkPreview.Voice -> word.motion.wet
                        InkPreview.Wet -> 1f
                        InkPreview.Dry -> 0f
                    }
                    word.shader.setFloatUniform("progress", progress)
                    word.shader.setFloatUniform("wet", wet)
                    word.shader.setFloatUniform("applying", if (preview.value == InkPreview.Voice && word.motion.applying) 1f else 0f)
                    word.shader.setFloatUniform("weight", 1.3f)
                    word.shader.setFloatUniform("aa", .9f / scale)
                    word.shader.setFloatUniform("tilt", tilt.value.x, tilt.value.y)
                    x -= word.width * scale
                    word.shader.setFloatUniform("origin", x / scale - 362.5f,
                        baseline / scale - word.baseline - size.height / (2 * scale))
                    native.save()
                    native.translate(x, baseline - word.baseline * scale)
                    native.scale(scale, scale)
                    native.drawRect(0f, 0f, word.width, word.height, word.paint)
                    native.restore()
                    x -= 24f * scale
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Paper ink", style = MaterialTheme.typography.titleMedium)
            Text("Abu Bakr Al-Shatri · hold wet to inspect the reflection", style = MaterialTheme.typography.bodySmall)
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

/** One lifecycle-bound fused gyro listener; only the small light vector animates. */
@Composable
private fun rememberPaperInkTilt(active: Boolean): androidx.compose.runtime.State<Offset> {
    val manager = LocalContext.current.getSystemService(SensorManager::class.java)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val aim = remember { mutableStateOf(Offset.Zero) }
    val light = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(Unit) { snapshotFlow { aim.value }.collectLatest { light.animateTo(it, tween(140)) } }
    DisposableEffect(active, lifecycle) {
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val matrix = FloatArray(9)
        var origin: Offset? = null
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                val angles = Offset(atan2(matrix[6], matrix[8]), asin((-matrix[7]).coerceIn(-1f, 1f)))
                val zero = origin ?: angles.also { origin = it }
                fun relative(value: Float) = (atan2(sin(value), cos(value)) / .45f).coerceIn(-1f, 1f)
                aim.value = Offset(relative(angles.x - zero.x), relative(angles.y - zero.y))
            }
        }
        fun followLifecycle() {
            manager?.unregisterListener(listener)
            if (active && sensor != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                manager.registerListener(listener, sensor, 100_000)
            }
        }
        val observer = LifecycleEventObserver { _, _ -> followLifecycle() }
        lifecycle.addObserver(observer)
        followLifecycle()
        onDispose { lifecycle.removeObserver(observer); manager?.unregisterListener(listener) }
    }
    return light.asState()
}
