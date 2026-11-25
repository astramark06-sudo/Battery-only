package com.batteryscope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/** Displays only retained observations. Never draws interpolated values or connects segments. */
internal class ChargingCurvePanel(context: Context) : LinearLayout(context) {
    private val description = label()
    private val next = Button(context)
    private val socLabel = label()
    private val socPlot = DotPlot(context, Color.rgb(142,226,185))
    private val tempLabel = label()
    private val tempPlot = DotPlot(context, Color.rgb(255,190,120))
    private val details = label()
    private val detailsToggle = Button(context)
    private var detailsExpanded = false
    private var curves = emptyList<ChargingProfile.Curve>()
    private var selected = 0

    private fun dp(n: Int) = (resources.displayMetrics.density*n+0.5f).toInt()
    private fun label() = TextView(context).apply {
        textSize = 14f
        setTextColor(Color.rgb(230,237,247))
        setPadding(dp(8),dp(6),dp(8),dp(6))
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(20,29,43))
        addView(label().apply { text = "RECENT CHARGING OBSERVATIONS • LAST 30 DAYS" })
        addView(label().apply { text = "Up to 8 newest + 8 longest earlier observed segments. Shorter older segments may be omitted; see report for coverage." })
        addView(description)
        next.text = "Show earlier segment"
        next.setOnClickListener {
            if (curves.isNotEmpty()) {
                selected = (selected+1)%curves.size
                render()
            }
        }
        addView(next)
        addView(socLabel)
        addView(socPlot,LayoutParams(-1,dp(120)))
        addView(tempLabel)
        addView(tempPlot,LayoutParams(-1,dp(120)))
        detailsToggle.text = "Show chart checkpoints"
        detailsToggle.setOnClickListener {
            detailsExpanded = !detailsExpanded
            render()
        }
        addView(detailsToggle)
        addView(details)
        details.setTextIsSelectable(false)
        render()
    }

    fun setCurves(value: List<ChargingProfile.Curve>) {
        val prior = if (selected == 0) null else curves.getOrNull(selected)?.first
        curves = value
        selected = if (prior == null) 0 else curves.indexOfFirst { it.first == prior }.coerceAtLeast(0)
        render()
    }

    private fun render() {
        val c = curves.getOrNull(selected)
        next.isEnabled = curves.size > 1
        next.setTextIfChanged(if (selected+1 < curves.size) "Show earlier segment" else "Show newest segment")
        detailsToggle.setTextIfChanged(if (detailsExpanded) "Hide chart checkpoints" else "Show chart checkpoints")
        if (c == null) {
            description.setTextIfChanged("No eligible recorded charging segments.")
            listOf(socLabel,socPlot,tempLabel,tempPlot,details,detailsToggle).forEach { it.visibility = View.GONE }
            return
        }
        listOf(socLabel,socPlot,tempLabel,detailsToggle).forEach { it.visibility = View.VISIBLE }
        details.visibility = if (detailsExpanded) View.VISIBLE else View.GONE
        val points = c.points()
        val span = c.last.elapsed-c.first.elapsed
        val start = c.first.wallMs?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "unknown wall time"
        description.setTextIfChanged(String.format(Locale.US,
            "Segment %d of %d (newest first) • %s • %s\n%.2f observed minutes • %d of %d checkpoints shown. Separate segments may have gaps; not total connected time.",
            selected+1,curves.size,c.first.plug,start,span/60_000.0,points.size,c.count))
        fun x(s: ChargingProfile.Sample): Float =
            if (span > 0L) ((s.elapsed-c.first.elapsed).toDouble()/span).toFloat() else 0.5f
        socLabel.setTextIfChanged("Battery level • bottom 0%, top 100% • elapsed time →")
        socPlot.setDots(points.mapNotNull { s -> s.percent?.let { x(s) to it/100f } })
        val temperatures = points.mapNotNull { s ->
            s.temperatureC?.takeIf { it in -100.0..100.0 }?.let { x(s) to it }
        }
        val fullTemperature = "All observed segment temperatures: " + c.observedTemperatureText() +
            " (before thinning)"
        if (temperatures.isEmpty()) {
            tempLabel.setTextIfChanged("Battery temperature • no usable retained readings\n" + fullTemperature)
            tempPlot.visibility = View.GONE
            tempPlot.setDots(emptyList())
        } else {
            // Scale against every usable segment observation, not only retained dots.
            val bounds = checkNotNull(c.observedTemperatureBounds())
            val lo = floor(bounds.first/5.0)*5.0
            val hi = maxOf(lo+5.0,ceil(bounds.second/5.0)*5.0)
            tempLabel.setTextIfChanged(String.format(Locale.US,"Battery temperature • bottom %.0f °C, top %.0f °C • elapsed time →",lo,hi) +
                "\n" + fullTemperature)
            tempPlot.visibility = View.VISIBLE
            tempPlot.setDots(temperatures.map { it.first to ((it.second-lo)/(hi-lo)).toFloat() })
        }
        details.setTextIfChanged(buildString {
            appendLine("Dots are actual retained samples; no interpolation.")
            appendLine("Elapsed min | SOC | Battery temperature")
            points.forEach { s ->
                val temp = s.temperatureC?.takeIf { it in -100.0..100.0 }?.let {
                    String.format(Locale.US,"%.1f °C",it)
                } ?: "unavailable"
                appendLine(String.format(Locale.US,"%.2f | %d%% | %s",(s.elapsed-c.first.elapsed)/60_000.0,s.percent,temp))
            }
        })
    }

    private class DotPlot(context: Context, private val color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var dots = emptyList<Pair<Float,Float>>()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun setDots(values: List<Pair<Float,Float>>) {
            if (dots == values) return
            dots = values
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val margin = 8f*resources.displayMetrics.density
            val w = (width-2*margin).coerceAtLeast(0f)
            val h = (height-2*margin).coerceAtLeast(0f)
            paint.color = Color.rgb(65,79,96)
            paint.strokeWidth = resources.displayMetrics.density
            for (i in 0..4) {
                val y = margin+h*i/4f
                val x = margin+w*i/4f
                canvas.drawLine(margin,y,margin+w,y,paint)
                canvas.drawLine(x,margin,x,margin+h,paint)
            }
            paint.color = color
            for (dot in dots) canvas.drawCircle(margin+w*dot.first,margin+h*(1f-dot.second),
                3f*resources.displayMetrics.density,paint)
        }
    }
}
