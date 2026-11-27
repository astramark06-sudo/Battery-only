package com.batteryscope

import android.content.Context
import android.view.MotionEvent
import android.widget.ScrollView
import android.widget.TextView

internal fun TextView.setTextIfChanged(value: String) {
    if (text.toString() != value) text = value
}

/** Preserve passive refresh position, while allowing ordinary touch/focus navigation. */
internal class DashboardScrollView(context: Context) : ScrollView(context) {
    private var preserveNextLayout = false
    private var restoredOffset: Int? = null
    private var restoreReady = false

    fun rememberRestoredOffset(y: Int) {
        restoredOffset = y.coerceAtLeast(0)
        restoreReady = false
    }

    fun offsetForSave(): Int = restoredOffset ?: scrollY

    fun restoreWhenOverviewReady() {
        if (restoredOffset != null) {
            restoreReady = true
            requestLayout()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // An explicit gesture takes precedence over a delayed recreation restore.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            restoredOffset = null
            restoreReady = false
        }
        return super.dispatchTouchEvent(event)
    }

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
        val restored = restoredOffset
        if (restoreReady && restored != null) {
            restoredOffset = null
            restoreReady = false
            scrollTo(0,restored)
        } else if (preserve) scrollTo(oldX,oldY)
    }
}

internal fun compactChargingStages(stages: List<ChargingProfile.Stage>): String = buildString {
    appendLine("Charging stages • last 30 days")
    listOf(0 to 20,20 to 80,80 to 100).forEachIndexed { index,(a,b) ->
        val stage = stages.singleOrNull { it.start == a && it.end == b }
        append("$a–$b%: ")
        append(stage?.rate?.let {
            String.format(java.util.Locale.US,"%.2f pp/h • %.2f min observed",it,stage.observedMs/60_000.0)
        } ?: "insufficient observations")
        stage?.exactText?.let { append("\n  Complete spans: $it") }
        stage?.boundedText?.let { append("\n  Bounded spans: $it") }
        if (index != 2) appendLine()
    }
}
