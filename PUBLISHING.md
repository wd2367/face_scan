# 发布 face_scan 到 pub.dev

## 问题诊断

本地发布时遇到网络问题：无法连接到 `accounts.google.com`，OAuth 认证超时。

## 解决方案

### 方案1：本地发布（需要稳定网络）

1. 确保网络可以访问 Google 服务
2. 运行发布命令：
```bash
cd packages/face_scan
flutter pub publish
```

### 方案2：使用 GitHub Actions 自动发布（推荐）

#### 步骤1：获取 pub.dev credentials

在可以访问 Google 的环境中运行一次：
```bash
cd packages/face_scan
flutter pub publish --dry-run
# 完成 OAuth 授权后
```

然后获取 credentials 文件：
```bash
# macOS/Linux
cat ~/.pub-cache/credentials.json

# Windows
type %APPDATA%\Pub\Cache\credentials.json
```

#### 步骤2：添加 GitHub Secret

1. 打开 GitHub 仓库设置
2. 进入 Settings > Secrets and variables > Actions
3. 点击 "New repository secret"
4. Name: `PUB_CREDENTIALS`
5. Value: 粘贴上面的 credentials.json 内容
6. 保存

#### 步骤3：触发发布

方法A - 创建 Git tag：
```bash
git tag v0.1.0
git push origin v0.1.0
```

方法B - 手动触发：
1. 打开 GitHub 仓库
2. 进入 Actions 标签
3. 选择 "Publish Plugin to pub.dev"
4. 点击 "Run workflow"

## 当前状态

- ✅ pubspec.yaml 已配置
- ✅ CHANGELOG.md 已创建
- ✅ LICENSE 已添加
- ✅ GitHub Actions workflow 已创建
- ⏳ 等待网络环境或使用 GitHub Actions 发布
