# 构建与自动化发布指南

HyperPods-X 借助 GitHub Actions 实现了自动化构建、质量门禁检查与 Release 打包分发流程。

---

## 1. 自动化流水线触发

在 GitHub 仓库的 **Actions** 页面中选择 **Build and release** 工作流，点击 **Run workflow**：
- **`tag_name`**：输入本次发布对应的版本标签（如 `v0.5.0`，需与 `app/build.gradle.kts` 中的 `versionName` 严格保持一致）；
- **`publish_release`**：默认 `false` 仅执行构建与门禁检查，并在 Artifacts 中提供测试包下载；设为 `true` 时将在通过检查后正式创建 GitHub Release；
- **`prerelease`**：针对预览包开启预览标记。

---

## 2. CI 门禁检查项目

每次流水线运行必须依序通过以下自动化质量门禁：
1. **环境初始化**：安装 JDK 21、Android SDK 37 及对应 Build Tools，执行 `python -m pip install -r scripts/requirements-fonts.txt` 和 `python scripts/prepare-ui.py --fonts` 完成 Glasmio 依赖、SF Pro 常规字体校验与子集裁剪；
2. **测试与静态检查**：
   - `:app:testDebugUnitTest`（应用层单元测试）
   - `:core:protocol:test`（纯 Kotlin 底层协议测试）
   - `:core:wire:testDebugUnitTest`（IPC 通信与契约测试）
   - `:app:lintDebug`（Android 静态代码规范检查）
3. **优化与产物构建**：执行 `:app:assembleDeviceTest` 进行 R8 代码混淆与体积压缩；
4. **签名与对齐验证**：校验 APK v2 签名完整性与 16 KB 页面对齐。

---

## 3. 签名与发布产物

### 3.1 签名配置
仓库通过 GitHub Secrets 中的 `ANDROID_DEBUG_KEYSTORE_BASE64` 注入测试签名文件，确保所有构建出的测试安装包签名保持一致，支持覆盖安装。本地构建若未配置该变量，则自动回退至 Android 默认调试签名。

### 3.2 发布资产清册
正式发布时将打包并上传以下文件：
- `HyperPods-X-v<版本>-device-test.apk`：经过优化混淆与签名的可安装预览包；
- `HyperPods-X-v<版本>-source.zip`：对应 Git 提交的干净源码归档包；
- `build-info.json`：构建环境、提交哈希、证书公钥指纹与测试通过统计；
- `SHA256SUMS`：所有发布资产的 SHA-256 校验和文件。
