package com.dropflow.android.core.network.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Dfp11ProtocolTest {
    private fun header(tag: Int, length: Int) = byteArrayOf(
        0x44, 0x46, 0x50, 0x31, 1, tag.toByte(),
        (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()
    )

    private fun readHeader(tag: Int, length: Int) = FrameUtils.readHeader(ByteArrayInputStream(header(tag, length)))

    @Test fun newTagsHaveTheSpecifiedCodes() {
        assertEquals(0x0C.toByte(), FrameTag.TRANSFER_REQUEST_LARGE.code)
        assertEquals(0x0D.toByte(), FrameTag.RESUME_STATE.code)
        assertEquals(FrameTag.TRANSFER_REQUEST_LARGE, FrameTag.fromCode(0x0C))
        assertEquals(FrameTag.RESUME_STATE, FrameTag.fromCode(0x0D))
        assertThrows(IllegalArgumentException::class.java) { FrameTag.fromCode(0x0E) }

        val wire = ByteArrayOutputStream()
        FrameUtils.writeFrame(wire, FrameTag.RESUME_STATE, "[]".toByteArray())
        assertArrayEquals(header(0x0D, 2) + "[]".toByteArray(), wire.toByteArray())
    }

    @Test fun onlyTheTwoNewTagsMayCarrySixteenMebibytes() {
        val large = 16 * 1024 * 1024
        for (tag in listOf(0x0C, 0x0D)) {
            assertEquals(large, readHeader(tag, large).payloadLength)
            assertThrows(IllegalArgumentException::class.java) { readHeader(tag, large + 1) }
        }
        // Every other control frame keeps the 64 KB limit, data chunks 1 MiB.
        for (tag in listOf(0x01, 0x02, 0x03, 0x04, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B)) {
            assertEquals(64 * 1024, readHeader(tag, 64 * 1024).payloadLength)
            assertThrows(IllegalArgumentException::class.java) { readHeader(tag, 64 * 1024 + 1) }
        }
        assertEquals(1024 * 1024, readHeader(0x05, 1024 * 1024).payloadLength)
        assertThrows(IllegalArgumentException::class.java) { readHeader(0x05, 1024 * 1024 + 1) }
        // The same limits apply when writing.
        val buffer = ByteArray(Protocol.HEADER_SIZE)
        FrameUtils.encodeHeader(buffer, FrameTag.TRANSFER_REQUEST_LARGE, large)
        assertArrayEquals(header(0x0C, large), buffer)
        assertThrows(IllegalArgumentException::class.java) { FrameUtils.encodeHeader(buffer, FrameTag.RESUME_STATE, large + 1) }
        assertThrows(IllegalArgumentException::class.java) { FrameUtils.encodeHeader(buffer, FrameTag.TRANSFER_REQUEST, 64 * 1024 + 1) }
    }

    @Test fun aClaimedLengthIsNotAllocatedBeforeItsBytesArrive() {
        // Hands out data in small pieces and records how much room each read offered.
        class Trickle(private val total: Int) : InputStream() {
            var sent = 0
            var largestRequest = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largestRequest = maxOf(largestRequest, length)
                if (sent >= total) return -1
                val count = minOf(length, 1000, total - sent)
                for (index in 0 until count) buffer[offset + index] = ((sent + index) % 251).toByte()
                sent += count
                return count
            }
        }

        // A peer that claims 16 MiB and sends 3 KB costs 64 KB, not 16 MiB.
        val liar = Trickle(3000)
        assertThrows(EOFException::class.java) { FrameUtils.readPayload(liar, 16 * 1024 * 1024) }
        assertTrue(liar.largestRequest <= Protocol.MAX_METADATA_SIZE)

        // An honest large payload arrives complete and in order.
        val size = 300_000
        val payload = FrameUtils.readPayload(Trickle(size), size)
        assertEquals(size, payload.size)
        assertTrue(payload.indices.all { payload[it] == (it % 251).toByte() })
        assertEquals(0, FrameUtils.readPayload(ByteArrayInputStream(ByteArray(0)), 0).size)
    }

    @Test fun aManifestOverSixtyFourKilobytesUsesTheLargeTag() {
        val few = requestFor(listOf("a.txt" to ByteArray(1)), BATCH_AND_RESUME)
        val small = ByteArrayOutputStream()
        assertFalse(FrameUtils.writeTransferRequest(small, few))
        assertEquals(FrameTag.TRANSFER_REQUEST, FrameUtils.readHeader(small.toByteArray().inputStream()).tag)

        val many = requestFor((0 until 1500).map { "Photos/2026/holiday-picture-number-$it.jpg" to ByteArray(0) }, BATCH_AND_RESUME)
        val big = ByteArrayOutputStream()
        assertTrue(FrameUtils.writeTransferRequest(big, many))
        val input = big.toByteArray().inputStream()
        val header = FrameUtils.readHeader(input)
        assertEquals(FrameTag.TRANSFER_REQUEST_LARGE, header.tag)
        assertTrue(header.payloadLength > Protocol.MAX_METADATA_SIZE)
        // Same JSON either way; only the tag differs.
        val parsed = TransferRequestPayload.fromJsonString(String(FrameUtils.readPayload(input, header.payloadLength)))
        assertEquals(many, parsed)
        ReceiveValidation.request(parsed)

        val tooMany = requestFor((0 until 17).map { "x".repeat(1024 * 1024) + it to ByteArray(0) })
        val error = assertThrows(IllegalArgumentException::class.java) { FrameUtils.writeTransferRequest(ByteArrayOutputStream(), tooMany) }
        assertEquals("Too many files for one transfer. Send them in smaller groups.", error.message)
    }

    @Test fun featuresAreOnlySerializedWhenOfferedAndParsedLeniently() {
        val files = listOf<TestFile>("a.txt" to ByteArray(1))
        // An original sender's request must not grow a new field.
        assertFalse(requestFor(files).toJsonObject().has("features"))
        val offered = requestFor(files, BATCH_AND_RESUME).toJsonObject()
        assertEquals(listOf("batch", "resume"), offered.getJSONArray("features").let { list -> (0 until list.length()).map { list.getString(it) } })
        assertEquals(BATCH_AND_RESUME, TransferRequestPayload.fromJsonString(offered.toString()).features)
        // The sender never puts local paths in the request.
        assertFalse(offered.toString().contains("finalPath"))

        val base = requestFor(files).toJsonObject()
        fun parsed(features: Any?) = TransferRequestPayload.fromJsonString(JSONObject(base.toString()).put("features", features).toString()).features
        assertEquals(emptyList<String>(), TransferRequestPayload.fromJsonString(base.toString()).features)
        assertEquals(emptyList<String>(), parsed("batch"))
        assertEquals(emptyList<String>(), parsed(7))
        assertEquals(emptyList<String>(), parsed(JSONObject().put("batch", true)))
        assertEquals(listOf("batch", "future"), parsed(JSONArray(listOf(1, "batch", JSONObject.NULL, true, "future"))))
    }

    @Test fun aRequestIsBoundedBeforeAnyoneApprovesIt() {
        val files = listOf<TestFile>("a.txt" to ByteArray(1))
        val base = requestFor(files)
        fun withFeatures(features: JSONArray) =
            TransferRequestPayload.fromJsonString(base.toJsonObject().put("features", features).toString())

        // At most 16 names of at most 32 bytes each.
        val sixteen = (0 until 16).map { "f$it" }
        assertEquals(sixteen, withFeatures(JSONArray(sixteen)).features)
        assertEquals(listOf("x".repeat(32)), withFeatures(JSONArray(listOf("x".repeat(32)))).features)
        assertThrows(IllegalArgumentException::class.java) { withFeatures(JSONArray((0 until 17).map { "f$it" })) }
        assertThrows(IllegalArgumentException::class.java) { withFeatures(JSONArray(listOf("x".repeat(33)))) }
        // Bytes, not characters.
        assertThrows(IllegalArgumentException::class.java) { withFeatures(JSONArray(listOf("é".repeat(17)))) }
        // A hostile list is refused without being copied.
        assertThrows(IllegalArgumentException::class.java) { withFeatures(JSONArray((0 until 100_000).map { 7 })) }

        ReceiveValidation.request(base.copy(senderId = "i".repeat(128), senderName = "n".repeat(256), senderPlatform = "p".repeat(64)))
        for (bad in listOf(
            base.copy(senderId = "i".repeat(129)),
            base.copy(senderName = "n".repeat(257)),
            base.copy(senderName = "é".repeat(129)),
            base.copy(senderPlatform = "p".repeat(65)),
            base.copy(features = (0 until 17).map { "f$it" }),
            base.copy(features = listOf("x".repeat(33))),
            requestFor(listOf("a".repeat(100) + "/".repeat(1) + "b".repeat(1000) to ByteArray(1)))
        )) {
            assertThrows(IllegalArgumentException::class.java) { ReceiveValidation.request(bad) }
        }

        // A reply is not rejected for the same thing; the excess is ignored.
        val reply = JSONObject().put("status", "ACCEPTED").put("features", JSONArray(listOf("batch", "x".repeat(33)) + (0 until 30).map { "f$it" }))
        val granted = TransferAcceptPayload.grantedFeatures(reply.toString().toByteArray())
        assertTrue(granted.size <= 16 && granted.first() == "batch" && "x".repeat(33) !in granted)
    }

    @Test fun acceptKeepsItsOriginalShapeUnlessFeaturesAreGranted() {
        val plain = TransferAcceptPayload(status = "ACCEPTED").toJsonObject()
        assertEquals(setOf("status", "resumeOffset"), plain.keySet())
        assertEquals("ACCEPTED", plain.getString("status"))
        assertEquals(0L, plain.getLong("resumeOffset"))

        val granting = TransferAcceptPayload(status = "ACCEPTED", features = BATCH).toJsonObject()
        assertEquals(setOf("status", "features"), granting.keySet())
        assertEquals("""["batch"]""", granting.getJSONArray("features").toString())

        fun granted(text: String) = TransferAcceptPayload.grantedFeatures(text.toByteArray())
        assertEquals(BATCH_AND_RESUME, granted("""{"status":"ACCEPTED","features":["batch","resume"]}"""))
        assertEquals(BATCH, granted(granting.toString()))
        // Original receivers: desktop sends text, Android sends JSON without features.
        assertEquals(emptyList<String>(), granted("ACCEPTED"))
        assertEquals(emptyList<String>(), granted(OLD_ACCEPT))
        assertEquals(emptyList<String>(), granted(""))
        assertEquals(emptyList<String>(), granted("""{"status":"ACCEPTED","features":"batch"}"""))
    }

    @Test fun resumeEntriesUseTheShortKeys() {
        val entry = ResumeEntry(fileIndex = 3, offset = 5_000_000_000L, sha256 = "ab".repeat(32))
        val json = entry.toJsonObject()
        assertEquals(setOf("i", "o", "h"), json.keySet())
        assertEquals(listOf(entry), ResumeEntry.listFromJsonString(JSONArray().put(json).toString()))
        assertEquals(emptyList<ResumeEntry>(), ResumeEntry.listFromJsonString("[]"))
        assertEquals(
            listOf(ResumeEntry(0, 10, "aa"), ResumeEntry(2, 20, "bb")),
            ResumeEntry.listFromJsonString("""[{"i":0,"o":10,"h":"aa"},{"i":2,"o":20,"h":"bb","extra":1}]""")
        )
        assertThrows(Exception::class.java) { ResumeEntry.listFromJsonString("""{"i":0}""") }
        assertThrows(Exception::class.java) { ResumeEntry.listFromJsonString("""[{"i":0,"o":10}]""") }
    }

    @Test fun approvalWaitSkipsKeepAlives() {
        val wire = ByteArrayOutputStream()
        FrameUtils.writeFrame(wire, FrameTag.HEARTBEAT, ByteArray(0))
        FrameUtils.writeFrame(wire, FrameTag.HEARTBEAT, "busy".toByteArray())
        FrameUtils.writeFrame(wire, FrameTag.TRANSFER_ACCEPT, "ACCEPTED".toByteArray())
        val phases = mutableListOf<Int>()
        val response = readAuthorizationResponse(wire.toByteArray().inputStream()) { phases += it }
        assertEquals(FrameTag.TRANSFER_ACCEPT, response.tag)
        assertEquals("ACCEPTED", String(response.payload))
        assertEquals(listOf(Protocol.APPROVAL_TIMEOUT_MS, Protocol.READ_TIMEOUT_MS), phases)
    }

    @Test fun storageFailuresTellTheSenderOnlyAShortReason() {
        val full = storageFailure(java.io.IOException("write failed: ENOSPC (No space left on device)"))
        assertEquals("not enough storage space", full.notice)
        assertEquals("Not enough storage space on this device", full.message)
        val wrapped = storageFailure(RuntimeException("wrapper", java.io.IOException("No space left on device")))
        assertEquals("not enough storage space", wrapped.notice)
        val other = storageFailure(java.io.IOException("Could not create '/storage/emulated/0/Download/DropFlow/a.txt'"))
        assertEquals("it could not save the files", other.notice)
        assertFalse(other.notice.contains("/"))
        assertEquals("abc", noticeText("a\u0000b\nc".toByteArray()))
        assertEquals(200, noticeText(ByteArray(5000) { 'x'.code.toByte() }).length)
    }
}
