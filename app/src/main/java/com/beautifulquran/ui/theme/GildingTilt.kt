package com.beautifulquran.ui.theme

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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

/** Share of the remaining distance covered per sample — ~50 Hz, so the light settles in ~0.3 s. */
private const val SMOOTHING = 0.06f

/** A change smaller than this is not worth redrawing every gilded figure for. */
private const val MIN_STEP = 0.002f

/** The sheen for a phone rolled so that gravity reads [rollG] g's across its width. */
internal fun sheenForRoll(rollG: Float): Float =
    GILDING_REST + GILDING_REST * (rollG / ROLL_FULL_SCALE).coerceIn(-1f, 1f)

private object RestingTilt : State<Float> {
    override val value: Float get() = GILDING_REST
}

/**
 * The accelerometer, low-passed into a sheen. Listens only while the app is
 * started, and not at all when the user has turned animations off (or the
 * device has no accelerometer) — then the gilding simply rests.
 */
@Composable
fun rememberGildingTilt(): State<Float> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val tilt = remember { mutableFloatStateOf(GILDING_REST) }
    DisposableEffect(context, lifecycle) {
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
        var smoothed = GILDING_REST
        var seeded = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val target = sheenForRoll(event.values[0] / SensorManager.GRAVITY_EARTH)
                smoothed = if (seeded) smoothed + (target - smoothed) * SMOOTHING else target
                seeded = true
                if (abs(smoothed - tilt.floatValue) >= MIN_STEP) tilt.floatValue = smoothed
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    seeded = false
                    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                }
                Lifecycle.Event.ON_STOP -> manager.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            manager.unregisterListener(listener)
        }
    }
    return tilt
}
