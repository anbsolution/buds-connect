package com.budscontrol.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.EOFException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

data class CustomEqPreset(
    val id: Int,
    val name: String,
    val frequencies: List<Int>,
    val gains: List<Int>,
    val selected: Boolean,
    val tag: List<Int>
)

data class GestureBinding(
    val deviceType: Int,
    val button: Int,
    val action: Int,
    val function: Int
)

data class EarbudSnapshot(
    val productId: String? = null,
    val leftBattery: String = "Not reported",
    val rightBattery: String = "Not reported",
    val caseBattery: String = "Not reported",
    val firmwareRaw: String = "Not queried",
    val eqSlot: Int? = null,
    val eqStatus: String = "Waiting for device",
    val customEqPresets: List<CustomEqPreset> = emptyList(),
    val customEqStatus: String = "Not queried",
    val gestureCount: Int? = null,
    val gestureBindings: List<GestureBinding> = emptyList(),
    val gestureStatus: String = "Not queried",
    val findStatus: String = "Not used",
    val supportsEq: Boolean = false,
    val supportsCustomEq: Boolean = false,
    val supportsGestures: Boolean = false,
    val supportsFind: Boolean = false,
    val identified: Boolean = false
)

interface RfcommListener {
    fun onStatus(status: String, connected: Boolean)
    fun onSnapshot(snapshot: EarbudSnapshot)
    fun onBatteryLevel(part: String, level: Int)
}

class RfcommClient(private val listener: RfcommListener) {
    companion object {
        private val SERVICE_UUIDS = listOf(
            UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5"),
            UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5")
        )
        private const val TARGET_PRODUCT_ID = "062C14"
        private val DEFAULT_EQ_FREQS = listOf(62, 250, 1000, 4000, 8000, 16000)
        private val DEFAULT_EQ_TAG = listOf(-6, 6)

        // Nord Buds 2R model profile options, decoded from the model's support masks.
        private val GESTURE_OPTIONS = mapOf(
            1 to listOf(0x00, 0x01),
            2 to listOf(0x00, 0x01, 0x03, 0x05, 0x06, 0x11),
            3 to listOf(0x00, 0x03, 0x05, 0x06, 0x11),
            6 to listOf(0x00, 0x0D)
        )
    }

    private val connectionExecutor = Executors.newSingleThreadExecutor()
    private val commandExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closed = false
    @Volatile private var snapshot = EarbudSnapshot()
    private var bitmap = ByteArray(0)
    private var nextSequence = 1
    private var gestureEntries: List<GestureBinding> = emptyList()
    private var reconnectAttempts = 0
    @Volatile private var generation = 0

    private fun status(text: String, connected: Boolean) {
        main.post { listener.onStatus(text, connected) }
    }

    private fun publish() {
        val value = snapshot
        main.post { listener.onSnapshot(value) }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val session = synchronized(this) {
            generation++
            closed = true
            closeSocketOnly()
            closed = false
            bitmap = ByteArray(0)
            nextSequence = 1
            reconnectAttempts = 0
            gestureEntries = emptyList()
            snapshot = EarbudSnapshot()
            generation
        }
        publish()
        status("Connecting to ${device.name ?: "paired device"}…", false)
        connectionExecutor.execute { connectionLoop(device, session) }
    }

    @SuppressLint("MissingPermission")
    private fun connectionLoop(device: BluetoothDevice, session: Int) {
        while (!closed && session == generation) {
            var connectedSocket: BluetoothSocket? = null
            var lastError: Exception? = null
            for (uuid in SERVICE_UUIDS) {
                if (closed || session != generation) return
                var candidate: BluetoothSocket? = null
                try {
                    candidate = device.createRfcommSocketToServiceRecord(uuid)
                    candidate.connect()
                    connectedSocket = candidate
                    break
                } catch (error: Exception) {
                    lastError = error
                    try { candidate?.close() } catch (_: IOException) { }
                }
            }

            if (connectedSocket == null) {
                status("RFCOMM connection failed: ${lastError?.message ?: "service unavailable"}", false)
                return
            }

            socket = connectedSocket
            status("RFCOMM connected. Reading device capabilities…", true)
            try {
                send(0x0100, byteArrayOf())
                readLoop(connectedSocket)
            } catch (error: Exception) {
                if (!closed) status("Connection interrupted: ${error.message ?: "link lost"}", false)
            } finally {
                closeSocketOnly()
            }

            if (closed || session != generation) return
            if (reconnectAttempts >= 3) {
                status("Automatic reconnect stopped after 3 attempts. Tap Connect to retry.", false)
                return
            }
            reconnectAttempts++
            val delayMs = 1500L * reconnectAttempts
            status("Reconnecting (${reconnectAttempts}/3)…", false)
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return }
        }
    }

    @Synchronized
    private fun send(command: Int, payload: ByteArray) {
        val active = socket ?: throw IOException("Socket is not connected")
        val totalLength = 7 + payload.size
        if (totalLength >= 128) throw IOException("This phase supports short unsliced frames only")
        val frame = ByteArray(2 + totalLength)
        frame[0] = 0xAA.toByte()
        frame[1] = totalLength.toByte()
        frame[2] = 0x00
        frame[3] = 0x00
        frame[4] = (command and 0xFF).toByte()
        frame[5] = ((command shr 8) and 0xFF).toByte()
        frame[6] = nextSequence.toByte()
        nextSequence = if (nextSequence >= 0xBF) 1 else nextSequence + 1
        frame[7] = (payload.size and 0xFF).toByte()
        frame[8] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(frame, 9)
        synchronized(active) {
            active.outputStream.write(frame)
            active.outputStream.flush()
        }
    }

    private fun hasBit(bit: Int): Boolean {
        val byteIndex = bit / 8
        if (byteIndex >= bitmap.size) return false
        return ((bitmap[byteIndex].toInt() and 0xFF) and (1 shl (bit % 8))) != 0
    }

    private fun readLoop(active: BluetoothSocket) {
        val input = active.inputStream
        while (!closed && active.isConnected) {
            var marker = input.read()
            while (marker != -1 && marker != 0xAA) marker = input.read()
            if (marker == -1) throw EOFException("Earbud closed the RFCOMM stream")
            var length = 0
            var shift = 0
            while (true) {
                val value = input.read()
                if (value == -1) throw EOFException("Incomplete frame length")
                length = length or ((value and 0x7F) shl shift)
                if ((value and 0x80) == 0) break
                shift += 7
                if (shift > 21) throw IOException("Invalid frame length")
            }
            if (length < 7 || length > 2048) throw IOException("Unsupported frame length: $length")
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(body, offset, length - offset)
                if (count < 0) throw EOFException("Incomplete RFCOMM frame")
                offset += count
            }
            parseFrame(body)
        }
    }

    private fun parseFrame(body: ByteArray) {
        if (body.size < 7 || body[0].toInt() != 0 || body[1].toInt() != 0) return
        val command = (body[2].toInt() and 0xFF) or ((body[3].toInt() and 0xFF) shl 8)
        val payloadLength = (body[5].toInt() and 0xFF) or ((body[6].toInt() and 0xFF) shl 8)
        if (payloadLength > body.size - 7) return
        val payload = body.copyOfRange(7, 7 + payloadLength)

        when (command) {
            0x8100 -> {
                if (payload.isEmpty() || payload[0].toInt() != 0) {
                    status("Device rejected the protocol handshake", true)
                    return
                }
                bitmap = payload.copyOfRange(1, payload.size)
                status("Capabilities received. Checking Nord Buds 2R identity…", true)
                send(0x0103, byteArrayOf())
            }
            0x8103 -> {
                if (payload.size < 4 || payload[0].toInt() != 0) {
                    status("Product identity was not reported; controls remain locked", true)
                    return
                }
                val id = payload.copyOfRange(1, 4).reversedArray()
                    .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                val known = id == TARGET_PRODUCT_ID
                snapshot = snapshot.copy(
                    productId = id,
                    identified = known,
                    supportsEq = known && hasBit(10),
                    supportsGestures = known && hasBit(3),
                    supportsFind = known && hasBit(5),
                    supportsCustomEq = known && hasBit(34),
                    eqStatus = if (known && hasBit(10)) "Reading current preset…" else "Unsupported / unavailable",
                    customEqStatus = if (known && hasBit(34)) "Reading custom presets…" else "Not supported"
                )
                publish()
                if (!known) {
                    status("Unsupported product ID $id. Nord Buds 2R controls are locked.", true)
                    return
                }
                status("Nord Buds 2R identified. Reading battery and control tables…", true)
                send(0x0106, byteArrayOf())
                if (hasBit(22)) send(0x0205, byteArrayOf(2, 1, 2))
                if (hasBit(0)) send(0x0105, byteArrayOf())
                if (hasBit(10)) send(0x010F, byteArrayOf())
                if (hasBit(34)) send(0x0122, byteArrayOf())
                if (hasBit(3)) send(0x0108, byteArrayOf())
            }
            0x8106 -> updateBattery(payload, true)
            0x8105 -> {
                snapshot = snapshot.copy(firmwareRaw = decodeFirmware(payload))
                publish()
            }
            0x810F -> {
                val slot = if (payload.size >= 2 && payload[0].toInt() == 0)
                    payload[1].toInt() and 0xFF else null
                snapshot = snapshot.copy(
                    eqSlot = slot,
                    eqStatus = slot?.let { eqName(it) } ?: "Device did not report a preset"
                )
                publish()
            }
            0x8406 -> {
                val accepted = payload.isNotEmpty() && payload[0].toInt() == 0
                snapshot = snapshot.copy(eqStatus = if (accepted) "Preset accepted; verifying read-back…" else "Preset rejected")
                publish()
                if (accepted) send(0x010F, byteArrayOf())
            }
            0x8122 -> {
                val presets = parseCustomEqList(payload)
                snapshot = snapshot.copy(
                    customEqPresets = presets ?: snapshot.customEqPresets,
                    customEqStatus = if (presets == null) "Could not decode custom preset list" else "${presets.size} custom preset(s) read"
                )
                publish()
            }
            0x8418 -> {
                val accepted = payload.isNotEmpty() && payload[0].toInt() == 0
                snapshot = snapshot.copy(customEqStatus = if (accepted) "Preset write accepted; checking read-back…" else "Preset write rejected")
                publish()
                if (accepted && hasBit(34)) send(0x0122, byteArrayOf())
            }
            0x8108 -> {
                val parsed = parseGestureTable(payload)
                if (parsed != null) {
                    gestureEntries = parsed
                    snapshot = snapshot.copy(
                        gestureCount = parsed.size,
                        gestureBindings = parsed,
                        gestureStatus = "Gesture table read successfully"
                    )
                    publish()
                } else {
                    snapshot = snapshot.copy(gestureStatus = "Gesture table layout/status invalid; editing locked")
                    publish()
                }
            }
            0x8401 -> {
                val accepted = payload.isNotEmpty() && payload[0].toInt() == 0
                snapshot = snapshot.copy(gestureStatus = if (accepted) "Write accepted; verifying with a fresh table read…" else "Earbuds rejected gesture write")
                publish()
                if (accepted) send(0x0108, byteArrayOf())
            }
            0x8400 -> {
                val accepted = payload.isNotEmpty() && payload[0].toInt() == 0
                snapshot = snapshot.copy(findStatus = if (accepted) "Command accepted — confirm the locating tone physically" else "Find command rejected")
                publish()
            }
            0x0204 -> {
                if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == 0x01) {
                    updateBattery(payload.copyOfRange(1, payload.size), false)
                }
            }
        }
    }

    private fun updateBattery(payload: ByteArray, hasStatus: Boolean) {
        val header = if (hasStatus) 2 else 1
        if (payload.size < header || (hasStatus && payload[0].toInt() != 0)) return
        val count = payload[if (hasStatus) 1 else 0].toInt() and 0xFF
        var index = header
        var left = snapshot.leftBattery
        var right = snapshot.rightBattery
        var case = snapshot.caseBattery
        repeat(count) {
            if (index + 1 >= payload.size) return
            val part = payload[index++].toInt() and 0xFF
            val raw = payload[index++].toInt() and 0xFF
            val level = raw and 0x7F
            val value = if (level == 0 || level == 0x7F) "Not reported"
                else "$level%" + if ((raw and 0x80) != 0) " · charging" else ""
            when (part) {
                1 -> left = value
                2 -> right = value
                3 -> case = value
            }
            if (level in 1..100 && (raw and 0x80) == 0) {
                val name = when (part) { 1 -> "Left earbud"; 2 -> "Right earbud"; 3 -> "Charging case"; else -> "" }
                if (name.isNotEmpty()) main.post { listener.onBatteryLevel(name, level) }
            }
        }
        snapshot = snapshot.copy(leftBattery = left, rightBattery = right, caseBattery = case)
        publish()
    }

    private fun parseGestureTable(payload: ByteArray): List<GestureBinding>? {
        if (payload.size < 2 || payload[0].toInt() != 0) return null
        val count = payload[1].toInt() and 0xFF
        if (payload.size != 2 + count * 4) return null
        return (0 until count).map { i ->
            val o = 2 + i * 4
            GestureBinding(
                payload[o].toInt() and 0xFF,
                payload[o + 1].toInt() and 0xFF,
                payload[o + 2].toInt() and 0xFF,
                payload[o + 3].toInt() and 0xFF
            )
        }
    }

    private fun parseCustomEqList(payload: ByteArray): List<CustomEqPreset>? {
        if (payload.size < 2 || payload[0].toInt() != 0) return null
        val count = payload[1].toInt() and 0xFF
        var offset = 2
        val result = mutableListOf<CustomEqPreset>()
        repeat(count) {
            if (offset + 5 > payload.size) return null
            val selected = payload[offset].toInt() == 1
            val tag = listOf(payload[offset + 1].toInt(), payload[offset + 2].toInt())
            val id = payload[offset + 3].toInt() and 0xFF
            val nameLength = payload[offset + 4].toInt() and 0xFF
            offset += 5
            if (offset + nameLength + 1 > payload.size) return null
            val name = String(payload, offset, nameLength, Charsets.UTF_8)
            offset += nameLength
            val bandCount = payload[offset++].toInt() and 0xFF
            if (offset + bandCount * 3 > payload.size) return null
            val freqs = mutableListOf<Int>()
            val gains = mutableListOf<Int>()
            repeat(bandCount) {
                val frequency = (payload[offset].toInt() and 0xFF) or
                    ((payload[offset + 1].toInt() and 0xFF) shl 8)
                freqs += frequency
                gains += payload[offset + 2].toInt()
                offset += 3
            }
            result += CustomEqPreset(id, name, freqs, gains, selected, tag)
        }
        if (offset != payload.size) return null
        return result
    }

    private fun eqName(slot: Int): String = when (slot) {
        0 -> "Balanced"
        1 -> "Bold"
        2 -> "Bass"
        else -> "Unknown preset (slot $slot)"
    }

    private fun decodeFirmware(payload: ByteArray): String {
        if (payload.size < 2 || payload[0].toInt() != 0) return "Not reported"
        val count = payload[1].toInt() and 0xFF
        var index = 2
        val versions = mutableListOf<String>()
        repeat(count) {
            if (index + 2 >= payload.size) return "Incomplete firmware response"
            val deviceType = payload[index++].toInt() and 0xFF
            val versionType = payload[index++].toInt() and 0xFF
            val version = payload[index++].toInt() and 0xFF
            if (versionType == 2) versions += "$deviceType:$version"
        }
        return versions.joinToString(" · ").ifBlank { "No version fields reported" }
    }

    fun selectEqPreset(slot: Int) {
        if (!snapshot.identified || !snapshot.supportsEq || slot !in 0..2) return
        commandExecutor.execute {
            try { send(0x0406, byteArrayOf(slot.toByte())) }
            catch (error: Exception) { status("EQ command failed: ${error.message}", true) }
        }
    }

    fun createCustomEqPreset(name: String) {
        val cleanName = name.trim()
        if (!snapshot.identified || !snapshot.supportsCustomEq || cleanName.isEmpty() ||
            cleanName.toByteArray(Charsets.UTF_8).size > 24 || snapshot.customEqPresets.size >= 3
        ) return
        commandExecutor.execute {
            try {
                val payload = encodeCustomEq(0x01, 0, cleanName, DEFAULT_EQ_FREQS, List(6) { 0 }, DEFAULT_EQ_TAG)
                send(0x0418, payload)
                snapshot = snapshot.copy(customEqStatus = "Creating preset…")
                publish()
            } catch (error: Exception) {
                status("Custom EQ create failed: ${error.message}", true)
            }
        }
    }

    fun saveCustomEqPreset(id: Int, gains: List<Int>) {
        if (!snapshot.identified || !snapshot.supportsCustomEq) return
        val preset = snapshot.customEqPresets.firstOrNull { it.id == id } ?: return
        if (gains.size != preset.frequencies.size || gains.any { it !in -6..6 }) return
        commandExecutor.execute {
            try {
                val payload = encodeCustomEq(0x02, preset.id, preset.name, preset.frequencies, gains, preset.tag)
                send(0x0418, payload)
                snapshot = snapshot.copy(customEqStatus = "Saving ${preset.name}…")
                publish()
            } catch (error: Exception) {
                status("Custom EQ save failed: ${error.message}", true)
            }
        }
    }

    private fun encodeCustomEq(
        action: Int,
        id: Int,
        name: String,
        frequencies: List<Int>,
        gains: List<Int>,
        tag: List<Int>
    ): ByteArray {
        require(frequencies.size == gains.size && frequencies.size in 1..10)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= 24)
        val out = ArrayList<Byte>()
        out += action.toByte()
        out += tag[0].toByte()
        out += tag[1].toByte()
        out += id.toByte()
        out += nameBytes.size.toByte()
        nameBytes.forEach { out += it }
        out += frequencies.size.toByte()
        frequencies.indices.forEach { i ->
            val f = frequencies[i]
            out += (f and 0xFF).toByte()
            out += ((f shr 8) and 0xFF).toByte()
            out += gains[i].toByte()
        }
        return out.toByteArray()
    }

    fun setGestureBinding(deviceType: Int, action: Int, function: Int) {
        if (!snapshot.identified || !snapshot.supportsGestures || deviceType !in 1..2) return
        val allowed = GESTURE_OPTIONS[action] ?: return
        if (function !in allowed) return
        commandExecutor.execute {
            try {
                val matches = gestureEntries.filter {
                    it.deviceType == deviceType && it.button == 1 && it.action == action
                }
                if (matches.isEmpty()) {
                    status("No matching gesture slot on this earbud; write cancelled", true)
                    return@execute
                }
                val updated = gestureEntries.map { entry ->
                    if (entry.deviceType == deviceType && entry.button == 1 && entry.action == action)
                        entry.copy(function = function) else entry
                }
                val payload = ByteArray(1 + updated.size * 4)
                payload[0] = updated.size.toByte()
                updated.forEachIndexed { index, entry ->
                    val offset = 1 + index * 4
                    payload[offset] = entry.deviceType.toByte()
                    payload[offset + 1] = entry.button.toByte()
                    payload[offset + 2] = entry.action.toByte()
                    payload[offset + 3] = entry.function.toByte()
                }
                send(0x0401, payload)
                snapshot = snapshot.copy(gestureStatus = "Gesture change sent; waiting for device acknowledgement…")
                publish()
            } catch (error: Exception) {
                status("Gesture write failed: ${error.message}", true)
            }
        }
    }

    fun setFindMyEarbuds(enabled: Boolean) {
        if (!snapshot.identified || !snapshot.supportsFind) return
        commandExecutor.execute {
            try { send(0x0400, byteArrayOf(if (enabled) 1 else 0)) }
            catch (error: Exception) { status("Find command failed: ${error.message}", true) }
        }
    }

    fun disconnect() {
        synchronized(this) {
            generation++
            closed = true
            closeSocketOnly()
        }
        status("Disconnected by user", false)
    }

    private fun closeSocketOnly() {
        try { socket?.close() } catch (_: IOException) { }
        socket = null
    }
}
