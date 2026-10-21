package dev.facescan.core

import android.app.Activity
import android.content.Context
import android.view.View
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*
import io.flutter.plugin.platform.*

/** 插件自包含摄像头与计算，应用无需导入 Kotlin 类。 */
class FaceScanPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var methods: MethodChannel
    private lateinit var events: EventChannel
    private var activity: Activity? = null
    private var engine: ScanEngine? = null
    private var sink: EventChannel.EventSink? = null
    private var disposed = false
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods = MethodChannel(binding.binaryMessenger, "face_scan/methods")
        events = EventChannel(binding.binaryMessenger, "face_scan/events")
        methods.setMethodCallHandler(this); events.setStreamHandler(this)
        binding.platformViewRegistry.registerViewFactory("face_scan/preview", object : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
            override fun create(context: Context, id: Int, args: Any?): PlatformView {
                val value = ensureEngine()
                return object : PlatformView { override fun getView(): View = value.preview; override fun dispose() { value.stop() } }
            }
        })
    }
    private fun ensureEngine(): ScanEngine {
        if (engine == null || disposed) { engine = ScanEngine(activity ?: error("No Android Activity attached")) { sink?.success(it) }; disposed = false }
        return engine!!
    }
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when(call.method) {
                "initialize" -> { ensureEngine(); result.success(null) }
                "start" -> { ensureEngine().start(call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()); result.success(null) }
                "pause" -> { engine?.pause(); result.success(null) }
                "resume" -> { engine?.resume(); result.success(null) }
                "stop" -> { engine?.stop(); result.success(null) }
                "dispose" -> { engine?.dispose(); engine = null; disposed = true; result.success(null) }
                else -> result.notImplemented()
            }
        } catch (error: Exception) { result.error("SCAN_ERROR", error.message, null) }
    }
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { sink = events }
    override fun onCancel(arguments: Any?) { sink = null }
    override fun onAttachedToActivity(binding: ActivityPluginBinding) { activity = binding.activity }
    override fun onDetachedFromActivityForConfigChanges() { detachActivity() }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) { activity = binding.activity }
    override fun onDetachedFromActivity() { detachActivity() }
    private fun detachActivity() { engine?.dispose(); engine = null; disposed = true; activity = null }
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) { detachActivity(); methods.setMethodCallHandler(null); events.setStreamHandler(null); sink = null }
}
