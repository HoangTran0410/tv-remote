package dev.tvremote.core

import dev.tvremote.core.picture.Tv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoftEncoderTest {
    @Test fun marked() = assertEquals("c2.android.avc.encoder", Tv.softIn("""
        --video-codec=h264 --video-encoder=c2.amlogic.avc.encoder        (hw) [vendor]
        --video-codec=h264 --video-encoder=c2.android.avc.encoder        (sw)
        --video-codec=h265 --video-encoder=c2.android.hevc.encoder       (sw)"""))

    // Android 9: scrcpy writes no (hw) / (sw); Google's own encoder is the software one (issue #10)
    @Test fun android9() = assertEquals("OMX.google.h264.encoder", Tv.softIn("""
        --video-codec=h264 --video-encoder=OMX.amlogic.avc.encoder.awesome
        --video-codec=h264 --video-encoder=OMX.google.h264.encoder
        --video-codec=h265 --video-encoder=OMX.google.hevc.encoder"""))

    @Test fun none() = assertNull(Tv.softIn("--video-codec=h264 --video-encoder=OMX.amlogic.avc.encoder.awesome"))

    // a marked list names its software one only by the mark: a vendor's (hw) stays hardware whatever its name
    @Test fun markWins() = assertNull(Tv.softIn("--video-codec=h264 --video-encoder=OMX.google.h264.encoder (hw)"))

    // an NVIDIA Shield (Android 11), as scrcpy 5.0.1 listed it there on 2026-11-05
    private val shield = """
        --video-codec=h264 --video-encoder=OMX.Nvidia.h264.encoder        (hw) [vendor]
        --video-codec=h264 --video-encoder=OMX.google.h264.encoder        (hybrid)
        --audio-codec=aac --audio-encoder=OMX.google.aac.encoder          (hybrid)
        --audio-codec=flac --audio-encoder=OMX.google.flac.encoder        (hybrid)"""

    @Test fun shieldsHybridIsTheSoftwareOne() = assertEquals("OMX.google.h264.encoder", Tv.softIn(shield))

    @Test fun noOpusSoundGoesAac() {
        assertEquals("aac", Tv.soundCodec("opus", shield, aacOk = true))
        assertEquals("opus", Tv.soundCodec("opus", shield, aacOk = false))   // a sink that plays only Opus: as before
        assertEquals("opus", Tv.soundCodec("opus", shield + "\n--audio-codec=opus --audio-encoder=c2.android.opus.encoder (sw)", aacOk = true))
        assertEquals("opus", Tv.soundCodec("opus", null, aacOk = true))      // no list: as asked
        assertEquals("raw", Tv.soundCodec("raw", shield, aacOk = true))
    }

    @Test fun webPageWithNoOpusAndNoAacGetsPcm() {
        val flacOnly = "--audio-codec=flac --audio-encoder=OMX.google.flac.encoder (hybrid)"
        assertEquals("raw", Tv.soundCodec("opus", flacOnly, aacOk = false, rawOk = true))   // the web page: scrcpy would die on Opus
        assertEquals("raw", Tv.soundCodec("opus", shield, aacOk = false, rawOk = true))
        assertEquals("aac", Tv.soundCodec("opus", shield, aacOk = true, rawOk = true))      // a sink that plays AAC keeps it
        assertEquals("opus", Tv.soundCodec("opus", null, aacOk = false, rawOk = true))      // no list: as asked
        assertEquals("refused:34", Tv.refusedWord(34))
    }
}
