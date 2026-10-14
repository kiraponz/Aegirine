package com.kira3.ametrine.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputScaleTest {
    @Test
    fun noOverrideNoScalerTracksLayout() {
        assertNull(outputBufferSize(1920, 1080, 0, "auto"))
        assertNull(outputBufferSize(1080, 1920, 0, "auto"))
    }

    @Test
    fun badOverrideIsIgnored() {
        assertNull(outputBufferSize(1920, 1080, 0, ""))
        assertNull(outputBufferSize(1920, 1080, 0, "oops"))
        assertNull(outputBufferSize(1920, 1080, 0, "1x1"))
    }

    @Test
    fun parseResOverride() {
        assertEquals(1920 to 1080, parseResOverride("1920x1080"))
        assertEquals(1280 to 720, parseResOverride("1280X720"))
        assertNull(parseResOverride("auto"))
        assertNull(parseResOverride(null))
    }

    @Test
    fun resOverridePinsBuffer() {
        assertEquals(1920 to 1080, outputBufferSize(1920, 1080, 0, "1920x1080"))
    }

    @Test
    fun hwScalerDownscalesShortSide() {
        // 1080p landscape, 480p -> short side pinned to 480.
        assertEquals(853 to 480, outputBufferSize(1920, 1080, 1, "auto"))
        // 1440p on a small view is larger than the screen: no downscale.
        assertNull(outputBufferSize(800, 600, 3, "auto"))
    }

    @Test
    fun scaleInsetToBuffer() {
        // No downscale: buffer pixels equal view pixels.
        assertEquals(80, scaleInsetToBuffer(80, 1920, 1920))
        // Downscaled buffer: the inset shrinks with it, so the on-screen shift still clears the cutout.
        assertEquals(33, scaleInsetToBuffer(80, 1920, 800))
        assertEquals(0, scaleInsetToBuffer(0, 1920, 800))
        assertEquals(0, scaleInsetToBuffer(80, 0, 800))
    }

    @Test
    fun portraitOverrideKeepsViewShape() {
        // Landscape override on a portrait view: short side only, view's tall shape kept.
        // 720p override on a 1080x1920 view -> 720 wide, height scaled from the view's shape.
        assertEquals(720 to 1280, outputBufferSize(1080, 1920, 0, "1280x720"))
    }
}
