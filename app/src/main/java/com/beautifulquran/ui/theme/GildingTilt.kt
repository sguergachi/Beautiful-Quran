package com.beautifulquran.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.provider.Settings
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.exp

/**
 * How the phone is held, as the gilding's `sheen` (0..1, the lighting axis
 * [goldBrush] tilts): 0.5 with the phone level side to side, swinging to the
 * ends as it rolls. Every gilded figure reads this at draw time, so tilting the
 * phone makes light travel across the leaf the way it does across a real one.
 *
 * Provided once at the app root by [rememberGildingTilt]; anywhere without one
 * (the share renderer, previews, tests) gilding rests at [GILDING_REST].
 */
val LocalGildingTilt = staticCompositionLocalOf<State<Float>> { RestingTilt }

const val GILDING_REST = 0.5f

/** Roll, in g's (sin of the angle), that carries the sheen its whole way: ~25°. */
private const val ROLL_FULL_SCALE = 0.42f

/**
 * Sampling while the phone is being moved: 10 Hz. The eye never sees these
 * samples — the light glides between them (see [GLIDE_SECONDS]) — so the sensor
 * can be slow and the motion still smooth.
 */
private const val MOVING_PERIOD_US = 100_000

/** Sampling once it has been still for [STILL_AFTER_SAMPLES]: 1 Hz, just enough to notice a pick-up. */
private const val STILL_PERIOD_US = 1_000_000

/** Consecutive samples with no real change (1.5 s) before the sensor drops to [STILL_PERIOD_US]. */
private const val STILL_AFTER_SAMPLES = 15

/** A jump this large (of the 0..1 sheen) at the slow rate means the phone is moving again. */
private const val MOVING_STEP = 0.02f

/** Share of the remaining distance covered per sample: takes the tremor out of a hand. */
private const val SMOOTHING = 0.5f

/**
 * Time constant of the glide toward the latest sample. The displayed sheen
 * closes on it exponentially, one frame at a time, and only while it is not
 * there yet — so the light moves at display rate however slowly the sensor
 * reports, and a settled or still phone draws no frames at all.
 */
private const val GLIDE_SECONDS = 0.14f

/**
 * A change smaller than this is not worth redrawing every gilded figure for:
 * a phone lying still (or held steadily) writes nothing at all.
 */
private const val MIN_STEP = 0.005f

/** Close enough to the aim to stop drawing frames for it. */
private const val ARRIVED = 0.001f

/**
 * Gravity across the *screen's* width, in m/s², from the sensor's device-frame
 * [x] and [y] and the display's [rotation] (`Surface.ROTATION_*`) — so a phone
 * held in landscape rolls the light the same way as one held upright.
 */
internal fun screenRoll(x: Float, y: Float, rotation: Int): Float = when (rotation) {
    Surface.ROTATION_90 -> y
    Surface.ROTATION_180 -> -x
    Surface.ROTATION_270 -> -y
    else -> x
}

/** The sheen for a phone rolled so that gravity reads [rollG] g's across its width. */
internal fun sheenForRoll(rollG: Float): Float =
    GILDING_REST + GILDING_REST * (rollG / ROLL_FULL_SCALE).coerceIn(-1f, 1f)

private object RestingTilt : State<Float> {
    override val value: Float get() = GILDING_REST
}

/**
 * The live tilt. [demand] counts the gilded figures on screen (see
 * [GildingDemand]); the sensor runs only while it is above zero, so the
 * Home list, Settings and every other screen without gold pay nothing.
 */
@Stable
class GildingTilt internal constructor(
    /** False on a device with nothing to tilt; the cover then keeps its idle sweep. */
    val hasSensor: Boolean = true,
) : State<Float> {
    private val sheen = mutableFloatStateOf(GILDING_REST)

    /** Where the latest sample says the light should be; [sheen] glides to it. */
    internal val aim = mutableFloatStateOf(GILDING_REST)
    internal var demand = 0
        private set

    /** Set by [rememberGildingTilt]: start or stop the sensor to match [demand]. */
    internal var onDemandChanged: () -> Unit = {}

    internal fun acquire() { demand++; onDemandChanged() }

    internal fun release() { demand--; onDemandChanged() }

    override val value: Float get() = sheen.floatValue

    /** A sample: retarget the glide. Steps under [MIN_STEP] are not worth a frame. */
    internal fun aimAt(v: Float) {
        if (abs(v - aim.floatValue) >= MIN_STEP) aim.floatValue = v
    }

    /** The first sample after a pause: no glide across a gap nobody watched. */
    internal fun snapTo(v: Float) {
        aim.floatValue = v
        sheen.floatValue = v
    }

    /** One frame of the glide, [dtSeconds] long. True when it has arrived. */
    internal fun glide(dtSeconds: Float): Boolean {
        val target = aim.floatValue
        val next = sheen.floatValue + (target - sheen.floatValue) * (1f - exp(-dtSeconds / GLIDE_SECONDS))
        val arrived = abs(target - next) < ARRIVED
        sheen.floatValue = if (arrived) target else next
        return arrived
    }
}

/**
 * A gilded figure's own layer. Its gold reads the tilt at draw time, and a draw
 * read invalidates the nearest layer above it: without one here that is the
 * whole sheet the figure sits on, re-recorded and redrawn for every step of the
 * light. A hand turning a page tilts the phone, so that was every frame of
 * every turn.
 */
internal fun Modifier.gildingLayer(): Modifier = graphicsLayer()

/** Keeps the tilt sensor alive for as long as this figure is composed. */
@Composable
internal fun GildingDemand(sheen: State<Float>) {
    if (sheen !is GildingTilt) return
    DisposableEffect(sheen) {
        sheen.acquire()
        onDispose { sheen.release() }
    }
}

/**
 * The accelerometer, low-passed into a sheen. It listens only while the app is
 * started *and* a gilded figure is on screen, and not at all in battery saver,
 * with animations turned off, or on a device with no accelerometer — then the
 * gilding simply rests. Both settings are followed live.
 */
@Composable
fun rememberGildingTilt(): State<Float> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val tilt = remember(context) {
        GildingTilt(
            hasSensor = context.getSystemService(SensorManager::class.java)
                ?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
        )
    }
    // One loop for the life of the tilt: it sleeps while the sheen is at its aim
    // and, once a sample moves the aim, closes on it a frame at a time. A new
    // sample mid-glide just changes what the next frame closes on — nothing is
    // restarted, so no frame's time is lost.
    LaunchedEffect(tilt) {
        while (true) {
            snapshotFlow { tilt.aim.floatValue != tilt.value }.first { it }
            var last = withFrameNanos { it }
            do {
                val now = withFrameNanos { it }
                val arrived = tilt.glide((now - last) / 1_000_000_000f)
                last = now
            } while (!arrived)
        }
    }
    DisposableEffect(context, lifecycle, tilt) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            return@DisposableEffect onDispose {}
        }
        lateinit var listener: SensorEventListener
        val powerManager = context.getSystemService(PowerManager::class.java)
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        fun animationsOff() = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) <= 0f
        var smoothed = GILDING_REST
        var seeded = false
        var still = 0
        var slow = false
        var listening = false
        fun register(periodUs: Int) {
            manager.unregisterListener(listener)
            manager.registerListener(listener, sensor, periodUs)
        }
        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val roll = screenRoll(
                    event.values[0],
                    event.values[1],
                    display?.rotation ?: Surface.ROTATION_0,
                )
                val target = sheenForRoll(roll / SensorManager.GRAVITY_EARTH)
                val jump = abs(target - smoothed)
                smoothed = if (seeded) smoothed + (target - smoothed) * SMOOTHING else target
                if (seeded) tilt.aimAt(smoothed) else tilt.snapTo(smoothed)
                seeded = true
                // A held phone barely moves: back off to a slow watch, and come
                // back up the moment it does.
                if (jump < MIN_STEP) still++ else still = 0
                if (!slow && still >= STILL_AFTER_SAMPLES) {
                    slow = true
                    register(STILL_PERIOD_US)
                } else if (slow && jump >= MOVING_STEP) {
                    slow = false
                    still = 0
                    register(MOVING_PERIOD_US)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        var started = false
        fun sync() {
            // Battery saver, or the user's animations turned off, asks for no
            // ambient motion; the gilding rests.
            val resting = powerManager?.isPowerSaveMode == true || animationsOff()
            val shouldListen = started && tilt.demand > 0 && !resting
            if (resting) tilt.aimAt(GILDING_REST)
            if (shouldListen == listening) return
            listening = shouldListen
            if (shouldListen) {
                seeded = false
                still = 0
                slow = false
                register(MOVING_PERIOD_US)
            } else {
                manager.unregisterListener(listener)
            }
        }
        // Battery saver and the animation scale are both changed with the app
        // still open, so they are heard as they happen, not only when the app
        // comes forward.
        val powerSaveChanged = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = sync()
        }
        val animationScaleChanged = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = sync()
        }
        var receiving = false
        fun receive(on: Boolean) {
            if (on == receiving) return
            receiving = on
            if (on) {
                ContextCompat.registerReceiver(
                    context,
                    powerSaveChanged,
                    IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                context.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                    false,
                    animationScaleChanged,
                )
            } else {
                context.unregisterReceiver(powerSaveChanged)
                context.contentResolver.unregisterContentObserver(animationScaleChanged)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { started = true; receive(true); sync() }
                Lifecycle.Event.ON_STOP -> { started = false; receive(false); sync() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        tilt.onDemandChanged = ::sync
        onDispose {
            tilt.onDemandChanged = {}
            lifecycle.removeObserver(observer)
            receive(false)
            manager.unregisterListener(listener)
        }
    }
    return tilt
}
