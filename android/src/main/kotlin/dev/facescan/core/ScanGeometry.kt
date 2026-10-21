package dev.facescan.core

/** CameraX 的共享 ViewPort 裁剪先转到 ML Kit 坐标，再映射到 256×348 预览。 */
internal data class ScanBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
}

internal object ScanGeometry {
    fun rotateCrop(crop: ScanBox, imageWidth: Int, imageHeight: Int, rotation: Int): ScanBox = when (rotation) {
        90 -> ScanBox(imageHeight - crop.bottom, crop.left, imageHeight - crop.top, crop.right)
        180 -> ScanBox(imageWidth - crop.right, imageHeight - crop.bottom, imageWidth - crop.left, imageHeight - crop.top)
        270 -> ScanBox(crop.top, imageWidth - crop.right, crop.bottom, imageWidth - crop.left)
        else -> crop
    }

    fun mapToPreview(box: ScanBox, crop: ScanBox): ScanBox = ScanBox(
        (box.left - crop.left) * 256 / crop.width,
        (box.top - crop.top) * 348 / crop.height,
        (box.right - crop.left) * 256 / crop.width,
        (box.bottom - crop.top) * 348 / crop.height,
    )

    fun pose(box: ScanBox?): String {
        if (box == null) return "missing"
        val ratio = box.width * box.height / (216 * 308)
        // 先判过近，随后判出界，最后判过远。
        if (box.width > 256 || box.height > 348 || ratio > 1) return "near"
        if (box.left < -4 || box.top < -4 || box.right > 260 || box.bottom > 352) return "clipped"
        return if (ratio < 0.4) "distant" else "aligned"
    }
}
