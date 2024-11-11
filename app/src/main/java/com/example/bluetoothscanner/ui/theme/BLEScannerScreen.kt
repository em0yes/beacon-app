package com.example.bluetoothscanner.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import android.bluetooth.le.ScanResult
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BLEScannerScreen(
    scanResults: List<Pair<ScanResult, Int>>,  // ScanResult와 azimuth를 Pair로 전달
    scanning: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("BLE Scanner") })
        },
        content = { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Button(
                    onClick = { if (scanning) onStopScan() else onStartScan() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(if (scanning) "Stop Scanning" else "Start Scanning")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Scanned Devices:",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.LightGray)
                                .padding(vertical = 4.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TableCell(text = "No.", weight = 0.2f, fontSize = 12.sp, textAlign = TextAlign.Center)
                            TableCell(text = "Time Stamp", weight = 0.8f, fontSize = 12.sp, textAlign = TextAlign.Center)
                            TableCell(text = "MAC Address", weight = 1.5f, fontSize = 12.sp, textAlign = TextAlign.Center)
                            TableCell(text = "RSSI", weight = 0.5f, fontSize = 12.sp, textAlign = TextAlign.Center)
                            TableCell(text = "Azimuth", weight = 0.5f, fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                    }

                    itemsIndexed(scanResults) { index, (result, azimuth) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TableCell(text = (index + 1).toString(), weight = 0.2f, fontSize = 12.sp, textAlign = TextAlign.Center)
                            TableCell(text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(result.timestampNanos / 1000000), weight = 0.8f, fontSize = 12.sp, textAlign = TextAlign.Center)
                            TableCell(text = result.device.address, weight = 1.5f, fontSize = 12.sp, textAlign = TextAlign.Center)
                            TableCell(text = result.rssi.toString(), weight = 0.5f, fontSize = 12.sp, textAlign = TextAlign.Center)
                            TableCell(text = azimuth.toString(), weight = 0.5f, fontSize = 12.sp, textAlign = TextAlign.Center) // 해당 타임스탬프에 맞는 azimuth 값 표시
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun RowScope.TableCell(
    text: String,
    weight: Float,
    fontSize: androidx.compose.ui.unit.TextUnit,
    textAlign: TextAlign
) {
    Text(
        text = text,
        modifier = Modifier
            .weight(weight)
            .padding(4.dp),
        fontSize = fontSize,
        textAlign = textAlign
    )
}
