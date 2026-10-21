package dev.facescan.core

import org.junit.Assert.*
import org.junit.Test

class ScanGeometryTest {
    @Test fun cropRotationUsesMlKitCoordinates() {
        val crop = ScanBox(40.0, 20.0, 580.0, 450.0)
        assertEquals(ScanBox(30.0, 40.0, 460.0, 580.0), ScanGeometry.rotateCrop(crop, 640, 480, 90))
        assertEquals(ScanBox(60.0, 30.0, 600.0, 460.0), ScanGeometry.rotateCrop(crop, 640, 480, 180))
        assertEquals(ScanBox(20.0, 60.0, 450.0, 600.0), ScanGeometry.rotateCrop(crop, 640, 480, 270))
    }

    @Test fun faceMapsToSharedPreviewInsteadOfFullSensor() {
        val crop = ScanBox(80.0, 100.0, 592.0, 796.0)
        val face = ScanBox(140.0, 180.0, 500.0, 720.0)
        val mapped = ScanGeometry.mapToPreview(face, crop)
        assertEquals(ScanBox(30.0, 40.0, 210.0, 310.0), mapped)
        assertEquals("aligned", ScanGeometry.pose(mapped))
        val outside = ScanGeometry.mapToPreview(ScanBox(30.0, 180.0, 390.0, 720.0), crop)
        assertEquals("clipped", ScanGeometry.pose(outside))
    }

    @Test fun poseThresholdsUseInsetGuideAndPrioritizeNear() {
        assertEquals("missing", ScanGeometry.pose(null))
        assertEquals("distant", ScanGeometry.pose(ScanBox(100.0, 120.0, 156.0, 228.0)))
        assertEquals("near", ScanGeometry.pose(ScanBox(-10.0, 0.0, 266.0, 348.0)))
        assertEquals("near", ScanGeometry.pose(ScanBox(10.0, 10.0, 246.0, 338.0)))
        assertEquals("clipped", ScanGeometry.pose(ScanBox(-5.0, 50.0, 175.0, 300.0)))
        assertEquals("aligned", ScanGeometry.pose(ScanBox(-4.0, 50.0, 176.0, 300.0)))
    }
}
