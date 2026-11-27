package com.batteryscope

import org.json.JSONObject

/** Optional metadata in the existing JSON column. No schema or backup-format change. */
internal object ChargerLabelJson {
    const val KEY = "@charging_label_v1"
    fun encode(label: ChargerLabel): JSONObject = JSONObject()
        .put("name",label.name).put("source",label.source)
        .put("assignment",label.assignment).put("assigned_elapsed_ms",label.assignedElapsed)

    fun decode(raw: String?, plug: String, elapsed: Long): ChargerLabel? {
        if (raw == null || !raw.contains(KEY)) return null
        return try {
            val value = JSONObject(raw).optJSONObject(KEY) ?: return null
            val name = value.opt("name") as? String ?: return null
            val source = value.opt("source") as? String ?: return null
            val assignment = value.opt("assignment") as? String ?: return null
            val time = when (val v = value.opt("assigned_elapsed_ms")) {
                is Long -> v
                is Int -> v.toLong()
                else -> return null
            }
            ChargerLabel(name,source,assignment,time).takeIf { it.validAt(plug,elapsed) }
        } catch (_: Exception) { null }
    }
}
