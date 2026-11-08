package com.dropflow.android.nearby

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Fixed synthetic exchange, never a production file-transfer endpoint. */
internal object NearbyLabWire {
    const val PORT = 47638
    private val magic = "DFNLAB01".encodeToByteArray()
    private const val BLOCK_SIZE = 64 * 1024
    private const val BLOCK_COUNT = 256

    internal fun pattern(seed: Int): ByteArray =
        ByteArray(BLOCK_SIZE) { (seed + (it xor (it ushr 8))).toByte() }

    fun exchange(input: InputStream, output: OutputStream) {
        val reader = DataInputStream(input)
        val outgoing = pattern(0x19)
        val sent = MessageDigest.getInstance("SHA-256")
        output.write(magic)
        repeat(BLOCK_COUNT) {
            output.write(outgoing)
            sent.update(outgoing)
        }
        output.flush()

        val expected = pattern(0xa2)
        val block = ByteArray(BLOCK_SIZE)
        val received = MessageDigest.getInstance("SHA-256")
        repeat(BLOCK_COUNT) {
            reader.readFully(block)
            check(block.contentEquals(expected)) { "Invalid synthetic payload" }
            received.update(block)
        }
        output.write(received.digest())
        output.flush()
        val receipt = ByteArray(32)
        reader.readFully(receipt)
        check(receipt.contentEquals(sent.digest())) { "Invalid Windows receipt" }
    }
}
