# face_scan

Android 人脸扫描插件，提供相机预览、测量控制和状态事件。

## 功能特性

- 📷 原生相机预览组件
- 🎯 人脸扫描与测量控制
- 📊 实时状态事件回调
- ⚡ 支持暂停、恢复和停止操作

## 系统要求

- Android API 24 及以上
- Flutter 3.0.0 及以上

## 安装

在 `pubspec.yaml` 中添加依赖：

```yaml
dependencies:
  face_scan: ^0.1.1
```

## 使用方法

### 1. 申请相机权限

在使用插件前，需要由宿主应用申请相机权限。

### 2. 初始化和使用

```dart
import 'package:face_scan/face_scan.dart';

// 订阅扫描事件
final subscription = FaceScanner.events.listen((event) {
  print('扫描阶段: ${event.phase}');
  print('进度: ${event.progress}');
  if (event.result != null) {
    print('扫描结果: ${event.result}');
  }
});

// 初始化
await FaceScanner.initialize();

// 开始扫描
await FaceScanner.start();

// 暂停
await FaceScanner.pause();

// 恢复
await FaceScanner.resume();

// 停止
await FaceScanner.stop();

// 释放资源
await FaceScanner.dispose();
subscription.cancel();
```

### 3. 相机预览

```dart
FaceScanPreview()
```

## 注意事项

- 插件仅支持单会话，同时只能有一个扫描实例
- 取消订阅后需调用 `dispose()` 释放资源
- Flutter 接口与 Android 实现需配套使用

## 许可证

MIT License
