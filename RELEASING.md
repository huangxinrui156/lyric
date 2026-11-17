# 发版指南

给后续维护者（包括其他 AI）的发版与协作说明。使用者文档见 [README.md](README.md)，变更记录见 [CHANGELOG.md](CHANGELOG.md)。

## 环境

需要 **JDK 17** 与 **Android SDK 35**（含 Build Tools 35.0.0，以便使用 `apksigner`）。仓库已包含 Gradle 8.9 wrapper。

```bash
export JAVA_HOME=    # JDK 17 安装目录
export ANDROID_HOME= # Android SDK 目录
# 也可在 local.properties 中设置 sdk.dir（该文件已 gitignore）
```

首次构建需联网下载依赖；应用运行时不访问网络。

## 构建与检查

合并或发版前在相应提交上跑完这些命令，且必须全部通过：

```bash
./gradlew assembleDebug test assembleBenchmark
bash tools/check-tokens.sh
bash tools/check-licenses.sh
```

- 界面尺寸、颜色、动效与文案只放进 `app/src/main/java/com/lyric/ui/theme/LyricTokens.kt`，不要在 UI 文件里写裸的 dp/sp 或色值。`check-tokens.sh` 会检查这一点。
- 新增运行时依赖须能被 `check-licenses.sh` 识别。

产物位置：

- debug：`app/build/outputs/apk/debug/app-debug.apk`（包名 `com.lyric.reader`）
- benchmark：`app/build/outputs/apk/benchmark/app-benchmark.apk`（包名 `com.lyric.reader.benchmark`，debug 签名）
- release：`app/build/outputs/apk/release/`（有签名配置时为 `app-release.apk`，否则为 `app-release-unsigned.apk`，包名 `com.lyric.reader`）

## 分支与 PR

- 用小而专一的 PR，基于最新 `main`。
- **禁止 force-push**，也不要删除他人还在用的分支。
- **未经所有者明确批准，不要把 PR 合并进 `main`。** 批准后再用普通 merge commit（不要 squash，除非所有者另有说明）。
- 视觉与 token 改动走 `LyricTokens`；产品或视觉争议先问功能设计 / 美术设计，不要擅自改规范。

## 升版本

在单独的小 PR 里同时改这三项，等待所有者批准后再合并：

1. `app/build.gradle.kts` 的 `defaultConfig`：`versionName`（如 `1.0.1`）与 `versionCode`（必须比上一版大，整数递增）。
2. [CHANGELOG.md](CHANGELOG.md)：把 `Unreleased` 写成新版本小节，写清面向使用者的变更。

不要改历史版本的说明文档来「追齐」版本号。

## 签名

正式包必须用同一把 release 密钥签署。证书 SHA-256 指纹必须是：

```
4E:C7:52:9A:8C:0B:1C:BE:00:EE:DE:A1:AF:0C:2C:0D:85:34:CB:80:27:D3:EE:33:93:0C:D0:58:F3:7E:22:DE
```

**对不上就不能发给用户**：已安装正式版的设备将无法覆盖升级。

**密钥丢失或更换后，已安装用户无法升级，只能卸载重装（会丢掉未备份的数据）。** 密钥与密码只放在本机私密位置，**永远不要提交到 git，也不要写进文档、PR 或聊天。** `*.jks`、`*.keystore`、`keystore.properties` 已在 `.gitignore` 中。

### keystore.properties

键名必须如下，值换成你本机的路径与密码（以下仅为占位，不要提交真实内容）：

```properties
storeFile=/absolute/path/to/lyric-release.jks
storePassword=<store-password>
keyAlias=lyric
keyPassword=<key-password>
```

### 构建已签名 release

把上述文件的**绝对路径**交给 Gradle，未设置或文件不存在时，`assembleRelease` 仍会打出**未签名**包，CI 可以继续构建：

```bash
export LYRIC_KEYSTORE_PROPERTIES=/absolute/path/to/keystore.properties
# 等价：./gradlew assembleRelease -Plyric.keystore.properties=/absolute/path/to/keystore.properties
./gradlew assembleRelease
```

也可以先打未签名包，再用 `apksigner` 签 v2 / v3（不要把密码写进命令历史；用 `file:` 或提示输入）：

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner sign \
  --ks /absolute/path/to/lyric-release.jks \
  --ks-key-alias lyric \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out lyric-<versionName>-vc<versionCode>-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

无论哪种方式，发版前必须校验：

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs <release.apk>
```

确认 Verifies、v2 / v3 为 true，包名为 `com.lyric.reader`，`versionName` / `versionCode` 与本次发版一致，**Signer certificate SHA-256 digest 与上文指纹一致**（`apksigner` 输出为无冒号小写，与上文为同一组字节）。

正式签名包与 debug 包同包名但签名不同，**不能覆盖安装**。用户需先在应用内备份，卸载旧包后再装正式版。benchmark 包名不同，可与正式包并存。


## 安全

- 仓库 ruleset：`main` 禁止删除与 force-push，更新须走 PR（不要求 Approve，所有者可合并自己的 PR）。`v*` 标签的创建、更新、删除与非快进更新仅仓库管理员可做，避免误改已发布标签。
- 签名发版 job 只在 `main` 上的 `workflow_dispatch`，或推送 `v*` 标签时运行；标签对应提交必须已是 `main` 的祖先（`git merge-base --is-ancestor`），否则失败。只有 tag 才会发布 GitHub Release。
- 第三方 Actions 按完整 commit SHA 固定（注释中标明版本），不要改回浮动的主版本标签。
- Secrets 只放在仓库 Settings，不要打印或写入日志、文档与聊天。

## 发版（标签与 GitHub Release）

1. 升版本的 PR 经所有者批准并合并进 `main`（见上文）。
2. 在该提交上打 **annotated** 标签并 push（不要 force，也不要移动已发布的标签，例如不要改 `v1.0.0` 的指向）：

```bash
git tag -a vX.Y.Z <commit> -m "Lyric X.Y.Z"
git push origin vX.Y.Z
```

3. 推送 `v*` 标签后，GitHub Actions（`.github/workflows/release.yml`）会自动跑测试与检查、用仓库 Secrets 签名 `assembleRelease`、校验证书指纹，并创建或更新该标签的 GitHub Release，附上 `lyric-<versionName>-vc<versionCode>-release.apk`。

仓库中只应保存 Secret **名称**，不要把值写入 git、文档或聊天：

- `LYRIC_KEYSTORE_BASE64`（release keystore 的 base64，无换行）
- `LYRIC_STORE_PASSWORD`
- `LYRIC_KEY_ALIAS`
- `LYRIC_KEY_PASSWORD`

工作流在 runner 临时目录解码 keystore、写入 `keystore.properties`，并设置 `LYRIC_KEYSTORE_PROPERTIES`，走现有的可选签名配置。`workflow_dispatch` 仅在 `main` 上试构建并校验证书，**只有推送已落在 `main` 历史上的 `v*` 标签才会签名并发布 Release**。

本机手动签名（见上一节）仍可用于对照，或在 Actions 不可用时使用。Release 说明用简体中文，写清自上一版以来的用户可见变更，并附 APK 的 sha256。**推荐下载正式签名的 release APK**；若同时附 debug / benchmark，需注明签名与包名差异，以及不能覆盖安装正式包。
