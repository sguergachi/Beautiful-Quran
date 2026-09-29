package com.beautifulquran.ui.theme

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs

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

/** Sampling while the phone is being moved: 5 Hz, the slowest the platform names. */
private const val MOVING_PERIOD_US = 200_000

/** Sampling once it has been still for [STILL_AFTER_SAMPLES]: 1 Hz, just enough to notice a pick-up. */
private const val STILL_PERIOD_US = 1_000_000

/** Consecutive samples with no real change before the sensor drops to [STILL_PERIOD_US]. */
private const val STILL_AFTER_SAMPLES = 8

/** A jump this large (of the 0..1 sheen) at the slow rate means the phone is moving again. */
private const val MOVING_STEP = 0.02f

/** Share of the remaining distance covered per sample — at 5 Hz, ~0.4 s to settle. */
private const val SMOOTHING = 0.5f

/**
 * A change smaller than this is not worth redrawing every gilded figure for:
 * a phone lying still (or held steadily) writes nothing at all.
 */
private const val MIN_STEP = 0.005f

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
class GildingTilt internal constructor() : State<Float> {
    private val sheen = mutableFloatStateOf(GILDING_REST)
    internal var demand = 0
        private set

    /** Set by [rememberGildingTilt]: start or stop the sensor to match [demand]. */
    internal var onDemandChanged: () -> Unit = {}

    internal fun acquire() { demand++; onDemandChanged() }

    internal fun release() { demand--; onDemandChanged() }

    override val value: Float get() = sheen.floatValue

    internal fun set(v: Float) {
        if (abs(v - sheen.floatValue) >= MIN_STEP) sheen.floatValue = v
    }
}

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
 * The accelerometer, low-passed into a sheen, at UI rate. It listens only while
 * the app is started *and* a gilded figure is on screen, and not at all when the
 * user has turned animations off (or the device has no accelerometer) — then the
 * gilding simply rests.
 */
@Composable
fun rememberGildingTilt(): State<Float> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val tilt = remember { GildingTilt() }
    DisposableEffect(context, lifecycle, tilt) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val animated = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
        if (manager == null || sensor == null || !animated) {
            return@DisposableEffect onDispose {}
        }
        lateinit var listener: SensorEventListener
        val powerManager = context.getSystemService(PowerManager::class.java)
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
                val target = sheenForRoll(event.values[0] / SensorManager.GRAVITY_EARTH)
                val jump = abs(target - smoothed)
                smoothed = if (seeded) smoothed + (target - smoothed) * SMOOTHING else target
                seeded = true
                tilt.set(smoothed)
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
            // Battery saver asks for no ambient motion; the gilding rests.
            val shouldListen = started && tilt.demand > 0 &&
                powerManager?.isPowerSaveMode != true
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
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { started = true; sync() }
                Lifecycle.Event.ON_STOP -> { started = false; sync() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        tilt.onDemandChanged = ::sync
        onDispose {
            tilt.onDemandChanged = {}
            lifecycle.removeObserver(observer)
            manager.unregisterListener(listener)
        }
    }
    return tilt
}
