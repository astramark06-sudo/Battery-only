package com.batteryscope

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual Activity integration; run on disposable CI emulator, never user history. */
class MainActivityAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (main(condition)) return
            SystemClock.sleep(50L)
        }
        fail("Timed out: " + description)
    }

    @Test fun actualDashboardRetainsScrollAndSettingsAcrossRefreshAndRecreationWhilePaused() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(BatteryRecorderService.PREFS,Context.MODE_PRIVATE)
        val prior = RecordingPreference.isPaused(prefs)
        var old: MainActivity? = null
        val recreated = AtomicReference<MainActivity?>()
        var callbacks: Application.ActivityLifecycleCallbacks? = null
        try {
            RecordingPreference.setPaused(prefs,true)
            context.stopService(Intent(context,BatteryRecorderService::class.java))
            val activity = instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            old = activity
            await("initial overview") {
                val stage = activity.window.decorView.findViewWithTag<TextView>("dashboard_stages")
                stage != null && !stage.text.contains("Waiting for history")
            }
            val offset = main {
                val root = activity.window.decorView
                val button = root.findViewWithTag<Button>("dashboard_tools_toggle")
                assertTrue(button.performClick())
                assertEquals(View.VISIBLE,root.findViewWithTag<View>("dashboard_tools_content").visibility)
                assertTrue(root.findViewWithTag<TextView>("dashboard_recorder_health").text.contains("Paused by user"))
                assertFalse(BatteryRecorderService.isRecorderLive())
                300
            }
            instrumentation.waitForIdleSync()
            main {
                val scroll = activity.window.decorView.findViewWithTag<DashboardScrollView>("dashboard_scroll")
                scroll.scrollTo(0,offset)
                assertEquals(offset,scroll.scrollY)
            }
            SystemClock.sleep(4_300L) // At least two ordinary two-second refresh opportunities.
            main {
                val scroll = activity.window.decorView.findViewWithTag<DashboardScrollView>("dashboard_scroll")
                assertEquals(offset,scroll.scrollY)
                assertFalse(BatteryRecorderService.isRecorderLive())
            }
            val latch = CountDownLatch(1)
            val observer = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity,b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityResumed(a: Activity) {
                    if (a is MainActivity && a !== activity) { recreated.set(a); latch.countDown() }
                }
                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity,b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            }
            callbacks = observer
            main { activity.application.registerActivityLifecycleCallbacks(observer); activity.recreate() }
            assertTrue("Activity recreation",latch.await(20,TimeUnit.SECONDS))
            val next = checkNotNull(recreated.get())
            await("restored overview and scroll") {
                val root = next.window.decorView
                root.findViewWithTag<View>("dashboard_tools_content")?.visibility == View.VISIBLE &&
                    root.findViewWithTag<DashboardScrollView>("dashboard_scroll")?.scrollY == offset
            }
            main {
                assertTrue(RecordingPreference.isPaused(prefs))
                assertFalse(BatteryRecorderService.isRecorderLive())
            }
        } finally {
            main {
                callbacks?.let { old?.application?.unregisterActivityLifecycleCallbacks(it) }
                recreated.get()?.finish()
                old?.finish()
            }
            instrumentation.waitForIdleSync()
            RecordingPreference.setPaused(prefs,prior)
        }
    }
}
