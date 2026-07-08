package com.trevornk.ramblr

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

    // -- overlayIconSampleSize (M15: subsample so a huge photo never decodes full-resolution) --

    @Test fun `an image already near target size is not subsampled`() {
        assertEquals(1, overlayIconSampleSize(300, 300, targetPx = 256))
    }

    @Test fun `a huge photo subsamples down toward the target on its shorter side`() {
        // 8000x6000 (~48MP), shorter side 6000: /2=3000, /4=1500, /8=750, /16=375 (>=256), /32=187 (<256).
        assertEquals(16, overlayIconSampleSize(8000, 6000, targetPx = 256))
    }

    @Test fun `sample size is driven by the shorter side so the target is never undershot`() {
        // 10000x400: shorter side 400, /2=200 (<256) -> stays at 1 so the 400px side isn't lost.
        assertEquals(1, overlayIconSampleSize(10000, 400, targetPx = 256))
    }

    @Test fun `degenerate dimensions fall back to no subsampling`() {
        assertEquals(1, overlayIconSampleSize(0, 0, targetPx = 256))
        assertEquals(1, overlayIconSampleSize(-1, 500, targetPx = 256))
    }
}
