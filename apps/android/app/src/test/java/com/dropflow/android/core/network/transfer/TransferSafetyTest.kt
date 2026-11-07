package com.dropflow.android.core.network.transfer

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferSafetyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun nestedPathsArePreservedAndTraversalIsRejected() {
        assertEquals(listOf("Photos", "2026", "വീട്.txt"), ReceivePath.components("Photos/2026/വീട്.txt"))
        for (name in listOf("../outside", "/absolute", "folder/../outside", "folder//name", "C:/bad",
            "folder\\bad", "folder/ .. ", "folder\u0000/file", "a".repeat(241))) {
            assertThrows(IllegalArgumentException::class.java) { ReceivePath.components(name) }
        }
    }

    @Test fun competingOrStaleTransferCannotReleaseOwnersSlot() {
        val slot = TransferSlot()
        assertTrue(slot.claim("first"))
        assertFalse(slot.claim("busy"))
        slot.release("busy")
        assertEquals("first", slot.activeId)
        slot.release("first")
        assertTrue(slot.claim("second"))
        slot.release("first")
        assertEquals("second", slot.activeId)
    }

    @Test fun legacyPublicationNeverReplacesExistingOrConcurrentFiles() {
        val directory = folder.newFolder()
        val original = File(directory, "report.txt").apply { writeText("existing") }
        val first = File(directory, ".first.part").apply { writeText("first") }
        val second = File(directory, ".second.part").apply { writeText("second") }
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first, second).map { stage ->
                workers.submit(Callable { start.await(); LegacyPublication.publish(stage, "report.txt") })
            }
            start.countDown()
            val outputs = results.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(setOf("report (1).txt", "report (2).txt"), outputs.map { it.name }.toSet())
            assertEquals(setOf("first", "second"), outputs.map { it.readText() }.toSet())
            assertEquals("existing", original.readText())
        } finally {
            workers.shutdownNow()
        }
    }
}
