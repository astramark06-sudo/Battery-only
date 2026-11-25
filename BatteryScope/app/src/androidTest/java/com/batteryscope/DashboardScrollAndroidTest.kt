package com.batteryscope

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class DashboardScrollAndroidTest {
    @Test fun passiveRefreshPreservesUserScrollAndDoesNotReplaceIdenticalText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var outcome: Result<Unit>? = null
        instrumentation.runOnMainSync {
            outcome = runCatching {
                val context = instrumentation.targetContext
                val scroll = DashboardScrollView(context)
                val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val summary = TextView(context).apply { text = "Initial"; setTextIsSelectable(false) }
                content.addView(summary,LinearLayout.LayoutParams(-1,-2))
                content.addView(View(context),LinearLayout.LayoutParams(-1,2400))
                scroll.addView(content)
                fun layout() {
                    scroll.measure(View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY))
                    scroll.layout(0,0,500,600)
                }
                layout()
                scroll.scrollTo(0,800)
                assertEquals(800,scroll.scrollY)
                val before = summary.text
                scroll.refreshContent { summary.setTextIfChanged("Initial") }
                assertSame(before,summary.text)
                scroll.refreshContent { summary.setTextIfChanged("Updated\nMore data\nThird line") }
                scroll.scrollTo(0,900)
                layout()
                assertEquals(900,scroll.scrollY)
                scroll.refreshContent { summary.setTextIfChanged("Short") }
                layout()
                assertEquals(900,scroll.scrollY)
                assertFalse(summary.isTextSelectable)
                scroll.scrollTo(0,100)
                assertEquals(100,scroll.scrollY)
            }
        }
        checkNotNull(outcome).getOrThrow()
    }

    @Test fun compactStagesKeepOnlyStageDataAndMarkMissingValues() {
        val report = "OTHER REPORT\n=== CHARGING STAGES (LAST 30 DAYS) ===\n" +
            "  0–20%: insufficient observations\n    Temperature: unavailable\n    Completed exact-boundary spans: unavailable\n" +
            "  20–80%: 45.00 pp/h • +60 points • 80.00 min observed\n    Temperature: 35.0 °C\n" +
            "    Completed exact-boundary spans: 80.00 to 80.00 min (1 samples)\nStage speeds use eligible intervals\n" +
            "Observed SOC-band speed\n  0–10%: 99 pp/h"
        val summary = compactChargingStages(report)
        assertTrue(summary.contains("0–20%: insufficient observations"))
        assertTrue(summary.contains("20–80%: 45.00 pp/h"))
        assertTrue(summary.contains("Complete spans: 80.00 to 80.00 min"))
        assertTrue(summary.contains("80–100%: unavailable"))
        assertFalse(summary.contains("0–10%"))
        assertFalse(summary.contains("OTHER REPORT"))
        assertTrue(compactChargingStages("History read failed").contains("0–20%: unavailable"))
    }
}
