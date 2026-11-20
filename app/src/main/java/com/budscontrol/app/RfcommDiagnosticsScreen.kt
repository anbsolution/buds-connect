package com.budscontrol.app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.budscontrol.app.bluetooth.EarbudSnapshot
import com.budscontrol.app.bluetooth.RfcommClient
import com.budscontrol.app.bluetooth.RfcommListener

@Composable
fun RfcommDiagnosticsScreen() {
    val context = LocalContext.current
    val manager = remember {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    val adapter = remember { manager.adapter }
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Not connected") }
    var connected by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf(EarbudSnapshot()) }

    val permissionGranted = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    val client = remember {
        RfcommClient(object : RfcommListener {
            override fun onStatus(statusText: String, isConnected: Boolean) {
                status = statusText
                connected = isConnected
            }

            override fun onSnapshot(value: EarbudSnapshot) {
                snapshot = value
            }

            override fun onBatteryLevel(part: String, level: Int) {
                // Read-only diagnostics screen does not issue battery notifications.
            }
        })
    }
    DisposableEffect(client) {
        onDispose { client.disconnect() }
    }

    val devices: List<BluetoothDevice> = remember(refresh, permissionGranted) {
        if (permissionGranted) {
            try {
                adapter?.bondedDevices?.toList()?.sortedBy { it.name ?: "" } ?: emptyList()
            } catch (_: SecurityException) {
                emptyList()
            }
        } else emptyList()
    }
    val bluetoothOn = remember(refresh) {
        try { adapter?.isEnabled == true } catch (_: SecurityException) { false }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFEB4D4B))) {
        Scaffold(containerColor = Color(0xFFF5F6F8)) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(
                        "Buds Control",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Nord Buds 2R · Phase 2 RFCOMM diagnostics", color = Color.Gray)
                }

                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(
                            Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row {
                                Icon(
                                    Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = Color(0xFFEB4D4B)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    if (bluetoothOn) "Phone Bluetooth is ON"
                                    else "Phone Bluetooth is OFF",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text("RFCOMM: $status")
                            if (connected) {
                                OutlinedButton(onClick = { client.disconnect() }) {
                                    Text("Disconnect")
                                }
                            }
                            OutlinedButton(onClick = { refresh++ }) {
                                Text("Refresh paired devices")
                            }
                        }
                    }
                }

                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(
                            Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Device diagnostics",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            InfoRow("Product ID", snapshot.productId ?: "Waiting for response")
                            InfoRow("Left battery", snapshot.leftBattery)
                            InfoRow("Right battery", snapshot.rightBattery)
                            InfoRow("Charging case", snapshot.caseBattery)
                            InfoRow("Firmware raw", snapshot.firmwareRaw)
                            Text(
                                "Read-only test: no EQ, gesture, find-earbuds or firmware-write commands are sent.",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                item {
                    Text(
                        "Paired devices",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (!permissionGranted) {
                    item {
                        Text(
                            "Grant Android Nearby devices permission, then reopen this screen.",
                            color = Color(0xFFB3261E)
                        )
                    }
                } else if (devices.isEmpty()) {
                    item {
                        Text(
                            "No paired devices found. Pair the earbuds in Android Bluetooth settings first.",
                            color = Color.Gray
                        )
                    }
                } else {
                    items(devices) { device ->
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    device.name ?: "Unnamed device",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    device.address,
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Button(
                                    onClick = { client.connect(device) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Connect using secure RFCOMM")
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "Compatibility notes",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Secure RFCOMM UUIDs are tried in documented order. Insecure fallback is disabled. " +
                            "Keep the charging case open and earbuds awake during the test. " +
                            "Only complete, unsliced protocol frames are currently parsed.",
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.labelMedium)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
