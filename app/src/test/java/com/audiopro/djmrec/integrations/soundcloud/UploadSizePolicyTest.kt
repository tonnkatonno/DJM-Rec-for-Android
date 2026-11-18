package com.audiopro.djmrec.integrations.soundcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadSizePolicyTest {
    @Test fun theTwoMeasuredSetsFallOnEitherSideOfTheLine() {
        assertFalse(UploadSizePolicy.needsSmallerCopy(2_105_752_462L))    // 2026-11-16: went up through the API
        assertTrue(UploadSizePolicy.needsSmallerCopy(2_191_705_919L))     // 2026-11-18: HTTP 413
        assertFalse(UploadSizePolicy.needsSmallerCopy(300_000_000L))
    }

    @Test fun readsTheFlacStreamInfo() {
        val header = ByteArray(42)
        "fLaC".forEachIndexed { i, c -> header[i] = c.code.toByte() }
        header[4] = 0x80.toByte()                                          // last metadata block, type 0 = STREAMINFO
        header[7] = 34
        val total = 530_302_520L                                           // 92 minutes at 96 kHz
        val packed = (96_000L shl 44) or (1L shl 41) or (23L shl 36) or total
        for (i in 0 until 8) header[18 + i] = (packed ushr (56 - 8 * i)).toByte()
        val info = UploadSizePolicy.flacStreamInfo(header)!!
        assertEquals(96_000, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(24, info.bitsPerSample)
        assertEquals(total, info.totalSamples)
        assertNull(UploadSizePolicy.flacStreamInfo(ByteArray(42)))          // not a FLAC file
        assertNull(UploadSizePolicy.flacStreamInfo(header.copyOf(20)))      // too short
    }

    @Test fun halvingsDownTo48k() {
        assertEquals(1, UploadSizePolicy.halvings(96_000))
        assertEquals(1, UploadSizePolicy.halvings(88_200))
        assertEquals(2, UploadSizePolicy.halvings(192_000))
        assertEquals(0, UploadSizePolicy.halvings(48_000))
        assertEquals(0, UploadSizePolicy.halvings(44_100))
    }
}
