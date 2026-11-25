package com.batteryscope

import android.content.Context
import android.widget.ScrollView
import android.widget.TextView

internal fun TextView.setTextIfChanged(value: String) {
    if (text.toString() != value) text = value
}

/** Preserve passive refresh position, while allowing ordinary touch/focus navigation. */
internal class DashboardScrollView(context: Context) : ScrollView(context) {
    private var preserveNextLayout = false

    fun refreshContent(update: () -> Unit) {
        val oldX = scrollX
        val oldY = scrollY
        update()
        if (isLayoutRequested) preserveNextLayout = true
        if (scrollX != oldX || scrollY != oldY) scrollTo(oldX,oldY)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val preserve = preserveNextLayout
        preserveNextLayout = false
        val oldX = scrollX
        val oldY = scrollY
        super.onLayout(changed,l,t,r,b)
        if (preserve) scrollTo(oldX,oldY)
    }
}

internal fun compactChargingStages(report: String): String {
    val ranges = listOf("0–20%:", "20–80%:", "80–100%:")
    val stageLines = report.substringAfter("=== CHARGING STAGES (LAST 30 DAYS) ===","")
        .substringBefore("Stage speeds use").lineSequence().map { it.trim() }.toList()
    return buildString {
        appendLine("Charging stages • last 30 days")
        ranges.forEachIndexed { index, range ->
            val rowIndex = stageLines.indexOfFirst { it.startsWith(range) }
            val row = stageLines.getOrNull(rowIndex) ?: "$range unavailable"
            val completed = if (rowIndex >= 0) stageLines.getOrNull(rowIndex+2)
                ?.takeIf { it.startsWith("Completed exact-boundary spans:") }
                ?.substringAfter(": ") else null
            append(row)
            if (completed != null && completed != "unavailable") append("\n  Complete spans: $completed")
            if (index != ranges.lastIndex) appendLine()
        }
    }
}
