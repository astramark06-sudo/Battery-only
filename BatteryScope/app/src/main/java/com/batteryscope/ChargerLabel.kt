package com.batteryscope

import java.util.UUID

/** Explicit user-entered label, not an electrically detected charger identity. */
internal data class ChargerLabel(val name: String, val source: String, val assignment: String, val assignedElapsed: Long) {
    fun validAt(plug: String, elapsed: Long): Boolean =
        normalizedName(name) == name && knownSource(source) && source == plug &&
        assignedElapsed >= 0L && assignedElapsed <= elapsed &&
        assignment.matches(assignmentPattern)

    companion object {
        private val assignmentPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val knownSources = setOf("AC","USB","WIRELESS","DOCK")
        fun knownSource(source: String): Boolean = source in knownSources
        fun normalizedName(raw: String): String? = raw.trim().takeIf {
            it.length in 1..40 && it.all { c -> c.isLetterOrDigit() || c in " -_.()" }
        }
    }
}

/** Process-local scope: no label survives a recorded connection boundary or service restart. */
internal class ChargerLabelTracker {
    private var source = "UNKNOWN"
    private var label: ChargerLabel? = null

    @Synchronized fun observeSource(observed: String) {
        if (observed != source || !ChargerLabel.knownSource(observed)) label = null
        source = observed
    }

    @Synchronized fun boundary() {
        label = null
        source = "UNKNOWN"
    }

    @Synchronized fun assign(raw: String, elapsed: Long): ChargerLabel? {
        val name = ChargerLabel.normalizedName(raw) ?: return null
        if (!ChargerLabel.knownSource(source) || elapsed < 0L) return null
        return ChargerLabel(name,source,UUID.randomUUID().toString(),elapsed).also { label = it }
    }

    @Synchronized fun clear() { label = null }
    @Synchronized fun current(): ChargerLabel? = label

    // Never retrospectively label samples whose acquisition started before assignment.
    @Synchronized fun snapshot(plug: String, sampleStarted: Long): ChargerLabel? =
        label?.takeIf { it.validAt(plug,sampleStarted) && source == plug }
}
