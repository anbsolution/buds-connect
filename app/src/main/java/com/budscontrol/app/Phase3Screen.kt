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
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.budscontrol.app.bluetooth.CustomEqPreset
import com.budscontrol.app.bluetooth.EarbudSnapshot
import com.budscontrol.app.bluetooth.RfcommClient
import com.budscontrol.app.bluetooth.RfcommListener

private val OnePlusRed = Color(0xFFDF3438)
private val Page = Color(0xFFF4F5F7)
private val Ink = Color(0xFF17191D)
private val Muted = Color(0xFF777D87)
private val SoftRed = Color(0xFFFFECEC)
private val Border = Color(0xFFE8E9ED)

@Composable
fun Phase3Screen() {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    val adapter = remember { manager.adapter }
    val notifier = remember { LowBatteryNotifier(context) }
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
            override fun onBatteryLevel(part: String, level: Int) { notifier.show(part, level) }
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

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = OnePlusRed, onPrimary = Color.White,
            background = Page, surface = Color.White,
            onSurface = Ink, surfaceVariant = Color(0xFFF0F1F4),
            onSurfaceVariant = Muted
        )
    ) {
        Scaffold(
            containerColor = Page,
            bottomBar = {
                NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Bluetooth, null) }, label = { Text("Home") }, colors = NavigationBarItemDefaults.colors(selectedIconColor = OnePlusRed, selectedTextColor = OnePlusRed, indicatorColor = SoftRed))
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.Equalizer, null) }, label = { Text("Sound") }, colors = NavigationBarItemDefaults.colors(selectedIconColor = OnePlusRed, selectedTextColor = OnePlusRed, indicatorColor = SoftRed))
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.BatteryFull, null) }, label = { Text("Battery") }, colors = NavigationBarItemDefaults.colors(selectedIconColor = OnePlusRed, selectedTextColor = OnePlusRed, indicatorColor = SoftRed))
                    NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Icons.Default.TouchApp, null) }, label = { Text("Touch") }, colors = NavigationBarItemDefaults.colors(selectedIconColor = OnePlusRed, selectedTextColor = OnePlusRed, indicatorColor = SoftRed))
                    NavigationBarItem(selected = tab == 4, onClick = { tab = 4 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Controls") }, colors = NavigationBarItemDefaults.colors(selectedIconColor = OnePlusRed, selectedTextColor = OnePlusRed, indicatorColor = SoftRed))
                }
            }
        ) { padding ->
            when (tab) {
                0 -> HomeTab(Modifier.padding(padding), devices, permissionGranted, bluetoothOn, status, connected, snapshot, { refresh++ }, { client.connect(it) }, { client.disconnect() })
                1 -> SoundTab(Modifier.padding(padding), snapshot, connected, { client.selectEqPreset(it) }, { client.createCustomEqPreset(it) }, { id, gains -> client.saveCustomEqPreset(id, gains) })
                2 -> BatteryTab(Modifier.padding(padding), snapshot)
                3 -> TouchTab(Modifier.padding(padding), snapshot, connected, { side, action, fn -> client.setGestureBinding(side, action, fn) })
                else -> ControlsTab(Modifier.padding(padding), snapshot, connected, { client.setFindMyEarbuds(true) }, { client.setFindMyEarbuds(false) })
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun Title(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Ink)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HomeTab(
    modifier: Modifier, devices: List<BluetoothDevice>, permissionGranted: Boolean,
    bluetoothOn: Boolean, status: String, connected: Boolean, snapshot: EarbudSnapshot,
    refresh: () -> Unit, connect: (BluetoothDevice) -> Unit, disconnect: () -> Unit
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("BUDS CONTROL", color = OnePlusRed, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Title("Your earbuds", "OnePlus Nord Buds 2R")
                }
                Surface(shape = RoundedCornerShape(18.dp), color = SoftRed) {
                    Icon(Icons.Default.Bluetooth, contentDescription = "Bluetooth", tint = OnePlusRed, modifier = Modifier.padding(14.dp).size(28.dp))
                }
            }
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bluetooth, null, tint = OnePlusRed)
                    Spacer(Modifier.width(10.dp))
                    Text(if (bluetoothOn) "Phone Bluetooth is ON" else "Phone Bluetooth is OFF", fontWeight = FontWeight.SemiBold)
                }
                Text(status, color = Muted, style = MaterialTheme.typography.bodyMedium)
                if (connected) OutlinedButton(onClick = disconnect) { Text("Disconnect") }
                OutlinedButton(onClick = refresh) { Text("Refresh paired devices") }
                snapshot.productId?.let {
                    Text("Product ID: $it", fontWeight = FontWeight.SemiBold)
                    Text(if (snapshot.identified) "Nord Buds 2R identified" else "Unsupported model — device writes disabled",
                        color = if (snapshot.identified) Color(0xFF16834A) else OnePlusRed)
                }
            }
        }
        if (!permissionGranted) item { Text("Grant Nearby devices permission, then reopen the app.", color = OnePlusRed) }
        else if (devices.isEmpty()) item { Text("Pair Nord Buds 2R in Android Bluetooth settings first.", color = Color.Gray) }
        else {
            item { Text("Paired devices", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(devices) { device ->
                Panel {
                    Text(device.name ?: "Unnamed device", fontWeight = FontWeight.SemiBold)
                    Text(device.address, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { connect(device) }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) { Text("Connect") }
                }
            }
        }
        item {
            Panel {
                Text("Compatibility", fontWeight = FontWeight.SemiBold)
                Info("Custom EQ", if (snapshot.supportsCustomEq) "Available" else "Waiting for capability check")
                Info("Touch controls", if (snapshot.supportsGestures) "Available" else "Waiting for capability check")
                Info("Find My Earbuds", if (snapshot.supportsFind) "Available" else "Waiting for capability check")
                Info("Auto reconnect", "Up to 3 retries while this screen remains active")
            }
        }
    }
}

@Composable
private fun SoundTab(
    modifier: Modifier, snapshot: EarbudSnapshot, connected: Boolean,
    selectPreset: (Int) -> Unit, createPreset: (String) -> Unit, savePreset: (Int, List<Int>) -> Unit
) {
    var newName by remember { mutableStateOf("My Sound") }
    var selectedId by remember { mutableIntStateOf(-1) }
    var gains by remember { mutableStateOf(List(6) { 0 }) }
    val selected = snapshot.customEqPresets.firstOrNull { it.id == selectedId }
        ?: snapshot.customEqPresets.firstOrNull { it.selected }
        ?: snapshot.customEqPresets.firstOrNull()

    LaunchedEffect(selected?.id, selected?.gains) {
        if (selected != null) {
            selectedId = selected.id
            gains = selected.gains
        }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Title("Sound Studio", "Built-in presets and custom 6-band EQ") }
        item {
            Panel {
                Text("Built-in presets", fontWeight = FontWeight.SemiBold)
                Text(snapshot.eqStatus, color = Color.Gray)
                listOf(0 to "Balanced", 1 to "Bold", 2 to "Bass").forEach { (slot, name) ->
                    Button(
                        onClick = { selectPreset(slot) },
                        enabled = connected && snapshot.identified && snapshot.supportsEq,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = if (snapshot.eqSlot == slot) OnePlusRed else Color(0xFF34363B))
                    ) { Text(name) }
                }
            }
        }
        item {
            Panel {
                Text("Custom EQ Studio", fontWeight = FontWeight.SemiBold)
                Text(snapshot.customEqStatus, color = Color.Gray)
                OutlinedTextField(value = newName, onValueChange = { newName = it.take(24) }, label = { Text("Preset name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = { createPreset(newName) },
                    enabled = connected && snapshot.supportsCustomEq && snapshot.customEqPresets.size < 3 && newName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Create custom preset") }
                Text("Up to 3 custom presets. Each band uses the device-supported -6 to +6 gain range.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        }
        items(snapshot.customEqPresets, key = { it.id }) { preset ->
            CustomEqCard(
                preset = preset,
                selected = selected?.id == preset.id,
                gains = if (selected?.id == preset.id) gains else preset.gains,
                enabled = connected && snapshot.supportsCustomEq,
                onSelect = { selectedId = preset.id; gains = preset.gains; savePreset(preset.id, preset.gains) },
                onGain = { band, value -> if (selected?.id == preset.id) gains = gains.toMutableList().also { it[band] = value } },
                onSave = { savePreset(preset.id, gains) }
            )
        }
        if (snapshot.customEqPresets.isEmpty()) {
            item { Text("No custom presets reported by the earbuds yet.", color = Color.Gray) }
        }
    }
}

@Composable
private fun CustomEqCard(
    preset: CustomEqPreset, selected: Boolean, gains: List<Int>, enabled: Boolean,
    onSelect: () -> Unit, onGain: (Int, Int) -> Unit, onSave: () -> Unit
) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(preset.name, fontWeight = FontWeight.SemiBold)
                Text("Device preset #${preset.id}${if (preset.selected) " · active" else ""}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onSelect, enabled = enabled) { Text(if (selected) "Selected" else "Edit") }
        }
        if (selected) {
            preset.frequencies.forEachIndexed { index, frequency ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(freqLabel(frequency), fontWeight = FontWeight.Medium)
                        Text("${gains.getOrElse(index) { 0 }} dB", color = Color.Gray)
                    }
                    Slider(
                        value = gains.getOrElse(index) { 0 }.toFloat(),
                        onValueChange = { onGain(index, it.toInt()) },
                        valueRange = -6f..6f,
                        steps = 11,
                        enabled = enabled
                    )
                }
            }
            Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Save to earbuds") }
        }
    }
}

private fun freqLabel(hz: Int): String = when (hz) {
    1000 -> "1 kHz"
    4000 -> "4 kHz"
    8000 -> "8 kHz"
    16000 -> "16 kHz"
    else -> "$hz Hz"
}

@Composable
private fun BatteryTab(modifier: Modifier, snapshot: EarbudSnapshot) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Title("Battery Center", "Live device reports and low-battery alerts") }
        item {
            Panel {
                BatteryValue("Left earbud", snapshot.leftBattery)
                BatteryValue("Right earbud", snapshot.rightBattery)
                BatteryValue("Charging case", snapshot.caseBattery)
                Text("A notification is raised at 20% or below when a fresh battery report arrives. The case may report only while its lid is open.",
                    color = Color.Gray, style = MaterialTheme.typography.bodySmall)
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
private fun TouchTab(
    modifier: Modifier, snapshot: EarbudSnapshot, connected: Boolean,
    setBinding: (Int, Int, Int) -> Unit
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Title("Touch Control Studio", "Configure left and right earbuds independently") }
        item {
            Panel {
                Text(snapshot.gestureStatus, color = Color.Gray)
                Text("Each change rewrites the current table, then reads it back. Unsupported actions are not offered.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        }
        listOf(1 to "Left earbud", 2 to "Right earbud").forEach { (side, label) ->
            item {
                Panel {
                    Text(label, fontWeight = FontWeight.SemiBold)
                    listOf(1 to "Single tap", 2 to "Double tap", 3 to "Triple tap", 6 to "Long press").forEach { (action, actionLabel) ->
                        val current = snapshot.gestureBindings.firstOrNull {
                            it.deviceType == side && it.button == 1 && it.action == action
                        }?.function ?: 0
                        GestureActionPicker(
                            title = actionLabel,
                            current = current,
                            options = gestureOptions(action),
                            enabled = connected && snapshot.identified && snapshot.supportsGestures,
                            onChoose = { setBinding(side, action, it) }
                        )
                    }
                }
            }
        }
        item { Text("ANC is not offered for this model's gesture menu. The app does not invent ANC controls.", color = Color.Gray) }
    }
}

@Composable
private fun GestureActionPicker(title: String, current: Int, options: List<Pair<Int, String>>, enabled: Boolean, onChoose: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(actionName(current))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onChoose(code) })
                }
            }
        }
    }
}

private fun gestureOptions(action: Int): List<Pair<Int, String>> {
    val codes = when (action) {
        1 -> listOf(0x00, 0x01)
        2 -> listOf(0x00, 0x01, 0x03, 0x05, 0x06, 0x11)
        3 -> listOf(0x00, 0x03, 0x05, 0x06, 0x11)
        6 -> listOf(0x00, 0x0D)
        else -> listOf(0x00)
    }
    return codes.map { it to actionName(it) }
}

private fun actionName(code: Int): String = when (code) {
    0x00 -> "None"
    0x01 -> "Play / Pause"
    0x03 -> "Voice Assistant"
    0x05 -> "Previous Track"
    0x06 -> "Next Track"
    0x0D -> "Switch Device"
    0x11 -> "Game Mode"
    else -> "Unsupported"
}

@Composable
private fun ControlsTab(modifier: Modifier, snapshot: EarbudSnapshot, connected: Boolean, find: () -> Unit, stopFind: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                Text("A command acknowledgement does not prove the tone was heard. Confirm it physically.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Panel {
                Info("Automatic reconnect", "Up to 3 retries with increasing delay after an unexpected link loss")
                Info("Low battery alert", "Notification at 20% or below on fresh battery reports")
                Info("ANC", "Not supported on Nord Buds 2R")
                Info("Call noise reduction", "Built-in dual-mic AI call processing; no separate toggle exposed")
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
