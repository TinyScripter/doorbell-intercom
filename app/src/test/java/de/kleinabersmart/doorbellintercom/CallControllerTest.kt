package de.kleinabersmart.doorbellintercom

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CallControllerTest {

    private lateinit var context: Context
    private lateinit var config: AppConfig
    private lateinit var scope: TestScope
    private lateinit var calls: CallController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        config = AppConfig(context)
        config.ringDurationSeconds = 30
        // Virtuelle Zeit: Timeout und Vorschaubild laufen erst mit advanceTimeBy/runCurrent.
        scope = TestScope()
        calls = CallController(context, scope)
    }

    private val state get() = calls.state.value
    private val ringing get() = state as CallState.Ringing

    private fun notificationIds() =
        context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.id }

    @Test
    fun ring_showsCallAndNotification() {
        calls.onRing("A", source = "MQTT")

        assertEquals("A", ringing.id)
        assertEquals("MQTT", ringing.source)
        assertTrue(CALL_NOTIFICATION in notificationIds())
        assertTrue(config.ringLog.first().endsWith("MQTT  Anruf angezeigt"))
    }

    @Test
    fun sameIdOverSecondPath_isIgnored_evenInOtherTimeZoneNotation() {
        calls.onRing("2026-11-04T17:24:06.519261+00:00", source = "Push")
        val first = ringing.seq

        calls.onRing("2026-11-04T18:24:06.519261+01:00", source = "MQTT")

        assertEquals(first, ringing.seq)
        assertTrue(config.ringLog.first().contains("ignoriert (Duplikat, ID"))
    }

    @Test
    fun differentIds_ringTwice_evenWithinTheTimeWindow() {
        calls.onRing("A", source = "Push")
        val first = ringing.seq

        calls.onRing("B", source = "Push")

        assertNotEquals(first, ringing.seq)
        assertEquals("B", ringing.id)
    }

    @Test
    fun withoutId_secondArrivalWithin15sIsIgnored_laterRingsAgain() {
        calls.onRing(null, source = "MQTT")
        val first = ringing.seq

        calls.onRing(null, source = "Push")
        assertEquals(first, ringing.seq)

        ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
        calls.onRing(null, source = "Push")
        assertNotEquals(first, ringing.seq)
    }

    /** Erst MQTT ohne ID (alte Automation), dann Push mit ID: dasselbe Klingeln. */
    @Test
    fun mixedIdAndNoId_withinWindow_isOneRing() {
        calls.onRing(null, source = "MQTT")
        val first = ringing.seq

        calls.onRing("A", source = "Push")
        assertEquals(first, ringing.seq)

        // Kommt dieselbe ID später nochmal, ist sie bekannt.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        calls.onRing("A", source = "Push")
        assertEquals(first, ringing.seq)
    }

    @Test
    fun accept_endsRingingAndStartsCall() {
        calls.onRing("A", source = "Push")

        assertTrue(calls.accept())

        val active = state as CallState.Active
        assertEquals("A", active.id)
        assertFalse(active.rangAgain)
        assertFalse(CALL_NOTIFICATION in notificationIds())
    }

    @Test
    fun accept_withoutRinging_doesNothing() {
        assertFalse(calls.accept())
        assertEquals(CallState.Idle, state)
    }

    @Test
    fun decline_endsRinging() {
        calls.onRing("A", source = "Push")

        calls.decline(CallController.SOURCE_NOTIFICATION)

        assertEquals(CallState.Idle, state)
        assertFalse(CALL_NOTIFICATION in notificationIds())
        assertTrue(config.ringLog.first().endsWith("Benachrichtigung  abgelehnt"))
    }

    @Test
    fun timeout_endsRingingAndShowsMissed() {
        calls.onRing("A", source = "Push")

        scope.advanceTimeBy(29_000)
        scope.runCurrent()
        assertTrue(state is CallState.Ringing)

        scope.advanceTimeBy(1_001)
        scope.runCurrent()
        assertEquals(CallState.Idle, state)
        assertTrue(MISSED_NOTIFICATION in notificationIds())
        assertTrue(config.ringLog.first().endsWith("Push  verpasst"))
    }

    @Test
    fun acceptedCall_doesNotTimeOut() {
        calls.onRing("A", source = "Push")
        calls.accept()

        scope.advanceTimeBy(60_000)
        scope.runCurrent()

        assertTrue(state is CallState.Active)
        assertFalse(MISSED_NOTIFICATION in notificationIds())
    }

    @Test
    fun ringDuringVisibleCall_onlyMarksRangAgain() {
        calls.onRing("A", source = "Push")
        calls.accept()
        calls.onCallScreenStarted()

        calls.onRing("B", source = "Push")

        val active = state as CallState.Active
        assertEquals("A", active.id)
        assertTrue(active.rangAgain)
        assertFalse(CALL_NOTIFICATION in notificationIds())
    }

    @Test
    fun ringDuringCallLeftInBackground_ringsAgain() {
        calls.onRing("A", source = "Push")
        calls.accept()
        calls.onCallScreenStarted()
        calls.onCallScreenStopped()

        calls.onRing("B", source = "Push")

        assertEquals("B", ringing.id)
    }

    @Test
    fun answeredElsewhere_endsMatchingRing() {
        calls.onRing("2026-11-04T17:24:06.519261+00:00", source = "Push")

        calls.answeredElsewhere("2026-11-04T18:24:06.519261+01:00", answeredBy = "Pixel")

        assertEquals(CallState.Idle, state)
    }

    @Test
    fun answeredElsewhere_forEarlierRing_isIgnored() {
        calls.onRing("A", source = "Push")
        calls.onRing("B", source = "Push")

        calls.answeredElsewhere("A", answeredBy = "Pixel")

        assertEquals("B", ringing.id)
    }

    @Test
    fun answeredElsewhere_withoutId_endsRing() {
        calls.onRing("A", source = "Push")

        calls.answeredElsewhere(null, answeredBy = null)

        assertEquals(CallState.Idle, state)
    }

    @Test
    fun doorOpened_endsRinging() {
        calls.onRing("A", source = "Push")

        calls.doorOpened(CallController.SOURCE_NOTIFICATION)

        assertEquals(CallState.Idle, state)
        assertTrue(config.ringLog.first().endsWith("Tür geöffnet"))
    }

    @Test
    fun leave_onlyEndsTheRingTheScreenShowed() {
        calls.onRing("A", source = "Push")
        val oldSeq = ringing.seq
        calls.onRing("B", source = "Push")

        calls.leave(oldSeq)
        assertEquals("B", ringing.id)

        calls.leave(ringing.seq)
        assertEquals(CallState.Idle, state)
    }

    // --- Anruf im System (TelecomBridge) ------------------------------------

    @Test
    fun systemCall_isKeptWhenAccepted() {
        calls.onRing("A", source = "Push")
        calls.onSystemCallAdded(ringing.seq)
        assertTrue(ringing.systemCall)

        calls.accept()

        assertTrue((state as CallState.Active).systemCall)
    }

    @Test
    fun acceptFromSystem_onlyForTheRingingSeq() {
        calls.onRing("A", source = "Push")
        val seq = ringing.seq

        assertFalse(calls.acceptFromSystem(seq + 1))
        assertTrue(calls.acceptFromSystem(seq))
        assertTrue(state is CallState.Active)
        assertTrue(config.ringLog.first().endsWith("System  angenommen"))
        // Schon angenommen: ein zweites Annehmen schlägt fehl (TelecomBridge lehnt dann ab).
        assertFalse(calls.acceptFromSystem(seq))
    }

    @Test
    fun endFromSystem_declinesRingingAndHangsUpCall_butNotANewerRing() {
        calls.onRing("A", source = "Push")
        val first = ringing.seq
        calls.endFromSystem(first)
        assertEquals(CallState.Idle, state)

        calls.onRing("B", source = "Push")
        calls.accept()
        calls.endFromSystem(first)
        assertTrue(state is CallState.Active)
        calls.endFromSystem(state.seq!!)
        assertEquals(CallState.Idle, state)
    }

    @Test
    fun setHeld_marksActiveCallAndLogsIt() {
        calls.onRing("A", source = "Push")
        calls.accept()
        val seq = state.seq!!

        calls.setHeld(seq, held = true)
        assertTrue((state as CallState.Active).held)
        assertTrue(config.ringLog.first().endsWith("System  gehalten"))

        calls.setHeld(seq, held = false)
        assertFalse((state as CallState.Active).held)
        assertTrue(config.ringLog.first().endsWith("System  fortgesetzt"))
    }

    @Test
    fun requestResume_onlyWhileHeld() = scope.runTest {
        val requests = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { calls.resumeRequests.toList(requests) }
        calls.onRing("A", source = "Push")
        calls.accept()
        val seq = state.seq!!

        calls.requestResume()
        calls.setHeld(seq, held = true)
        calls.requestResume()

        assertEquals(listOf(seq), requests)
    }

    @Test
    fun ringIdFromPayload() {
        assertEquals("x1", CallController.ringIdFromPayload("""{"type":"doorbell_ring","id":"x1"}"""))
        assertNull(CallController.ringIdFromPayload("ring"))
        assertNull(CallController.ringIdFromPayload("""{"type":"doorbell_ring"}"""))
        assertNull(CallController.ringIdFromPayload("""{"id":null}"""))
        assertNull(CallController.ringIdFromPayload("""{"id":"  "}"""))
    }

    @Test
    fun normalizeRingId() {
        val utc = CallController.normalizeRingId("2026-11-04T17:24:06.519261+00:00")
        assertEquals(utc, CallController.normalizeRingId("2026-11-04T18:24:06.519261+01:00"))
        assertEquals(utc, CallController.normalizeRingId("2026-11-04 17:24:06.519261+00:00"))
        assertEquals("ring-42", CallController.normalizeRingId("ring-42"))
    }

    private companion object {
        const val CALL_NOTIFICATION = 2
        const val MISSED_NOTIFICATION = 5
    }
}
