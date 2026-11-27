package com.batteryscope

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Streaming byte-for-byte verification; caller owns and closes streams. */
internal object HistoryTransfer {
    data class Fingerprint(val bytes: Long, val sha256: String)

    enum class Stage { READY, LEGACY_UNVERIFIED, INTERRUPTED }
    data class StagedFile(val name: String, val stage: Stage)
    private val published = Regex("BatteryScope_(History_[0-9]{1,19}_[0-9a-f]{32}[.]csv|Backup_[0-9]{1,19}_[0-9a-f]{32}[.]bsh)")
    private val legacy = Regex("BatteryScope_(History_[0-9]{8}_[0-9]{6}_[0-9]{3}[.]csv|Backup_[0-9]{8}_[0-9]{6}_[0-9]{3}[.]bsh)")

    fun classify(name: String): Stage? = when {
        published.matches(name) -> Stage.READY
        legacy.matches(name) -> Stage.LEGACY_UNVERIFIED
        name.endsWith(".part") && published.matches(name.removeSuffix(".part")) -> Stage.INTERRUPTED
        else -> null
    }

    fun isReadyName(name: String): Boolean = classify(name) == Stage.READY
    fun isSaveName(name: String): Boolean = classify(name) in setOf(Stage.READY, Stage.LEGACY_UNVERIFIED)

    /** Exact app-generated names, regular private files, never follow a redirected path. */
    fun resolve(directory: File, name: String): File {
        require(classify(name) != null) { "Unrecognized staging filename" }
        val parent = directory.canonicalFile
        val file = File(parent, name)
        require(file.isFile && file.canonicalFile == file) { "Staged file missing or redirected" }
        return file
    }

    fun scan(directory: File): List<StagedFile> {
        if (!directory.exists()) return emptyList()
        check(directory.isDirectory) { "Private export directory is not a directory" }
        val entries = directory.listFiles() ?: error("Cannot list private export directory")
        return entries.sortedByDescending { it.lastModified() }.mapNotNull { file ->
            val stage = classify(file.name) ?: return@mapNotNull null
            if (runCatching { resolve(directory, file.name) }.isFailure) null
            else StagedFile(file.name, stage)
        }
    }

    fun fingerprint(input: InputStream, output: OutputStream? = null): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            check(n > 0) { "Stream made no progress" }
            total = Math.addExact(total, n.toLong())
            digest.update(buffer, 0, n)
            output?.write(buffer, 0, n)
        }
        return Fingerprint(total, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    fun requireMatch(expected: Fingerprint, actual: Fingerprint) {
        check(expected == actual) { "Destination size or SHA-256 differs from prepared copy" }
    }
}
