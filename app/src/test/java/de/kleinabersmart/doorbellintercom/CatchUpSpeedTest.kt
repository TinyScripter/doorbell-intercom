package de.kleinabersmart.doorbellintercom

import org.junit.Assert.assertEquals
import org.junit.Test

class CatchUpSpeedTest {

    private fun speed(lagMs: Long, current: Float = 1f) = DirectRtspClient.catchUpSpeed(lagMs, current)

    @Test
    fun smallLag_staysAtNormalSpeed() {
        assertEquals(1f, speed(lagMs = 100))
    }

    @Test
    fun lagAboveStart_speedsUp() {
        assertEquals(1.1f, speed(lagMs = 251))
    }

    @Test
    fun largeLag_speedsUpFaster() {
        assertEquals(1.25f, speed(lagMs = 501))
        assertEquals(1.25f, speed(lagMs = 5000, current = 1.1f))
    }

    /** Hysterese: zwischen Stopp- und Startschwelle bleibt die bisherige Geschwindigkeit. */
    @Test
    fun betweenStopAndStart_keepsCurrentSpeed() {
        assertEquals(1.1f, speed(lagMs = 100, current = 1.1f))
        assertEquals(1f, speed(lagMs = 100, current = 1f))
        assertEquals(1.25f, speed(lagMs = 250, current = 1.25f))
    }

    @Test
    fun targetReached_returnsToNormal() {
        assertEquals(1f, speed(lagMs = 50, current = 1.25f))
        assertEquals(1f, speed(lagMs = -300, current = 1.1f))
    }
}
