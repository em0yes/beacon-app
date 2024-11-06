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

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var sensorManager: SensorManager
    private lateinit var rotationVectorSensor: Sensor

    private var scanning by mutableStateOf(false)
    private var scanResults by mutableStateOf(listOf<ScanResult>())
    private var azimuthValues by mutableStateOf(listOf<Float>())

    private val targetMacAddresses = setOf(
        "60:98:66:32:98:58", "60:98:66:32:8E:28", "60:98:66:32:BC:AC", "60:98:66:30:A9:6E", "60:98:66:32:CA:74", "60:98:66:2F:CF:9F"
    )

    private var azimuth = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BLEScannerScreen(
                scanResults = scanResults,
                azimuthValues = azimuthValues,
                scanning = scanning,
                onStartScan = { startBleScan() },
                onStopScan = { promptFileNameAndSave() }
            )
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // SensorManager 초기화 및 TYPE_ROTATION_VECTOR 센서 등록
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)!!
        sensorManager.registerListener(sensorEventListener, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBluetoothPermissions()
        } else {
            startBleScan()
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
                scanResults = scanResults + result
                azimuthValues = azimuthValues + azimuth // 현재 azimuth 값을 추가합니다.

                val rssi = result.rssi
                sendDataToServer(macAddress, rssi, azimuth)
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
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun sendDataToServer(macAddress: String, rssi: Int, azimuth: Float) {
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
            .url("https://your-server-url/api/current_rssi") // 서버 URL 변경
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
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor == rotationVectorSensor) {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)

                // azimuth 값을 0~359 범위의 Float으로 변환
                azimuth = ((Math.toDegrees(orientation[0].toDouble()).toInt() + 360) % 360).toFloat()

                Log.i("Direction", "Azimuth: $azimuth")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }



    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(sensorEventListener, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(sensorEventListener)
    }

    private fun saveAndShareCsv(fileName: String) {
        val file = File(getExternalFilesDir(null), "$fileName.csv")

        try {
            FileWriter(file, false).use { writer ->
                writer.append("No.,TimeStamp,MAC Address,RSSI,Azimuth\n")
                scanResults.forEachIndexed { index, result ->
                    val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(result.timestampNanos / 1000000)
                    writer.append("${index + 1},$timestamp,${result.device.address},${result.rssi},$azimuth\n")
                }
            }
        } catch (e: IOException) {
            Log.e("MainActivity", "Error writing CSV", e)
        }
    }
}
