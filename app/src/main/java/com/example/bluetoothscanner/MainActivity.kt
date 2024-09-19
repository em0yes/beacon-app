package com.example.bluetoothscanner

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.runtime.*
import com.example.bluetoothscanner.ui.theme.BLEScannerScreen
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.provider.Settings

import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var scanning by mutableStateOf(false)
    private var scanResults by mutableStateOf(listOf<ScanResult>())
    private val targetMacAddresses = setOf(
        "60:98:66:33:42:D4", "60:98:66:32:8E:28", "60:98:66:32:BC:AC", "60:98:66:30:A9:6E",
        "60:98:66:32:CA:74", "60:98:66:2F:CF:9F", "60:98:66:32:B8:EF", "60:98:66:32:CA:59",
        "60:98:66:33:35:4C", "60:98:66:32:AF:B6", "60:98:66:33:0E:8C", "60:98:66:32:C8:E9",
        "60:98:66:32:9F:67", "60:98:66:33:24:44", "60:98:66:32:BB:CB", "60:98:66:32:AA:F8",
        "A0:6C:65:99:DB:7C", "60:98:66:32:98:58"
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

        // 테스트 메시지 서버로 전송
        //sendTestDataToServer()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBluetoothPermissions()
        } else {
            startBleScan()
        }


    }

    private fun requestBluetoothPermissions() {
        val requestMultiplePermissions =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
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
            requestMultiplePermissions.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }
    }

    private fun startBleScan() {
        try {
            // 스캔 시작 전에 기존 데이터를 초기화
            scanResults = listOf()

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothAdapter.bluetoothLeScanner.startScan(bleScanCallback)
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
            saveAndShareCsv(fileName) // CSV 파일 저장 및 공유
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

                val rssi = result.rssi
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val number = scanResults.size // 스캔된 데이터의 개수

                Log.i("BLE Scan", "Device: $macAddress, RSSI: $rssi")

                // 서버로 데이터 전송
                sendDataToServer(macAddress, rssi, number)
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

    private fun saveAndShareCsv(fileName: String) {
        val file = File(getExternalFilesDir(null), "$fileName.csv")

        try {
            // 새 파일에만 데이터를 기록
            FileWriter(file, false).use { writer ->
                // CSV 파일에 데이터를 씁니다.
                writer.append("No.,TimeStamp,MAC Address,RSSI\n")
                scanResults.forEachIndexed { index, result ->
                    val timestamp = SimpleDateFormat(
                        "HH:mm:ss",
                        Locale.getDefault()
                    ).format(result.timestampNanos / 1000000)
                    writer.append("${index + 1},$timestamp,${result.device.address},${result.rssi}\n")
                }
            }

            // CSV 파일 공유
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

    private fun sendDataToServer(macAddress: String, rssi: Int, number: Int) {
        val client = OkHttpClient()

        // Android ID 가져오기
        val androidId: String =
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        Log.i("androidID", "안드로이드아이디: ${androidId}")
        val json = """
        {
            "macAddress": "$macAddress",
            "rssi": $rssi,
            "deviceId": "$androidId",
            "number": $number
        }
    """

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://b2c7-117-16-195-2.ngrok-free.app/api/current_rssi") // 서버의 URL 여기에
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
}

