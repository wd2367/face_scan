import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// 姿态和采样状态分开传递；paused 不清空本次已完成的有效采样。
enum FaceScanPhase {
  missing,
  occupied,
  clipped,
  near,
  distant,
  aligned,
  validating,
  sampling,
  paused,
  completed,
  failed,
}

class FaceScanEvent {
  const FaceScanEvent({
    required this.phase,
    this.progress = 0,
    this.secondsLeft = 30,
    this.countdown = 0,
    this.bpm = 0,
    this.message = '',
    this.pose = FaceScanPhase.missing,
    this.result,
  });
  factory FaceScanEvent.fromMap(Map<Object?, Object?> data) => FaceScanEvent(
    phase: FaceScanPhase.values.firstWhere(
      (p) => p.name == data['phase'],
      orElse: () => FaceScanPhase.failed,
    ),
    progress: (data['progress'] as num?)?.toDouble() ?? 0,
    secondsLeft: (data['secondsLeft'] as num?)?.toInt() ?? 30,
    countdown: (data['countdown'] as num?)?.toInt() ?? 0,
    bpm: (data['bpm'] as num?)?.toDouble() ?? 0,
    message: data['message'] as String? ?? '',
    pose: FaceScanPhase.values.firstWhere(
      (p) => p.name == data['pose'],
      orElse: () => FaceScanPhase.missing,
    ),
    result: data['result'] is Map
        ? Map<String, dynamic>.from(data['result'] as Map)
        : null,
  );
  final FaceScanPhase phase;

  /// 采样暂停时仍保留本次进度，pose 单独说明过近、过远或移出框等原因。
  final FaceScanPhase pose;
  final double progress, bpm;
  final int secondsLeft, countdown;
  final String message;
  final Map<String, dynamic>? result;
}

/// 单会话控制器。取消监听后必须 dispose，原生相机资源不能依赖 GC 释放。
class FaceScanner {
  static const _methods = MethodChannel('face_scan/methods');
  late final Stream<FaceScanEvent> events =
      const EventChannel('face_scan/events').receiveBroadcastStream().map(
        (data) =>
            FaceScanEvent.fromMap(Map<Object?, Object?>.from(data as Map)),
      );
  Future<void> initialize() => _methods.invokeMethod<void>('initialize');
  Future<void> start({
    required bool advanced,
    required int age,
    required String gender,
    required double heightCm,
    required double weightKg,
  }) => _methods.invokeMethod<void>('start', {
    'advanced': advanced,
    'age': age,
    'gender': gender,
    'heightCm': heightCm,
    'weightKg': weightKg,
  });
  Future<void> pause() => _methods.invokeMethod<void>('pause');
  Future<void> resume() => _methods.invokeMethod<void>('resume');
  Future<void> stop() => _methods.invokeMethod<void>('stop');
  Future<void> dispose() => _methods.invokeMethod<void>('dispose');
}

/// 原生 PreviewView 渲染视频，Flutter 可叠加扫描框和状态。
class FaceScanPreview extends StatelessWidget {
  const FaceScanPreview({super.key, this.onCreated});
  final void Function(int)? onCreated;
  @override
  Widget build(BuildContext context) {
    if (defaultTargetPlatform != TargetPlatform.android) {
      return const Center(
        child: Text('Face scanning is supported on Android.'),
      );
    }
    return AndroidView(
      viewType: 'face_scan/preview',
      onPlatformViewCreated: onCreated,
    );
  }
}
