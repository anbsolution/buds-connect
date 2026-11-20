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

data class EarbudSnapshot(
    val productId: String? = null,
    val leftBattery: String = "Not reported",
    val rightBattery: String = "Not reported",
    val caseBattery: String = "Not reported",
    val firmwareRaw: String = "Not queried",
    val eqSlot: Int? = null,
    val eqStatus: String = "Waiting for device",
    val gestureCount: Int? = null,
    val gestureRaw: String = "Not queried",
    val findStatus: String = "Not used",
    val supportsEq: Boolean = false,
    val supportsGestures: Boolean = false,
    val supportsFind: Boolean = false,
    val identified: Boolean = false
)

interface RfcommListener {
    fun onStatus(status: String, connected: Boolean)
    fun onSnapshot(snapshot: EarbudSnapshot)
}

class RfcommClient(private val listener: RfcommListener) {
    companion object {
        private val SERVICE_UUIDS = listOf(
            UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5"),
            UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5")
        )
        private const val TARGET_PRODUCT_ID = "062C14"
    }

    private val connectionExecutor = Executors.newSingleThreadExecutor()
    private val commandExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closed = false
    private var snapshot = EarbudSnapshot()
    private var bitmap = ByteArray(0)
    private var nextSequence = 1

    private fun status(text: String, connected: Boolean) {
        main.post { listener.onStatus(text, connected) }
    }

    private fun publish() {
        val value = snapshot
        main.post { listener.onSnapshot(value) }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        closed = true
        closeSocketOnly()
        closed = false
        bitmap = ByteArray(0)
        nextSequence = 1
        snapshot = EarbudSnapshot()
        publish()
        status("Connecting to ${device.name ?: "paired device"}…", false)

        connectionExecutor.execute {
            var connectedSocket: BluetoothSocket? = null
            var lastError: Exception? = null
            for (uuid in SERVICE_UUIDS) {
                if (closed) return@execute
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
                return@execute
            }

            socket = connectedSocket
            status("RFCOMM connected. Reading device capabilities…", true)
            try {
                send(0x0100, byteArrayOf())
                readLoop(connectedSocket)
            } catch (error: Exception) {
                if (!closed) status("RFCOMM ended: ${error.message ?: "connection lost"}", false)
            } finally {
                closeSocketOnly()
                if (!closed) status("Disconnected", false)
            }
        }
    }

    private fun nextSeq(): Int {
        val result = nextSequence
        nextSequence = if (nextSequence >= 0xBF) 1 else nextSequence + 1
        return result
    }

    @Synchronized
    private fun send(command: Int, payload: ByteArray) {
        val active = socket ?: throw IOException("Socket is not connected")
        val totalLength = 7 + payload.size
        if (totalLength >= 128) throw IOException("Only short, unsliced frames are supported in this phase")
        val frame = ByteArray(2 + totalLength)
        frame[0] = 0xAA.toByte()
        frame[1] = totalLength.toByte()
        frame[2] = 0x00
        frame[3] = 0x00
        frame[4] = (command and 0xFF).toByte()
        frame[5] = ((command shr 8) and 0xFF).toByte()
        frame[6] = nextSeq().toByte()
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
                    eqStatus = if (known && hasBit(10)) "Reading current preset…" else "Unsupported / unavailable"
                )
                publish()
                if (!known) {
                    status("Unsupported product ID $id. Nord Buds 2R controls are locked.", true)
                    return
                }
                status("Nord Buds 2R identified. Reading battery, firmware and controls…", true)
                send(0x0106, byteArrayOf())
                if (hasBit(0)) send(0x0105, byteArrayOf())
                if (hasBit(10)) send(0x010F, byteArrayOf())
                if (hasBit(3)) send(0x0108, byteArrayOf())
            }
            0x8106 -> {
                snapshot = snapshot.copy(
                    leftBattery = batteryValue(payload, 1),
                    rightBattery = batteryValue(payload, 2),
                    caseBattery = batteryValue(payload, 3)
                )
                publish()
            }
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
                snapshot = snapshot.copy(
                    eqStatus = if (accepted) "Preset accepted; verifying read-back…" else "Preset rejected by earbuds"
                )
                publish()
                if (accepted) send(0x010F, byteArrayOf())
            }
            0x8400 -> {
                val accepted = payload.isNotEmpty() && payload[0].toInt() == 0
                snapshot = snapshot.copy(
                    findStatus = if (accepted) "Command accepted — check whether the earbuds emit the locating tone"
                    else "Find command rejected"
                )
                publish()
            }
            0x8108 -> {
                if (payload.size >= 2 && payload[0].toInt() == 0) {
                    val count = payload[1].toInt() and 0xFF
                    val raw = payload.drop(2).take(count * 4)
                        .joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                    snapshot = snapshot.copy(
                        gestureCount = count,
                        gestureRaw = raw.ifBlank { "No gesture entries" }
                    )
                    publish()
                }
            }
        }
    }

    private fun eqName(slot: Int): String = when (slot) {
        0 -> "Balanced"
        1 -> "Bold"
        2 -> "Bass"
        else -> "Unknown preset (slot $slot)"
    }

    private fun batteryValue(payload: ByteArray, part: Int): String {
        if (payload.size < 2 || payload[0].toInt() != 0) return "Not reported"
        val count = payload[1].toInt() and 0xFF
        var index = 2
        repeat(count) {
            if (index + 1 >= payload.size) return "Not reported"
            val id = payload[index++].toInt() and 0xFF
            val raw = payload[index++].toInt() and 0xFF
            if (id == part) {
                val level = raw and 0x7F
                if (level == 0 || level == 0x7F) return "Not reported"
                return "$level%" + if ((raw and 0x80) != 0) " · charging" else ""
            }
        }
        return "Not reported"
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

    fun setFindMyEarbuds(enabled: Boolean) {
        if (!snapshot.identified || !snapshot.supportsFind) return
        commandExecutor.execute {
            try { send(0x0400, byteArrayOf(if (enabled) 1 else 0)) }
            catch (error: Exception) { status("Find command failed: ${error.message}", true) }
        }
    }

    fun disconnect() {
        closed = true
        closeSocketOnly()
        status("Disconnected by user", false)
    }

    private fun closeSocketOnly() {
        try { socket?.close() } catch (_: IOException) { }
        socket = null
    }
}
