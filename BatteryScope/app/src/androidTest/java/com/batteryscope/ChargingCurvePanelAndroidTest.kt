package com.batteryscope

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real Android view and Canvas without touching production history. */
class ChargingCurvePanelAndroidTest {
    private fun sample(elapsed: Long, percent: Int, temp: Double?) =
        ChargingProfile.Sample(elapsed,9,percent,"CHARGING","AC",3,-560L,temp,
            null,null,null,1_000_000L+elapsed)

    @Test fun plotsActualSamplesAndHandlesSelectionAndEmptyData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var outcome: Result<Unit>? = null
        instrumentation.runOnMainSync {
            outcome = runCatching {
                val panel = ChargingCurvePanel(instrumentation.targetContext)
                val children = (0 until panel.childCount).map { panel.getChildAt(it) }
                val button = children.filterIsInstance<Button>().single()
                val plots = children.filterNot { it is TextView }
                assertEquals(2,plots.size)
                fun labels() = children.filterIsInstance<TextView>()
                    .filter { it.visibility == View.VISIBLE }.joinToString("\n") { it.text.toString() }
                fun layoutPanel() {
                    panel.measure(View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                    panel.layout(0,0,panel.measuredWidth,panel.measuredHeight)
                }
                fun containsColor(view: View, color: Int): Boolean {
                    assertTrue(view.width > 0 && view.height > 0)
                    val bitmap = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))
                        val pixels = IntArray(bitmap.width*bitmap.height)
                        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                        return pixels.any { it == color }
                    } finally { bitmap.recycle() }
                }

                assertFalse(button.isEnabled)
                assertTrue(labels().contains("No eligible recorded charging segments"))
                assertTrue(plots.all { it.visibility == View.GONE })

                val older = ChargingProfile.Curve(sample(1000,20,null)).apply {
                    add(sample(31_000,21,null))
                }
                val newer = ChargingProfile.Curve(sample(200_000,70,35.0)).apply {
                    add(sample(230_000,71,36.0))
                }
                panel.setCurves(listOf(newer,older))
                layoutPanel()
                assertTrue(button.isEnabled)
                assertTrue(labels().contains("Segment 1 of 2"))
                assertTrue(labels().contains("0.50 | 71% | 36.0"))
                assertTrue(plots.all { it.visibility == View.VISIBLE })
                assertTrue(containsColor(plots[0],Color.rgb(142,226,185)))
                assertTrue(containsColor(plots[1],Color.rgb(255,190,120)))

                assertTrue(button.performClick())
                assertTrue(labels().contains("Segment 2 of 2"))
                assertTrue(labels().contains("0.50 | 21% | unavailable"))
                assertTrue(labels().contains("no usable retained readings"))
                assertEquals(View.GONE,plots[1].visibility)

                // Retain the selected older segment across refresh with a newer segment.
                val newest = ChargingProfile.Curve(sample(400_000,90,37.0))
                panel.setCurves(listOf(newest,newer,older))
                assertTrue(labels().contains("Segment 3 of 3"))
                assertTrue(button.performClick())
                layoutPanel()
                assertTrue(labels().contains("Segment 1 of 3"))
                assertTrue(labels().contains("0.00 | 90% | 37.0"))
                assertTrue(containsColor(plots[0],Color.rgb(142,226,185)))
                assertTrue(containsColor(plots[1],Color.rgb(255,190,120)))

                panel.setCurves(emptyList())
                assertFalse(button.isEnabled)
                assertTrue(plots.all { it.visibility == View.GONE })
                assertFalse(labels().contains("90%"))
            }
        }
        checkNotNull(outcome).getOrThrow()
    }
}
