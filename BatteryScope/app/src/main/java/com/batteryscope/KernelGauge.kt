package com.batteryscope

internal data class GaugeSnapshot(val source: String, val fullRaw: String?, val designRaw: String?, val cyclesRaw: String?) {
    val fullUah: Long? get() = fullRaw?.toLongOrNull()?.takeIf { it > 0L }
    val designUah: Long? get() = designRaw?.toLongOrNull()?.takeIf { it > 0L }
    val cycles: Long? get() = cyclesRaw?.toLongOrNull()?.takeIf { it >= 0L }
    val healthPercent: Double? get() {
        val full = fullUah ?: return null
        val design = designUah ?: return null
        return full.toDouble() / design.toDouble() * 100.0
    }
}

/** Never form a health ratio from fields belonging to different supplies. */
internal object KernelGauge {
    private val knownNames = setOf("battery", "bms", "main")
    fun select(fields: Map<String, String>, rootPrefix: String = ""): GaugeSnapshot? {
        val sources = fields.keys.asSequence().filter { it.startsWith(rootPrefix) }
            .map { it.removePrefix(rootPrefix) }
            .filter { it.count { c -> c == '/' } == 1 && !it.startsWith("@") }
            .map { it.substringBefore('/') }.distinct()
        return sources.filter { name ->
            name.lowercase(java.util.Locale.US) in knownNames ||
                fields[rootPrefix + name + "/type"]?.equals("Battery", ignoreCase = true) == true
        }.map { name ->
            val p = rootPrefix + name
            GaugeSnapshot(p, fields["$p/charge_full"], fields["$p/charge_full_design"], fields["$p/cycle_count"])
        }.filter { it.fullRaw != null || it.designRaw != null || it.cyclesRaw != null }
            .sortedWith(compareByDescending<GaugeSnapshot> { it.healthPercent != null }
                .thenByDescending { listOf(it.fullUah, it.designUah, it.cycles).count { v -> v != null } }
                .thenBy { it.source }).firstOrNull()
    }
}
