package com.batteryscope

import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Exercise real Android ContentResolver file streams in an isolated cache directory. */
class HistoryOperationsAndroidTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private fun await(coordinator: HistoryOperationCoordinator): HistoryOperationCoordinator.State {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (coordinator.state.busy && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
        assertFalse("History worker timed out", coordinator.state.busy)
        return coordinator.state
    }

    @Test fun discoveryCopyFailureRetryAndExplicitDiscard() {
        val root = File(app.cacheDir,"stage-test-" + UUID.randomUUID()).apply { check(mkdir()) }
        val dir = File(root,"exports").apply { check(mkdir()) }
        val ready = "BatteryScope_History_123_" + "a".repeat(32) + ".csv"
        val legacy = "BatteryScope_Backup_20261125_123456_123.bsh"
        val partial = "BatteryScope_Backup_123_" + "b".repeat(32) + ".bsh.part"
        val content = ByteArray(100_003) { (it % 251).toByte() }
        val coordinator = HistoryOperationCoordinator { dir }
        try {
            File(dir,ready).writeBytes(content)
            File(dir,legacy).writeText("older")
            File(dir,partial).writeText("interrupted")
            File(dir,"unrelated.db").writeText("do not touch")
            assertTrue(coordinator.discover(app))
            val found = await(coordinator)
            assertEquals(listOf(ready),found.ready)
            assertEquals(listOf(legacy),found.legacy)
            assertEquals(listOf(partial),found.interrupted)
            assertTrue(coordinator.save(app,ready,Uri.fromFile(File(root,"missing/destination.csv"))))
            assertTrue(await(coordinator).message.contains("retained"))
            assertTrue(File(dir,ready).isFile)
            val destination = File(root,"destination.csv").apply { writeBytes(ByteArray(200_000)) }
            assertTrue(coordinator.save(app,ready,Uri.fromFile(destination)))
            assertTrue(await(coordinator).message.startsWith("Destination verified:"))
            assertArrayEquals(content,destination.readBytes())
            assertFalse(File(dir,ready).exists())
            val recovered = File(root,"older.bsh")
            assertTrue(coordinator.save(app,legacy,Uri.fromFile(recovered)))
            assertTrue(await(coordinator).message.contains("completeness is unknown"))
            assertEquals("older",recovered.readText())
            assertTrue(File(dir,legacy).isFile)
            val blocked = File(root,"blocked.bsh")
            assertTrue(coordinator.save(app,partial,Uri.fromFile(blocked)))
            assertTrue(await(coordinator).message.contains("cannot be exported"))
            assertFalse(blocked.exists())
            assertTrue(coordinator.discard(app,partial))
            assertTrue(await(coordinator).interrupted.isEmpty())
            assertFalse(File(dir,partial).exists())
            assertTrue(File(dir,legacy).exists())
            assertEquals("do not touch",File(dir,"unrelated.db").readText())
        } finally { await(coordinator); root.deleteRecursively() }
    }
}
