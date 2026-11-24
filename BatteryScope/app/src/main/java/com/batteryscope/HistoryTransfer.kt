package com.batteryscope

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Streaming byte-for-byte verification; caller owns and closes streams. */
internal object HistoryTransfer {
    data class Fingerprint(val bytes: Long, val sha256: String)

    fun isReadyName(name: String): Boolean =
        name.matches(Regex("BatteryScope_(History_[0-9a-f_]+[.]csv|Backup_[0-9a-f_]+[.]bsh)"))

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
