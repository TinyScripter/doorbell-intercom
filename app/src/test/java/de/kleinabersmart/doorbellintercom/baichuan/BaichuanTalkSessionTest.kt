package de.kleinabersmart.doorbellintercom.baichuan

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BaichuanTalkSessionTest {

    @Test
    fun applyGain_amplifiesOnlyTheFirstCountSamples() {
        val samples = shortArrayOf(100, -200, 300)
        BaichuanTalkSession.applyGain(samples, count = 2, gain = 2f)
        assertArrayEquals(shortArrayOf(200, -400, 300), samples)
    }

    @Test
    fun applyGain_clipsToShortRange() {
        val samples = shortArrayOf(20_000, -20_000)
        BaichuanTalkSession.applyGain(samples, count = 2, gain = 3f)
        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE), samples)
    }
}
