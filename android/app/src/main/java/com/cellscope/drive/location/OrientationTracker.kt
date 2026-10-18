package com.cellscope.drive.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

data class OrientationData(
    val trueHeadingDeg: Float? = null,           // 0.0 to 360.0 degrees from True North
    val magneticHeadingDeg: Float? = null,       // 0.0 to 360.0 degrees from Magnetic North
    val declinationDeg: Float? = null,           // Local magnetic declination from GeomagneticField
    val headingAccuracyDeg: Float? = null,       // Accuracy radius in degrees
    val sensorType: String = "NONE",             // "ROTATION_VECTOR", "GEOMAGNETIC_ROTATION_VECTOR", "ACCEL_MAG", "NONE"
    val isCalibrated: Boolean = false,           // True if sensor accuracy is LOW, MEDIUM, or HIGH
    val accuracyLevel: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    val timestampMs: Long = System.currentTimeMillis()
)

class OrientationTracker(
    private val context: Context,
    private val onOrientationChanged: ((OrientationData) -> Unit)? = null
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var rotationVectorSensor: Sensor? = null
    private var geomagneticVectorSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var magneticFieldSensor: Sensor? = null

    private var activeSensorType: String = "NONE"
    private var isListening = false

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private val accelReading = FloatArray(3)
    private val magnetReading = FloatArray(3)
    private var hasAccel = false
    private var hasMagnet = false

    @Volatile
    var latestOrientation: OrientationData = OrientationData()
        private set

    // Current coordinates for geomagnetic declination
    private var currentLat: Double? = null
    private var currentLon: Double? = null
    private var currentAltM: Double? = null

    init {
        initSensors()
    }

    private fun initSensors() {
        if (sensorManager == null) return
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        geomagneticVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magneticFieldSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }

    fun updateCoordinates(lat: Double?, lon: Double?, altM: Double?) {
        currentLat = lat
        currentLon = lon
        currentAltM = altM
    }

    fun startListening() {
        if (isListening || sensorManager == null) return

        var registered = false
        if (rotationVectorSensor != null) {
            registered = sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
            if (registered) activeSensorType = "ROTATION_VECTOR"
        }

        if (!registered && geomagneticVectorSensor != null) {
            registered = sensorManager.registerListener(this, geomagneticVectorSensor, SensorManager.SENSOR_DELAY_UI)
            if (registered) activeSensorType = "GEOMAGNETIC_ROTATION_VECTOR"
        }

        if (!registered && accelerometerSensor != null && magneticFieldSensor != null) {
            val regAccel = sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_UI)
            val regMag = sensorManager.registerListener(this, magneticFieldSensor, SensorManager.SENSOR_DELAY_UI)
            registered = regAccel && regMag
            if (registered) activeSensorType = "ACCEL_MAG"
        }

        isListening = registered
        Log.d("OrientationTracker", "Orientation tracking started: activeSensor=$activeSensorType, success=$registered")
    }

    fun stopListening() {
        if (!isListening || sensorManager == null) return
        sensorManager.unregisterListener(this)
        isListening = false
        Log.d("OrientationTracker", "Orientation tracking stopped.")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        var hasValidHeading = false
        var magHeadingDeg: Float? = null
        var accuracyDeg: Float? = null
        val accuracyLevel = event.accuracy
        val isCalibrated = accuracyLevel != SensorManager.SENSOR_STATUS_UNRELIABLE

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azimuthRad = orientationAngles[0]
                magHeadingDeg = azimuthToHeading(azimuthRad)
                hasValidHeading = true

                // On API 26+, ROTATION_VECTOR may provide heading accuracy in radians at index 4
                if (event.values.size >= 5 && event.values[4] > 0f) {
                    accuracyDeg = Math.toDegrees(event.values[4].toDouble()).toFloat()
                } else {
                    accuracyDeg = accuracyLevelToDegrees(accuracyLevel)
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelReading, 0, accelReading.size)
                hasAccel = true
                if (hasMagnet) {
                    hasValidHeading = computeFromAccelMag(outHeading = { magHeadingDeg = it }, outAcc = { accuracyDeg = it })
                }
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetReading, 0, magnetReading.size)
                hasMagnet = true
                if (hasAccel) {
                    hasValidHeading = computeFromAccelMag(outHeading = { magHeadingDeg = it }, outAcc = { accuracyDeg = it })
                }
            }
        }

        if (hasValidHeading && magHeadingDeg != null) {
            // Compute magnetic declination if coordinates are available
            val declination = computeDeclination(currentLat, currentLon, currentAltM, now)
            val trueHeading = if (declination != null) {
                computeTrueHeading(magHeadingDeg, declination)
            } else {
                magHeadingDeg // Fallback to magnetic heading if GPS position unknown
            }

            val data = OrientationData(
                trueHeadingDeg = trueHeading,
                magneticHeadingDeg = magHeadingDeg,
                declinationDeg = declination,
                headingAccuracyDeg = accuracyDeg,
                sensorType = activeSensorType,
                isCalibrated = isCalibrated,
                accuracyLevel = accuracyLevel,
                timestampMs = now
            )

            latestOrientation = data
            onOrientationChanged?.invoke(data)
        }
    }

    private fun computeFromAccelMag(outHeading: (Float) -> Unit, outAcc: (Float?) -> Unit): Boolean {
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, accelReading, magnetReading)
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuthRad = orientationAngles[0]
            outHeading(azimuthToHeading(azimuthRad))
            outAcc(20.0f) // Typical uncalibrated composite sensor error
            return true
        }
        return false
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        latestOrientation = latestOrientation.copy(
            accuracyLevel = accuracy,
            isCalibrated = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
        )
    }

    companion object {
        fun azimuthToHeading(azimuthRad: Float): Float {
            val deg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
            return (deg + 360f) % 360f
        }

        fun computeTrueHeading(magneticHeadingDeg: Float, declinationDeg: Float): Float {
            return (magneticHeadingDeg + declinationDeg + 360f) % 360f
        }

        fun computeDeclination(lat: Double?, lon: Double?, altM: Double?, timestampMs: Long): Float? {
            if (lat == null || lon == null) return null
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
            return try {
                val gmf = GeomagneticField(
                    lat.toFloat(),
                    lon.toFloat(),
                    (altM ?: 0.0).toFloat(),
                    timestampMs
                )
                gmf.declination
            } catch (e: Exception) {
                null
            }
        }

        fun accuracyLevelToDegrees(level: Int): Float? {
            return when (level) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 5.0f
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 15.0f
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 30.0f
                SensorManager.SENSOR_STATUS_UNRELIABLE -> null
                else -> null
            }
        }
    }
}
