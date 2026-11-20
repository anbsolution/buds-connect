package com.budscontrol.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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

private val OnePlusRed = Color(0xFFEB4D4B)
private val Page = Color(0xFFF5F6F8)
private val Ink = Color(0xFF17191D)

class MainActivity : ComponentActivity() {
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBluetoothPermissionIfNeeded()
        setContent { BudsControlApp() }
    }

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
}

@Composable
private fun BudsControlApp() {
    var tab by remember { mutableStateOf(0) }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = OnePlusRed,
            background = Page,
            surface = Color.White,
            onBackground = Ink,
            onSurface = Ink
        )
    ) {
        Scaffold(
            containerColor = Page,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.Bluetooth, null) }, label = { Text("Home") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Equalizer, null) }, label = { Text("Sound") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.BatteryFull, null) }, label = { Text("Battery") })
                    NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                        icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            }
        ) { padding ->
            when (tab) {
                0 -> HomeScreen(Modifier.padding(padding))
                1 -> SoundScreen(Modifier.padding(padding))
                2 -> BatteryScreen(Modifier.padding(padding))
                else -> SettingsScreen(Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val bluetoothManager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    val adapter: BluetoothAdapter? = remember { bluetoothManager.adapter }
    val permissionGranted = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    val devices = remember(refresh, permissionGranted) {
        if (permissionGranted && adapter?.isEnabled == true) {
            try { adapter.bondedDevices?.map { it.name ?: "Unnamed Bluetooth device" }?.sorted() ?: emptyList() }
            catch (_: SecurityException) { emptyList() }
        } else emptyList()
    }
    val bluetoothOn = remember(refresh) { try { adapter?.isEnabled == true } catch (_: SecurityException) { false } }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            PageTitle("Buds Control", "OnePlus Nord Buds 2R companion")
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bluetooth, null, tint = OnePlusRed, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Bluetooth", fontWeight = FontWeight.SemiBold)
                        Text(if (bluetoothOn) "Bluetooth is ON" else "Bluetooth is OFF",
                            color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                    AssistChip(onClick = { refresh++ }, label = { Text("Refresh") })
                }
            }
        }
        item {
            Panel {
                Text("Your earbuds", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Pair your Nord Buds 2R in Android Bluetooth settings first. This Phase 1 build lists paired devices; it does not yet control proprietary earbud settings.",
                    color = Color.Gray
                )
                Button(
                    onClick = { refresh++ },
                    colors = ButtonDefaults.buttonColors(containerColor = OnePlusRed)
                ) { Text("Refresh paired devices") }
            }
        }
        item {
            Text("Paired Bluetooth devices", fontWeight = FontWeight.SemiBold)
        }
        if (!permissionGranted) {
            item { Text("Bluetooth permission is required. Grant Nearby devices permission and reopen this screen.", color = OnePlusRed) }
        } else if (devices.isEmpty()) {
            item { Text("No paired devices found. Pair your earbuds from Android Settings → Bluetooth.", color = Color.Gray) }
        } else {
            items(devices) { name ->
                Panel {
                    Text(name, fontWeight = FontWeight.SemiBold)
                    Text("Paired device · connection state not confirmed by this list", color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Text("Feature compatibility", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Panel {
                StatusLine("Bluetooth discovery", "Available")
                StatusLine("Battery / case battery", "Needs device protocol support")
                StatusLine("Tap customization", "Needs device protocol support")
                StatusLine("ANC", "Not supported by Nord Buds 2R")
                StatusLine("Firmware details", "Needs device protocol support")
            }
        }
    }
}

@Composable
private fun StatusLine(name: String, status: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name)
        Text(status, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SoundScreen(modifier: Modifier = Modifier) {
    val prefs = LocalContext.current.getSharedPreferences("sound_profiles", Context.MODE_PRIVATE)
    var profile by remember { mutableStateOf(prefs.getString("profile", "Balanced") ?: "Balanced") }
    var bass by remember { mutableFloatStateOf(prefs.getFloat("bass", 0f)) }
    var mid by remember { mutableFloatStateOf(prefs.getFloat("mid", 0f)) }
    var treble by remember { mutableFloatStateOf(prefs.getFloat("treble", 0f)) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("Sound Studio", "Save your preferred sound profile") }
        item {
            Panel {
                Text("Sound profile", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Balanced", "Bass", "Vocal", "Custom").forEach {
                        FilterChip(selected = profile == it, onClick = {
                            profile = it
                            prefs.edit().putString("profile", it).apply()
                        }, label = { Text(it) })
                    }
                }
            }
        }
        item {
            Panel {
                Text("Custom equalizer", fontWeight = FontWeight.SemiBold)
                Text("These values are saved as preferences. They are not yet applied to system audio or the earbuds.", color = Color.Gray)
                SliderRow("Bass", bass) { bass = it; prefs.edit().putFloat("bass", it).apply() }
                SliderRow("Mid", mid) { mid = it; prefs.edit().putFloat("mid", it).apply() }
                SliderRow("Treble", treble) { treble = it; prefs.edit().putFloat("treble", it).apply() }
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onValue: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text("${value.toInt()} dB", color = Color.Gray)
        }
        Slider(value = value, onValueChange = onValue, valueRange = -10f..10f)
    }
}

@Composable
private fun BatteryScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("Battery Center", "Live readings depend on Bluetooth device support")
        Panel {
            BatteryRow("Left earbud", "Not reported")
            BatteryRow("Right earbud", "Not reported")
            BatteryRow("Charging case", "Not reported")
            Text("Android's standard Bluetooth APIs do not guarantee separate earbud or case battery readings. No estimated percentages are shown.", color = Color.Gray)
        }
        Panel {
            Text("Low battery alerts", fontWeight = FontWeight.SemiBold)
            Text("Alerts can be enabled after a real battery source is available.", color = Color.Gray)
        }
    }
}

@Composable
private fun BatteryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, color = Color.Gray)
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("Settings", "Buds Control · Phase 1")
        Panel {
            StatusLine("Application ID", "com.budscontrol.app")
            StatusLine("Version", "1.0.0")
            StatusLine("Connection", "Bluetooth only")
            StatusLine("Firmware information", "Not yet available")
        }
        Panel {
            Text("Device-specific controls", fontWeight = FontWeight.SemiBold)
            Text("Tap mapping, call noise reduction, locating tone and firmware reading require a verified Nord Buds 2R communication protocol. They are not represented as working until tested.", color = Color.Gray)
        }
    }
}
