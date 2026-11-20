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
    val firmwareRaw: String = "Not queried"
)

interface RfcommListener {
    fun onStatus(status: String, connected: Boolean)
    fun onSnapshot(snapshot: EarbudSnapshot)
}

class RfcommClient(private val listener: RfcommListener) {
    companion object {
        // Vendor RFCOMM UUIDs documented by QuickBuds; not generic SPP 00001101.
        private val SERVICE_UUIDS = listOf(
            UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5"),
            UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5")
        )
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closed = false
    private var handshakeDone = false
    private var productDone = false
    private var batteryDone = false
    private var firmwareDone = false
    private var snapshot = EarbudSnapshot()

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
        handshakeDone = false
        productDone = false
        batteryDone = false
        firmwareDone = false
        snapshot = EarbudSnapshot()
        publish()
        status("Connecting securely to ${device.name ?: "paired device"}…", false)

        executor.execute {
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
                status(
                    "Secure RFCOMM failed: ${lastError?.message ?: "service unavailable"}. " +
                        "Keep the case open and earbuds awake; close other earbud companion apps and retry.",
                    false
                )
                return@execute
            }

            socket = connectedSocket
            status("RFCOMM connected. Starting read-only protocol handshake…", true)
            try {
                writeQuery(0x0100, 0x01)
                readLoop(connectedSocket)
            } catch (error: Exception) {
                if (!closed) status("RFCOMM ended: ${error.message ?: "connection lost"}", false)
            } finally {
                closeSocketOnly()
                if (!closed) status("Disconnected", false)
            }
        }
    }

    private fun writeQuery(command: Int, sequence: Int) {
        val active = socket ?: throw IOException("Socket is not connected")
        val frame = byteArrayOf(
            0xAA.toByte(), 0x07, 0x00, 0x00,
            (command and 0xFF).toByte(), ((command shr 8) and 0xFF).toByte(),
            sequence.toByte(), 0x00, 0x00
        )
        synchronized(active) {
            active.outputStream.write(frame)
            active.outputStream.flush()
        }
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
                val next = input.read()
                if (next == -1) throw EOFException("Incomplete frame length")
                length = length or ((next and 0x7F) shl shift)
                if ((next and 0x80) == 0) break
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
        // Phase 2 diagnostic parser currently accepts complete, unsliced frames only.
        if (body.size < 7 || body[0].toInt() != 0 || body[1].toInt() != 0) return

        val command = (body[2].toInt() and 0xFF) or ((body[3].toInt() and 0xFF) shl 8)
        val payloadLength = (body[5].toInt() and 0xFF) or ((body[6].toInt() and 0xFF) shl 8)
        if (payloadLength > body.size - 7) return
        val payload = body.copyOfRange(7, 7 + payloadLength)

        when (command) {
            0x8100 -> if (!handshakeDone) {
                handshakeDone = true
                status("Handshake received. Reading product identity…", true)
                writeQuery(0x0103, 0x02)
            }

            0x8103 -> if (!productDone) {
                productDone = true
                if (payload.size >= 4 && payload[0].toInt() == 0) {
                    val id = payload.copyOfRange(1, 4).reversedArray()
                        .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                    snapshot = snapshot.copy(productId = id)
                    publish()
                    status(
                        if (id == "062C14") "Nord Buds 2R identified. Reading battery…"
                        else "Protocol answered. Product ID $id (expected 062C14).",
                        true
                    )
                }
                writeQuery(0x0106, 0xF0)
            }

            0x8106 -> if (!batteryDone) {
                batteryDone = true
                snapshot = snapshot.copy(
                    leftBattery = batteryValue(payload, 1),
                    rightBattery = batteryValue(payload, 2),
                    caseBattery = batteryValue(payload, 3)
                )
                publish()
                writeQuery(0x0105, 0x03)
            }

            0x8105 -> if (!firmwareDone) {
                firmwareDone = true
                snapshot = snapshot.copy(
                    firmwareRaw = payload.joinToString(" ") {
                        "%02X".format(it.toInt() and 0xFF)
                    }.ifBlank { "Empty response" }
                )
                publish()
                status("Read-only diagnostics complete. No device settings were changed.", true)
            }
        }
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
                return level.toString() + "%" +
                    if ((raw and 0x80) != 0) " · charging" else ""
            }
        }
        return "Not reported"
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
