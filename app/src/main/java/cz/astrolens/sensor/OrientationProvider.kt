package cz.astrolens.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import cz.astrolens.astro.DEG2RAD
import cz.astrolens.astro.Mat3
import cz.astrolens.astro.RAD2DEG
import cz.astrolens.astro.Vec3
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Reads the phone's rotation-vector sensor, smooths it and exposes the device-to-sky
 * transform, including magnetic declination and a one-point calibration.
 *
 * World frame: X = East, Y = North, Z = Up. Device frame: X = right, Y = up (screen),
 * Z = out of the screen, so the back camera looks along -Z.
 */
class OrientationProvider(context: Context) : SensorEventListener {
    companion object {
        const val SOURCE_COMPASS = 0
        const val SOURCE_GYRO = 1
    }

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var sensor: Sensor? = null
    private val rm = FloatArray(9)
    private val cur = DoubleArray(9)
    private var hasData = false

    /** One of SensorManager.SENSOR_STATUS_ACCURACY_*. */
    @Volatile
    var accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE

    /** Base smoothing factor (0.03 = very smooth, 1 = raw). */
    var smoothing: Double = 0.15

    /** Magnetic declination in degrees, east positive (only used with the compass source). */
    var declinationDeg: Double = 0.0

    var calibration: Mat3 = Mat3.identity()
        private set
    var calibrated: Boolean = false
        private set

    /** True when the active sensor knows magnetic north. */
    var usesMagnetometer: Boolean = true
        private set

    val hasSensor: Boolean
        get() = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
            sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null

    val hasData2: Boolean get() = hasData

    fun start(source: Int): Boolean {
        stop()
        val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val game = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val chosen = if (source == SOURCE_GYRO) (game ?: rot) else (rot ?: game)
        sensor = chosen
        if (chosen == null) return false
        usesMagnetometer = chosen.type == Sensor.TYPE_ROTATION_VECTOR
        hasData = false
        return sm.registerListener(this, chosen, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onAccuracyChanged(s: Sensor?, acc: Int) {
        accuracy = acc
    }

    override fun onSensorChanged(e: SensorEvent) {
        val type = e.sensor.type
        if (type != Sensor.TYPE_ROTATION_VECTOR && type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
        val vals = if (e.values.size > 4) e.values.copyOf(4) else e.values
        SensorManager.getRotationMatrixFromVector(rm, vals)
        if (!hasData) {
            for (i in 0 until 9) cur[i] = rm[i].toDouble()
            hasData = true
            return
        }
        var tr = 0.0
        for (i in 0 until 9) tr += cur[i] * rm[i]
        val ang = acos(((tr - 1.0) / 2.0).coerceIn(-1.0, 1.0)) * RAD2DEG
        val alpha = smoothing + (1.0 - smoothing) * (ang / 4.0).coerceIn(0.0, 1.0)
        for (i in 0 until 9) cur[i] = cur[i] * (1.0 - alpha) + rm[i] * alpha
        orthonormalize()
    }

    private fun orthonormalize() {
        var c0x = cur[0]; var c0y = cur[3]; var c0z = cur[6]
        var n = sqrt(c0x * c0x + c0y * c0y + c0z * c0z)
        c0x /= n; c0y /= n; c0z /= n
        var c1x = cur[1]; var c1y = cur[4]; var c1z = cur[7]
        val d = c1x * c0x + c1y * c0y + c1z * c0z
        c1x -= d * c0x; c1y -= d * c0y; c1z -= d * c0z
        n = sqrt(c1x * c1x + c1y * c1y + c1z * c1z)
        c1x /= n; c1y /= n; c1z /= n
        val c2x = c0y * c1z - c0z * c1y
        val c2y = c0z * c1x - c0x * c1z
        val c2z = c0x * c1y - c0y * c1x
        cur[0] = c0x; cur[3] = c0y; cur[6] = c0z
        cur[1] = c1x; cur[4] = c1y; cur[7] = c1z
        cur[2] = c2x; cur[5] = c2y; cur[8] = c2z
    }

    /** Smoothed sensor matrix (device to sensor world), without any correction. */
    private fun raw(): Mat3 = Mat3(cur.copyOf())

    /** Device to true-ENU world, before the user calibration. */
    private fun deviceToTrueWorld(): Mat3 {
        val d = if (usesMagnetometer) declinationDeg else 0.0
        return Mat3.rotZ(-d * DEG2RAD) * raw()
    }

    /** Final device-to-world matrix including declination and calibration. */
    fun deviceToWorld(): Mat3 = calibration * deviceToTrueWorld()

    /** World-to-device matrix; apply to ENU vectors to get device coordinates. */
    fun worldToDevice(): Mat3 = deviceToWorld().transpose()

    /** Direction the back camera is pointing (ENU unit vector), including calibration. */
    fun cameraAxis(): Vec3 = deviceToWorld() * Vec3(0.0, 0.0, -1.0)

    /** Aligns the current camera axis exactly with [targetEnu]. */
    fun calibrateTo(targetEnu: Vec3) {
        val axis = deviceToTrueWorld() * Vec3(0.0, 0.0, -1.0)
        calibration = Mat3.between(axis, targetEnu)
        calibrated = true
    }

    fun resetCalibration() {
        calibration = Mat3.identity()
        calibrated = false
    }
}
