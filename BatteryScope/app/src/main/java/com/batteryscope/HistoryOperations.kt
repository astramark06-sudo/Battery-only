package com.batteryscope

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** One process-wide history worker, independent of Activity recreation. No Activity references. */
internal class HistoryOperationCoordinator(
    private val directory: (Context) -> File = { File(it.filesDir, "exports") }
) {
    data class State(
        val busy: Boolean = false,
        val message: String = "No history operation in progress.",
        val ready: List<String> = emptyList(),
        val legacy: List<String> = emptyList(),
        val interrupted: List<String> = emptyList(),
        val restoreRevision: Long = 0L
    )
    private data class Result(val message: String, val restored: Boolean = false)
    @Volatile var state = State()
        private set
    private var discovered = false

    private fun scan(app: Context) = HistoryTransfer.scan(directory(app))

    @Synchronized private fun start(app: Context, message: String, work: (Context) -> Result): Boolean {
        if (state.busy) return false
        val context = app.applicationContext
        state = state.copy(busy = true, message = message)
        try {
            Thread({
                var result = Result("History operation interrupted; inspect prepared copies and local history.")
                try {
                    result = work(context)
                } catch (e: Exception) {
                    result = Result("History operation failed: " + e.javaClass.simpleName + ": " + (e.message ?: "unknown"))
                } finally {
                    val staged = runCatching { scan(context) }.getOrNull()
                    synchronized(this) {
                        fun names(stage: HistoryTransfer.Stage, fallback: List<String>) =
                            staged?.filter { it.stage == stage }?.map { it.name } ?: fallback
                        state = State(
                            busy = false,
                            message = result.message + if (staged == null) " Private staging list could not be refreshed." else "",
                            ready = names(HistoryTransfer.Stage.READY, state.ready),
                            legacy = names(HistoryTransfer.Stage.LEGACY_UNVERIFIED, state.legacy),
                            interrupted = names(HistoryTransfer.Stage.INTERRUPTED, state.interrupted),
                            restoreRevision = state.restoreRevision + if (result.restored) 1L else 0L
                        )
                        discovered = staged != null
                    }
                }
            }, "BatteryScopeHistoryOperation").start()
            return true
        } catch (e: Exception) {
            state = state.copy(busy = false, message = "Could not start history worker: " + e.javaClass.simpleName)
            return false
        }
    }

    @Synchronized fun discover(app: Context, force: Boolean = false): Boolean {
        if (state.busy || (discovered && !force)) return false
        val previousMessage = state.message
        return start(app, "Checking private prepared copies…") {
            Result(previousMessage)
        }
    }

    fun prepare(app: Context, backup: Boolean): Boolean = start(app, "Preparing private history export…") { context ->
        val dir = directory(context)
        require(dir.isDirectory || dir.mkdirs()) { "Could not create private export directory" }
        val id = System.currentTimeMillis().toString() + "_" + UUID.randomUUID().toString().replace("-", "")
        val name = if (backup) "BatteryScope_Backup_" + id + ".bsh" else "BatteryScope_History_" + id + ".csv"
        val finalFile = File(dir, name)
        val partial = File(dir, name + ".part")
        require(partial.createNewFile()) { "Could not reserve staging file" }
        try {
            val database = RecorderDatabase(context)
            val records = try {
                FileOutputStream(partial).use { stream ->
                    val count = if (backup) database.exportBackup(stream).total else {
                        val writer = stream.bufferedWriter(Charsets.UTF_8)
                        database.exportCsv(writer).also { writer.flush() }
                    }
                    stream.fd.sync()
                    count
                }
            } finally { database.close() }
            if (records == 0L) Result("No telemetry history exists to export yet.")
            else {
                check(!finalFile.exists() && partial.renameTo(finalFile)) { "Could not publish prepared copy" }
                Result("Prepared " + records + " records. Use Save / retry prepared export to choose a destination.")
            }
        } finally { partial.delete() }
    }

    fun save(app: Context, name: String, destination: Uri): Boolean = start(app, "Copying and verifying destination…") { context ->
        require(HistoryTransfer.isSaveName(name)) { "Interrupted or unknown files cannot be exported" }
        val source = HistoryTransfer.resolve(directory(context), name)
        val legacy = HistoryTransfer.classify(name) == HistoryTransfer.Stage.LEGACY_UNVERIFIED
        val resolver = context.contentResolver
        val expected = try {
            source.inputStream().use { input ->
                val output = resolver.openOutputStream(destination, "wt") ?: error("Cannot open destination")
                output.use { HistoryTransfer.fingerprint(input, it) }
            }
        } catch (e: Exception) {
            return@start Result("Destination copy failed: " + e.javaClass.simpleName + ". The destination may be incomplete. Prepared copy retained for retry.")
        }
        val actual = try {
            val input = resolver.openInputStream(destination) ?: error("Destination cannot be read back")
            input.use { HistoryTransfer.fingerprint(it) }
        } catch (e: Exception) {
            return@start Result("Copy closed, but read-back verification failed: " + e.javaClass.simpleName + ". Prepared copy retained; inspect the destination before relying on it.")
        }
        if (expected != actual) return@start Result("Destination size or SHA-256 differs. Prepared copy retained; do not rely on the destination.")
        if (legacy) return@start Result("Older staging copy transferred byte-for-byte (" + expected.bytes +
            " bytes; SHA-256 " + expected.sha256 + "). Original completeness is unknown; private copy retained.")
        val removed = source.delete()
        Result("Destination verified: " + expected.bytes + " bytes; SHA-256 " + expected.sha256 +
            if (removed) ". Prepared copy removed." else ". Verified, but private prepared copy remains.")
    }

    fun discard(app: Context, name: String): Boolean = start(app, "Discarding selected prepared copy…") { context ->
        check(HistoryTransfer.resolve(directory(context), name).delete()) { "Could not discard selected staging copy" }
        Result("Prepared copy discarded. Local telemetry history unchanged.")
    }

    fun restore(app: Context, uri: Uri, legacy: Boolean, exactOverlap: Boolean = false): Boolean = start(app, "Validating and restoring earlier history…") { context ->
        require(!legacy || !exactOverlap) { "Exact overlap recovery requires a portable backup" }
        val file = File.createTempFile("restore_", ".bsh", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot read selected backup")
            input.use { source -> file.outputStream().use { output ->
                HistoryBackup.stage(source,output) { written,next ->
                    // Reserve space for the staged chunk and estimated transactional restore.
                    val required = next.toLong() + 2L * (written + next) + 16L * 1024 * 1024
                    require(minOf(context.cacheDir.usableSpace,context.filesDir.usableSpace) >= required) {
                        "Insufficient free space while staging backup; existing history unchanged"
                    }
                }
            } }
            val estimatedRequired = 2L * file.length() + 16L * 1024 * 1024
            require(context.filesDir.usableSpace >= estimatedRequired) {
                "Insufficient free internal storage for restore (estimated requirement " + estimatedRequired + " bytes)"
            }
            val database = RecorderDatabase(context)
            try {
                if (exactOverlap) {
                    val recovered = database.recoverEarlierWithExactOverlap(file)
                    Result("Restored " + recovered.inserted.samples + " samples and " + recovered.inserted.events +
                        " events; matched and retained " + recovered.matched.total +
                        " existing records in sequence. Existing history preserved.", recovered.inserted.total > 0L)
                } else {
                    val result = if (legacy) database.restoreLegacyCsv(file) else database.restoreBackup(file)
                    Result("Restored " + result.samples + " samples and " + result.events + " events. Existing records preserved.", true)
                }
            } finally { database.close() }
        } finally { file.delete() }
    }
}

/** Production singleton; isolated instances are used only for disposable tests. */
internal val HistoryOperations = HistoryOperationCoordinator()
