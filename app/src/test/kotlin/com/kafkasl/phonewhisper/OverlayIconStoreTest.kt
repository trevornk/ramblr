package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers [centerCropRegion], the pure crop-math half of [OverlayIconStore.save] -- the Bitmap
 *  decode/scale/compress side needs a real Android runtime and isn't exercised by plain JVM tests. */
class OverlayIconStoreTest {

    @Test fun `square image needs no cropping`() {
        assertEquals(CropRegion(x = 0, y = 0, size = 200), centerCropRegion(200, 200))
    }

    @Test fun `landscape image is cropped on the x axis, centered`() {
        assertEquals(CropRegion(x = 100, y = 0, size = 300), centerCropRegion(500, 300))
    }

    @Test fun `portrait image is cropped on the y axis, centered`() {
        assertEquals(CropRegion(x = 0, y = 100, size = 300), centerCropRegion(300, 500))
    }

    @Test fun `odd-sized differences still center as closely as integer division allows`() {
        assertEquals(CropRegion(x = 0, y = 0, size = 101), centerCropRegion(101, 102))
    }
}
