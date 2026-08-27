package io.motohub.android.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDecoderStallDiagnosisTest {

    @Test
    fun outputStillFlowingIsNotAStall() {
        assertEquals(StallVerdict.NONE, diagnoseStall(stallGapMs = 0, inputGapMs = 5, downstreamBlockedMs = 0))
        assertEquals(StallVerdict.NONE, diagnoseStall(stallGapMs = 2_999, inputGapMs = 5, downstreamBlockedMs = 0))
    }

    @Test
    fun noInputMeansAndroidAutoPausedAndNotThatAnythingBroke() {
        assertEquals(
            StallVerdict.NONE,
            diagnoseStall(stallGapMs = 20_000, inputGapMs = 1_000, downstreamBlockedMs = 0)
        )
    }

    @Test
    fun inputFlowingWithAnIdlePipeIsTheDecoder() {
        assertEquals(
            StallVerdict.DECODER,
            diagnoseStall(stallGapMs = 9_201, inputGapMs = 6, downstreamBlockedMs = 0)
        )
        assertEquals(
            StallVerdict.DECODER,
            diagnoseStall(stallGapMs = 9_201, inputGapMs = 6, downstreamBlockedMs = 400)
        )
    }

    @Test
    fun inputFlowingWithABlockedPipeIsNotTheDecoder() {
        assertEquals(
            StallVerdict.DOWNSTREAM,
            diagnoseStall(stallGapMs = 9_201, inputGapMs = 6, downstreamBlockedMs = 8_900)
        )
        assertEquals(
            StallVerdict.DOWNSTREAM,
            diagnoseStall(stallGapMs = 9_200, inputGapMs = 6, downstreamBlockedMs = 4_600)
        )
        assertEquals(
            StallVerdict.DECODER,
            diagnoseStall(stallGapMs = 9_200, inputGapMs = 6, downstreamBlockedMs = 4_599)
        )
    }

    @Test
    fun withoutAProbeEveryStallIsStillTheDecoders() {
        assertEquals(
            StallVerdict.DECODER,
            diagnoseStall(stallGapMs = 3_001, inputGapMs = 999, downstreamBlockedMs = 0)
        )
    }
}
