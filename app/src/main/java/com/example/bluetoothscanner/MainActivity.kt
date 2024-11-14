package com.example.bluetoothscanner

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.example.bluetoothscanner.ui.theme.BLEScannerScreen
import okhttp3.*
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlin.math.*

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var magnetometerSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null

    private var scanning by mutableStateOf(false)
    private var scanResults by mutableStateOf<List<Triple<ScanResult, Int, String>>>(emptyList())

    private var latestAzimuth = 0f  // 최신 방위각 값을 저장

    private val targetMacAddresses = setOf(
        "60:98:66:32:98:58", "60:98:66:32:8E:28", "60:98:66:32:BC:AC", "60:98:66:30:A9:6E", "60:98:66:32:CA:74",
        "60:98:66:32:B8:EF"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BLEScannerScreen(
                scanResults = scanResults,
                scanning = scanning,
                onStartScan = { startBleScan() },
                onStopScan = { promptFileNameAndSave() }
            )
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // 정확한 센서 초기화
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // 센서들 등록
        rotationVectorSensor?.let { sensor ->
            sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_UI)
        } ?: Log.e("MainActivity", "Rotation vector sensor not available")

        magnetometerSensor?.let { sensor ->
            sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_UI)
        } ?: Log.e("MainActivity", "Magnetometer sensor not available")

        accelerometerSensor?.let { sensor ->
            sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_UI)
        } ?: Log.e("MainActivity", "Accelerometer sensor not available")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBluetoothPermissions()
        }
    }

    private fun requestBluetoothPermissions() {
        val requestMultiplePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                startBleScan()
            } else {
                Log.e("MainActivity", "Permission denied")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestMultiplePermissions.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        } else {
            requestMultiplePermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    private fun startBleScan() {
        try {
            scanResults = listOf()

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                val scanSettings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()

                bluetoothAdapter.bluetoothLeScanner.startScan(null, scanSettings, bleScanCallback)
                scanning = true
                Log.i("MainActivity", "Scanning started...")
            } else {
                Log.e("MainActivity", "Permission not granted")
            }
        } catch (e: SecurityException) {
            Log.e("MainActivity", "SecurityException: ${e.message}")
        }
    }
    // 방위각을 동서남북으로 변환하는 함수
    fun getDirection(azimuth: Int): String {
        return when {
            azimuth >= 348 || azimuth < 23 -> "N"  // 북쪽
            azimuth in 23..68 -> "NE"               // 북동쪽
            azimuth in 68..113 -> "E"                // 동쪽
            azimuth in 113..158 -> "SE"              // 동남쪽
            azimuth in 158..203 -> "S"               // 남쪽
            azimuth in 203..248 -> "SW"              // 남서쪽
            azimuth in 248..293 -> "W"               // 서쪽
            azimuth in 293..348 -> "NW"              // 북서쪽
            else -> "N"  // 기본값 (북쪽)
        }
    }

    private fun stopBleScanAndSave(fileName: String) {
        try {
            bluetoothAdapter.bluetoothLeScanner.stopScan(bleScanCallback)
            scanning = false
            Log.i("MainActivity", "Scanning stopped.")
            saveAndShareCsv(fileName)
        } catch (e: SecurityException) {
            Log.e("MainActivity", "SecurityException: ${e.message}")
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val macAddress = result.device.address

            if (macAddress in targetMacAddresses) {
                // 방위각을 소수점 없이 정수로 저장
                val azimuthSnapshot = latestAzimuth.toInt()
                val direction = getDirection(azimuthSnapshot)  // Direction을 얻음
                scanResults = scanResults + Triple(result, azimuthSnapshot, direction) // 방위각과 방향을 Triple로 저장
                val rssi = result.rssi
                sendDataToServer(macAddress, rssi, azimuthSnapshot) // 서버에 저장된 방위각을 전송
            }
        }
    }

    private fun promptFileNameAndSave() {
        val editText = EditText(this)
        editText.hint = "Enter file name"

        AlertDialog.Builder(this)
            .setTitle("Save CSV")
            .setMessage("Enter the file name for the CSV:")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val fileName = editText.text.toString()
                if (fileName.isNotBlank()) {
                    stopBleScanAndSave(fileName)
                } else {
                    Log.e("MainActivity", "File name is blank.")
                }
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun sendDataToServer(macAddress: String, rssi: Int, azimuth: Int) {
        val client = OkHttpClient()
        val androidId: String = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val json = """
        {
            "macAddress": "$macAddress",
            "rssi": $rssi,
            "deviceId": "$androidId",
            "azimuth": $azimuth
        }
        """

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://your-server-endpoint/api/current_rssi")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("MainActivity", "Failed to send data to server", e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.i("MainActivity", "Data sent successfully")
                } else {
                    Log.e("MainActivity", "Server error: ${response.code}")
                }
            }
        })
    }

    private val sensorEventListener = object : SensorEventListener {
        var gravity: FloatArray? = null
        var geomagnetic: FloatArray? = null

        override fun onSensorChanged(event: SensorEvent?) {
            if (event != null) {
                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientation)

                    // 방위각을 절대적으로 표시 (0이 북쪽, 90이 동쪽) 후 소수점을 없애고 정수로 저장
                    latestAzimuth = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat()
                    Log.i("Direction", "Latest Azimuth: $latestAzimuth")
                }

                if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    geomagnetic = event.values
                }

                if (gravity != null && geomagnetic != null) {
                    val R = FloatArray(9)
                    val I = FloatArray(9)
                    if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                        val orientation = FloatArray(3)
                        SensorManager.getOrientation(R, orientation)
                        latestAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onResume() {
        super.onResume()
        rotationVectorSensor?.let { sensor ->
            sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        magnetometerSensor?.let { sensor ->
            sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        accelerometerSensor?.let { sensor ->
            sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(sensorEventListener)
    }

    private fun saveAndShareCsv(fileName: String) {
        val file = File(getExternalFilesDir(null), "$fileName.csv")

        try {
            FileWriter(file, false).use { writer ->
                writer.append("No.,TimeStamp,MAC Address,RSSI,Direction,Azimuth\n")
                scanResults.forEachIndexed { index, (result, azimuth, direction) ->
                    val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(result.timestampNanos / 1000000)
                    writer.append("${index + 1},$timestamp,${result.device.address},${result.rssi},$direction,$azimuth\n")
                }
            }
            shareCsvFile(file)
        } catch (e: IOException) {
            Log.e("MainActivity", "Error writing CSV", e)
        }
    }

    private fun shareCsvFile(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "com.example.bluetoothscanner.provider",
            file
        )

        val shareIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(Intent.createChooser(shareIntent, "Share CSV via"))
    }
}
