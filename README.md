# face_scan

Android 人脸扫描插件，提供相机预览、测量控制和状态事件。当前仅在本地使用，不发布到插件库。

## 接口

- `FaceScanner`：初始化、开始、暂停、恢复、停止及释放会话。
- `FaceScanPreview`：原生相机预览组件。
- `FaceScanEvent` / `FaceScanPhase`：扫描状态、进度及结果。

接入前由宿主申请相机权限。先订阅事件，再启动测量；取消订阅后调用 `dispose()` 释放资源。插件仅支持单会话。

Flutter 接口与 Android 实现需配套使用，通信通道和预览标识是内部约定，接入者无需修改。

结果包含基于信号及个人资料计算的健康估算值，不代表直接传感器测量或医学诊断。

## 当前范围

Android API 24 及以上，依赖 CameraX、ML Kit、Java 17 和 AGP 9 工具链。具体依赖版本见 `pubspec.yaml` 和 `android/build.gradle`。

本目录目前仍包含原生源码，尚未进行混淆或二进制交付隔离。
