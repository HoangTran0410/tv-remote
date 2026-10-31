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
}
