package io.github.carnoeng.folddesk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pomodoro rules must match the web app: focus → (auto) break → ready focus, a long break every n rounds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PomoTest {
    private val c: Context = ApplicationProvider.getApplicationContext()
    private fun state(mode: String, endAgo: Long, round: Int, auto: Boolean = true) = JSONObject().put("mode", mode).put("end", System.currentTimeMillis() - endAgo)
        .put("left", 0).put("full", 25 * 60_000L).put("round", round).put("task", "単元計画").put("at", 1L)
        .put("cfg", JSONObject().put("f", 25).put("s", 5).put("l", 15).put("n", 4).put("auto", auto))

    @Test fun focusEndsIntoBreak() {
        Pomo.set(c, state("focus", 1000, 0).toString())
        assertTrue(Pomo.due(c, true))
        val s = Pomo.get(c)
        assertEquals("short", s.getString("mode")); assertTrue(s.getLong("end") > System.currentTimeMillis())
        assertEquals(1, s.getJSONObject("log").getInt("n")); assertEquals(25, s.getJSONObject("log").getInt("min"))
        assertFalse(Pomo.due(c, true)) // not due again
    }

    @Test fun fourthRoundGivesLongBreakAndManualMode() {
        Pomo.set(c, state("focus", 10, 3, auto = false).put("at", 2L).toString())
        Pomo.due(c, true)
        val s = Pomo.get(c)
        assertEquals("long", s.getString("mode")); assertEquals(0L, s.getLong("end")); assertTrue(s.getBoolean("rdy")); assertEquals(15 * 60_000L, s.getLong("left"))
        Pomo.action(c, Pomo.A_GO); assertTrue(Pomo.get(c).getLong("end") > 0)
        Pomo.action(c, Pomo.A_PAUSE); assertEquals(0L, Pomo.get(c).getLong("end")); assertTrue(Pomo.get(c).getLong("left") > 0)
        Pomo.action(c, Pomo.A_STOP); assertEquals("idle", Pomo.get(c).getString("mode"))
    }

    @Test fun breakEndsIntoReadyFocus() {
        Pomo.set(c, state("short", 10, 1).put("at", 3L).toString())
        Pomo.due(c, true)
        val s = Pomo.get(c)
        assertEquals("focus", s.getString("mode")); assertTrue(s.getBoolean("rdy")); assertEquals(0L, s.getLong("end"))
    }

    @Test fun olderWebStateIsIgnored() {
        Pomo.set(c, state("focus", -60_000, 0).put("at", System.currentTimeMillis() + 100_000).toString())
        Pomo.set(c, state("idle", 0, 0).put("at", 5L).toString())
        assertEquals("focus", Pomo.get(c).getString("mode"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerTest {
    private val c: Context = ApplicationProvider.getApplicationContext()
    @Test fun countdownEndsAndPauses() {
        Timer.set(c, JSONObject().put("end", System.currentTimeMillis() + 60_000).put("left", 0).put("full", 60_000).put("at", 1).toString())
        assertFalse(Timer.due(c, true))
        Timer.action(c, Timer.A_PAUSE); assertEquals(0L, Timer.get(c).getLong("end")); assertTrue(Timer.get(c).getLong("left") > 50_000)
        Timer.action(c, Timer.A_RESUME); assertTrue(Timer.get(c).getLong("end") > System.currentTimeMillis())
        Timer.set(c, JSONObject().put("end", System.currentTimeMillis() - 10).put("full", 60_000).put("at", System.currentTimeMillis() + 5).toString())
        assertTrue(Timer.due(c, true)); assertTrue(Timer.get(c).getBoolean("done"))
    }
}
