package dev.facescan.core

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.*
import androidx.camera.camera2.interop.Camera2Interop
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Rational
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.*

/** 姿态错误重置倒计时；采样时只暂停有效时间，恢复后继续。 */
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
internal class ScanEngine(private val activity: Activity, private val emit: (Map<String, Any>) -> Unit) {
    val preview = PreviewView(activity).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        // 预览等待帧或变换尺寸时，边缘与扫描页同色，不露出黑色底。
        setBackgroundColor(android.graphics.Color.rgb(252, 249, 252))
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).enableTracking().build())
    private var provider: ProcessCameraProvider? = null
    @Volatile private var running = false
    @Volatile private var suspended = false
    @Volatile private var generation = 0
    private var sampling = false
    private var elapsed = 0.0
    private var alignedSince = 0L
    private var lastFrame = 0L
    private var lastFace = 0L
    private var lastEmit = 0L
    private var previousBox: RectF? = null
    private var latestPulse = 0.0
    private var sampleWasValid = false
    private var posePhase = "missing"
    private var profile: Map<*, *> = emptyMap<Any, Any>()
    private val green = mutableListOf<Double>()
    private val pulses = mutableListOf<Double>()

    fun start(data: Map<*, *>) {
        check(ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { "Camera permission is required" }
        stop()
        profile = data
        running = true; suspended = false; elapsed = 0.0; alignedSince = 0; sampling = false; lastFrame = 0; previousBox = null; latestPulse = 0.0
        lastFace = 0; lastEmit = 0; sampleWasValid = false; posePhase = "missing"
        green.clear(); pulses.clear()
        val session = generation
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (!running || session != generation) return@addListener
            try {
                provider = future.get()
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysisBuilder = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetResolution(android.util.Size(640, 480))
                // 请求 30 FPS；设备若实际丢帧，样本统计留在结果中供真机核对。
                Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                val analysis = analysisBuilder.build()
                analysis.setAnalyzer(worker, ::analyze)
                val owner = activity as? LifecycleOwner ?: error("Activity must provide a lifecycle")
                provider!!.unbindAll()
                val viewport = ViewPort.Builder(Rational(256, 348), preview.display?.rotation ?: 0)
                    .setScaleType(ViewPort.FILL_CENTER).build()
                val group = UseCaseGroup.Builder().setViewPort(viewport)
                    .addUseCase(cameraPreview).addUseCase(analysis).build()
                provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, group)
                emit(mapOf("phase" to "missing", "message" to "Please position your face within the scanning frame."))
            } catch (error: Exception) { fail(error.message ?: "Unable to open the front camera") }
        }, ContextCompat.getMainExecutor(activity))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyze(frame: ImageProxy) {
        if (!running || suspended) { frame.close(); return }
        val media = frame.image
        if (media == null) { frame.close(); return }
        val rotation = frame.imageInfo.rotationDegrees
        val session = generation
        detector.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener(worker) { faces ->
                if (!running || suspended || session != generation) return@addOnSuccessListener
                val now = SystemClock.elapsedRealtime()
                val largest = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                val raw = largest?.boundingBox
                val crop = frame.cropRect
                val rotatedCrop = ScanGeometry.rotateCrop(ScanBox(crop.left.toDouble(), crop.top.toDouble(), crop.right.toDouble(), crop.bottom.toDouble()), frame.width, frame.height, rotation)
                val mapped = raw?.let { ScanGeometry.mapToPreview(ScanBox(it.left.toDouble(), it.top.toDouble(), it.right.toDouble(), it.bottom.toDouble()), rotatedCrop) }
                val pose = ScanGeometry.pose(mapped)
                posePhase = pose
                val valid = pose == "aligned"
                val delta = if (lastFrame == 0L) 0.0 else min((now - lastFrame) / 1000.0, 0.2)
                lastFrame = now
                if (!sampling) {
                    if (valid) {
                        if (alignedSince == 0L) alignedSince = now
                        val count = 3 - ((now - alignedSince) / 1000).toInt()
                        if (count <= 0) { sampling = true; elapsed = 0.0; sampleWasValid = false; green.clear(); pulses.clear() }
                        else dispatch(now, "validating", "Please remain still.", count)
                    } else {
                        alignedSince = 0
                        dispatch(now, pose, message(pose))
                    }
                }
                if (sampling) {
                    var stable = valid
                    if (valid && raw != null) {
                        val box = RectF(raw)
                        val old = previousBox
                        val blended = if (old == null) box else RectF(old.left + (box.left - old.left) * 0.3f, old.top + (box.top - old.top) * 0.3f, old.right + (box.right - old.right) * 0.3f, old.bottom + (box.bottom - old.bottom) * 0.3f)
                        val movement = if (old == null) 0.0 else hypot((blended.centerX() - old.centerX()).toDouble(), (blended.centerY() - old.centerY()).toDouble())
                        previousBox = blended
                        stable = movement <= blended.width() * 0.05
                        if (stable) {
                            val value = averageGreen(frame, blended, rotation)
                            stable = value > 0
                            if (stable) {
                                if (sampleWasValid) elapsed = min(30.0, elapsed + delta)
                                green.add(value)
                            }
                            if (green.size >= 256 && green.size % 30 == 0) {
                                val bpm = SignalProcessor.livePulse(green)
                                if (bpm in 45.0..150.0) { latestPulse = if (pulses.isEmpty()) bpm else latestPulse * 0.6 + bpm * 0.4; pulses.add(latestPulse) }
                            }
                        }
                        lastFace = now
                    } else if (raw == null && now - lastFace < 750) {
                        // 为检测短暂丢失保留 0.75 秒宽限，但不采集无脸帧。
                    }
                    sampleWasValid = stable
                    if (valid && !stable) posePhase = "distant"
                    dispatch(now, if (stable) "sampling" else "paused", if (valid) "Please remain still for the measurement." else message(pose))
                    if (elapsed >= 30.0) {
                        running = false
                        try {
                            val result = SignalProcessor.result(pulses, green, profile) + mapOf("sampleCount" to green.size, "effectiveSeconds" to elapsed, "assumedFps" to 30)
                            main.post { if (session == generation) { emit(mapOf("phase" to "completed", "progress" to 1.0, "result" to result, "sampleCount" to green.size)); provider?.unbindAll() } }
                        } catch (error: Exception) { fail("Signal was insufficient. Please improve the lighting, remain still and retry.") }
                    }
                }
            }
            .addOnFailureListener(worker) { error -> if (running && session == generation) fail(error.message ?: "Face detection failed") }
            .addOnCompleteListener(worker) { frame.close() }
    }

    /** ML Kit 使用旋转后坐标；YUV 的采样坐标需逆旋转。中央 50% ROI 使用 inset(25%)。 */
    private fun averageGreen(image: ImageProxy, rect: RectF, rotation: Int): Double {
        val planes = image.planes
        val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]
        val roi = RectF(rect).apply { inset(width() * 0.25f, height() * 0.25f) }
        var sum = 0.0; var count = 0
        for (ry in roi.top.toInt() until roi.bottom.toInt() step 2) {
            for (rx in roi.left.toInt() until roi.right.toInt() step 2) {
                val (x, y) = when(rotation) {
                    90 -> Pair(ry, image.height - 1 - rx)
                    180 -> Pair(image.width - 1 - rx, image.height - 1 - ry)
                    270 -> Pair(image.width - 1 - ry, rx)
                    else -> Pair(rx, ry)
                }
                if (x !in 0 until image.width || y !in 0 until image.height) continue
                val yi = y * yPlane.rowStride + x * yPlane.pixelStride
                val ui = (y / 2) * uPlane.rowStride + (x / 2) * uPlane.pixelStride
                val vi = (y / 2) * vPlane.rowStride + (x / 2) * vPlane.pixelStride
                if (yi >= yPlane.buffer.limit() || ui >= uPlane.buffer.limit() || vi >= vPlane.buffer.limit()) continue
                val yy = (yPlane.buffer.get(yi).toInt() and 255) - 16
                val u = (uPlane.buffer.get(ui).toInt() and 255) - 128
                val v = (vPlane.buffer.get(vi).toInt() and 255) - 128
                sum += ((298 * yy - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }
    private fun dispatch(now: Long, phase: String, text: String, countdown: Int = 0) {
        if (now - lastEmit < 100) return
        lastEmit = now
        val event = mapOf("phase" to phase, "pose" to posePhase, "message" to text, "progress" to elapsed / 30, "secondsLeft" to ceil(30 - elapsed).toInt(), "countdown" to countdown, "bpm" to latestPulse)
        val session = generation
        main.post { if (running && !suspended && session == generation) emit(event) }
    }
    private fun message(pose: String) = when(pose) { "near" -> "Your face is too close. Please adjust your distance."; "distant" -> "Your face is too far away. Please move closer."; "clipped" -> "Please fit your whole face within the frame."; else -> "No face detected. Please try again." }
    private fun fail(text: String) { running = false; val session = generation; main.post { if (session == generation) { provider?.unbindAll(); emit(mapOf("phase" to "failed", "message" to text)) } } }
    fun pause() { suspended = true; lastFrame = 0; sampleWasValid = false }
    fun resume() { suspended = false; lastFrame = 0; sampleWasValid = false; if (!sampling) alignedSince = 0 }
    fun stop() { generation++; running = false; provider?.unbindAll(); alignedSince = 0; lastFrame = 0 }
    fun dispose() { stop(); detector.close(); worker.shutdown() }
}
