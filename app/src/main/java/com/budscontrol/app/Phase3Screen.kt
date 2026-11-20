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
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.budscontrol.app.bluetooth.EarbudSnapshot
import com.budscontrol.app.bluetooth.RfcommClient
import com.budscontrol.app.bluetooth.RfcommListener

private val OnePlusRed = Color(0xFFEB4D4B)
private val Page = Color(0xFFF5F6F8)

@Composable
fun Phase3Screen() {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    val adapter = remember { manager.adapter }
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Not connected") }
    var connected by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf(EarbudSnapshot()) }
    var tab by remember { mutableIntStateOf(0) }

    val permissionGranted = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    val client = remember {
        RfcommClient(object : RfcommListener {
            override fun onStatus(statusText: String, isConnected: Boolean) {
                status = statusText
                connected = isConnected
            }
            override fun onSnapshot(value: EarbudSnapshot) { snapshot = value }
        })
    }
    DisposableEffect(client) { onDispose { client.disconnect() } }

    val devices: List<BluetoothDevice> = remember(refresh, permissionGranted) {
        if (permissionGranted) try {
            adapter?.bondedDevices?.toList()?.sortedBy { it.name ?: "" } ?: emptyList()
        } catch (_: SecurityException) { emptyList() } else emptyList()
    }
    val bluetoothOn = remember(refresh) {
        try { adapter?.isEnabled == true } catch (_: SecurityException) { false }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = OnePlusRed, background = Page, surface = Color.White)) {
        Scaffold(
            containerColor = Page,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Bluetooth, null) }, label = { Text("Home") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.Equalizer, null) }, label = { Text("Sound") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.BatteryFull, null) }, label = { Text("Battery") })
                    NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Controls") })
                }
            }
        ) { padding ->
            when (tab) {
                0 -> HomeTab(Modifier.padding(padding), devices, permissionGranted, bluetoothOn, status, connected, snapshot, { refresh++ }, { client.connect(it) }, { client.disconnect() })
                1 -> SoundTab(Modifier.padding(padding), snapshot, connected, { client.selectEqPreset(it) })
                2 -> BatteryTab(Modifier.padding(padding), snapshot)
                else -> ControlsTab(Modifier.padding(padding), snapshot, connected, { client.setFindMyEarbuds(true) }, { client.setFindMyEarbuds(false) })
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun Title(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HomeTab(
    modifier: Modifier,
    devices: List<BluetoothDevice>,
    permissionGranted: Boolean,
    bluetoothOn: Boolean,
    status: String,
    connected: Boolean,
    snapshot: EarbudSnapshot,
    refresh: () -> Unit,
    connect: (BluetoothDevice) -> Unit,
    disconnect: () -> Unit
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Title("Buds Control", "OnePlus Nord Buds 2R · Phase 3") }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bluetooth, null, tint = OnePlusRed)
                    Spacer(Modifier.width(10.dp))
                    Text(if (bluetoothOn) "Phone Bluetooth is ON" else "Phone Bluetooth is OFF", fontWeight = FontWeight.SemiBold)
                }
                Text(status, color = Color.Gray)
                if (connected) OutlinedButton(onClick = disconnect) { Text("Disconnect") }
                OutlinedButton(onClick = refresh) { Text("Refresh paired devices") }
                if (snapshot.productId != null) {
                    Text("Product ID: ${snapshot.productId}", fontWeight = FontWeight.SemiBold)
                    Text(if (snapshot.identified) "Nord Buds 2R identified" else "Unsupported model — device writes disabled", color = if (snapshot.identified) Color(0xFF16834A) else OnePlusRed)
                }
            }
        }
        if (!permissionGranted) {
            item { Text("Grant Nearby devices permission, then reopen the app.", color = OnePlusRed) }
        } else if (devices.isEmpty()) {
            item { Text("No paired devices found. Pair Nord Buds 2R in Android Bluetooth settings first.", color = Color.Gray) }
        } else {
            item { Text("Paired devices", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(devices) { device ->
                Panel {
                    Text(device.name ?: "Unnamed device", fontWeight = FontWeight.SemiBold)
                    Text(device.address, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { connect(device) }, modifier = Modifier.fillMaxWidth()) { Text("Connect using RFCOMM") }
                }
            }
        }
        item {
            Panel {
                Text("Compatibility", fontWeight = FontWeight.SemiBold)
                Info("Equalizer", if (snapshot.supportsEq) "Advertised by device capability bitmap" else "Waiting for model handshake")
                Info("Tap customization", if (snapshot.supportsGestures) "Gesture table readable; editing deferred" else "Waiting for model handshake")
                Info("Find My Earbuds", if (snapshot.supportsFind) "Advertised by device capability bitmap" else "Waiting for model handshake")
                Info("ANC", "Not supported on Nord Buds 2R")
                Info("Call noise reduction", "Built-in dual-mic AI call algorithm; no separate app toggle verified")
            }
        }
    }
}

@Composable
private fun SoundTab(modifier: Modifier, snapshot: EarbudSnapshot, connected: Boolean, selectPreset: (Int) -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Title("Sound Studio", "Change the earbud's built-in EQ preset") }
        item {
            Panel {
                Text("Current earbud preset", fontWeight = FontWeight.SemiBold)
                Text(snapshot.eqStatus, color = Color.Gray)
                Text("Preset selection is sent to the earbuds and then read back. No phone-wide audio EQ is simulated.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Panel {
                Text("Sound Master Equalizer", fontWeight = FontWeight.SemiBold)
                listOf(0 to "Balanced", 1 to "Bold", 2 to "Bass").forEach { (slot, name) ->
                    Button(
                        onClick = { selectPreset(slot) },
                        enabled = connected && snapshot.identified && snapshot.supportsEq,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = if (snapshot.eqSlot == slot) OnePlusRed else Color(0xFF34363B))
                    ) { Text(name) }
                }
                if (!snapshot.identified) Text("Connect and identify Nord Buds 2R to enable controls.", color = Color.Gray)
            }
        }
        item {
            Panel {
                Text("Custom EQ", fontWeight = FontWeight.SemiBold)
                Text("The model advertises custom EQ capability. Custom-band editing is deferred until preset-list read/write and read-back validation are complete.", color = Color.Gray)
            }
        }
    }
}

@Composable
private fun BatteryTab(modifier: Modifier, snapshot: EarbudSnapshot) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Title("Battery Center", "Live RFCOMM readings · no estimated values") }
        item {
            Panel {
                BatteryValue("Left earbud", snapshot.leftBattery)
                BatteryValue("Right earbud", snapshot.rightBattery)
                BatteryValue("Charging case", snapshot.caseBattery)
                Text("The case may report a level only while its lid is open. Missing values are shown as Not reported.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Panel {
                Text("Firmware information", fontWeight = FontWeight.SemiBold)
                Text(snapshot.firmwareRaw, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun ControlsTab(modifier: Modifier, snapshot: EarbudSnapshot, connected: Boolean, find: () -> Unit, stopFind: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Title("Earbud Controls", "Model-gated features") }
        item {
            Panel {
                Text("Find My Earbuds", fontWeight = FontWeight.SemiBold)
                Text(snapshot.findStatus, color = Color.Gray)
                Button(onClick = find, enabled = connected && snapshot.identified && snapshot.supportsFind, modifier = Modifier.fillMaxWidth()) {
                    Text("Play locating tone")
                }
                OutlinedButton(onClick = stopFind, enabled = connected && snapshot.identified && snapshot.supportsFind, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop locating tone")
                }
                Text("A command acknowledgement does not prove the tone was heard. Confirm the sound during physical testing.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Panel {
                Text("Tap customization", fontWeight = FontWeight.SemiBold)
                Info("Gesture table", snapshot.gestureCount?.let { "$it entries read from earbuds" } ?: "Not yet read")
                Text(snapshot.gestureRaw, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                Text("This phase reads the real gesture table only. Editing stays disabled until action-option mapping is validated for this exact model.", color = Color.Gray)
            }
        }
        item {
            Panel {
                Info("Call noise reduction", "Built into earbuds; no separate toggle exposed")
                Info("Active Noise Cancellation", "Not supported by Nord Buds 2R")
            }
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontWeight = FontWeight.Medium)
        Text(value, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BatteryValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
