# 构建与发布

在 GitHub 的 Actions 中选择 **Build and release**，点击 **Run workflow**，填写与应用版本对应的标签，例如 `v0.4.6`。

只想检查和打包时，保留 `publish_release=false`；构建完成后，从该次运行的 Artifacts 下载。需要发布时，启用 `publish_release`。预览版本保留 `prerelease=true`。

工作流会安装 JDK 21、Android SDK Platform 37.0 与 Build Tools 37.0.0，然后运行 App 测试、协议测试、Android lint 和经过 R8 优化的 `deviceTest` 构建。任何一项失败都不会发布。通过后先创建草稿，上传文件，最后发布 Release。

## 签名

仓库使用 `ANDROID_DEBUG_KEYSTORE_BASE64` 这个 Actions Secret，保存当前项目测试签名文件的 Base64。它用于延续已安装测试包的签名身份，不应写入仓库、日志或 Release 附件。Secret 通过 GitHub 公钥加密后提交；源码只保存公开的证书 SHA-256 指纹，构建时核对 APK 证书，防止意外换用临时签名。

工作流将签名文件还原到临时目录，在构建前检查证书指纹，再通过 `HYPERPODS_TEST_KEYSTORE` 显式传入 Gradle，避免依赖不同操作系统上的默认调试签名路径。本地没有设置这个变量时，继续使用 Android 默认的调试签名配置。

这是 `deviceTest` 预览版的测试签名流程。未来若改用正式发行签名，需要单独规划从测试包迁移的安装方式。不要在没有确认迁移方式时更换签名。

## 发布文件

- `HyperPods-X-v<版本>-device-test.apk`：已签名的优化预览包。
- `HyperPods-X-v<版本>-source.zip`：对应构建提交的完整源码，由 `git archive` 生成。
- `build-info.json`：构建提交、版本、公开证书指纹、测试与 lint 统计，以及 Actions 链接。
- `SHA256SUMS`：上述文件的 SHA-256。

源码打包时会检查跟踪文件，拒绝将开发环境、设备资料、抓包、签名文件、APK 或本机路径配置打进源码 ZIP。APK 的字节摘要可能因不同构建环境而变化；保持可覆盖安装所需的是相同的签名证书，而不是 APK 文件摘要相同。

## 更新版本

修改 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`，在 `docs/releases/` 中添加对应标签的 Markdown 发布说明，再提交并推送。比如 `versionName="0.4.6-dev"` 对应 `v0.4.6`；工作流会校验二者一致。

工作流不会覆盖已经发布的同名 Release。重试失败的上传可以继续使用指向相同提交的草稿；内容改变时应使用新的版本标签。
