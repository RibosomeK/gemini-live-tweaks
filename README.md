# Gemini Live Tweaks

作者：DeepSeek

一个只作用于 Google 应用（GSA）的 LSPosed 模块，提供三个可独立开关的
Gemini Live 功能：

1. **锁屏进入 Live**：锁屏/熄屏时说 “Hey Google, let's talk” 时绕过解锁，
   直接进入 Live。
2. **默认进入 Live**：普通 “Hey Google” 热词唤醒后直接进入 Live，而不是
   普通语音浮层。
3. **结束 Live 后自动退出**：说 “end session” 或点 X 结束 Live 后关闭
   Gemini，回到桌面或锁屏，而不是停留在聊天界面。

## 设置界面

安装后从桌面打开 **Gemini Live Tweaks**，可以单独开关以上三项。配置保存在
本应用的 SharedPreferences 中，通过导出的只读 Provider 提供给 Google 应用，
约 1 秒内生效，不需要 Root，也不需要重启。三项功能默认开启。

关闭 **锁屏进入 Live** 后，即使 **默认进入 Live** 仍开启，锁屏/熄屏唤醒也
不会绕过解锁直接进入 Live；解锁后默认进入 Live 仍然有效。

界面还提供 **隐藏桌面图标** 开关，默认开启。模块的配置界面注册了 LSPosed
的模块设置入口，因此隐藏桌面图标后仍可从 LSPosed 管理器打开：进入
**模块** → **Gemini Live Tweaks**，点右下角的悬浮按钮。若刚安装后没有看到
按钮，返回模块列表再进入一次即可。模块本身仍在 LSPosed 中启用并勾选
Google 作用域；关闭隐藏开关可以让桌面图标重新显示。

## 适用环境

- 已安装 LSPosed。
- Google 应用，测试版本 `17.68.28.ve.arm64`。
- OnePlus 15R `CPH2769`，OxygenOS `16.0.5.701(EX01)`，Android 16。
- GSA 内部类名经过混淆，Google 应用更新后模块可能失效。

## 安装

1. 安装 `build/GeminiLiveTweaks.apk`。
2. 在 LSPosed 管理器中启用 **Gemini Live Tweaks**。
3. 作用域只勾选 Google（`com.google.android.googlequicksearchbox`）。
4. 重启手机，或至少重启 Google 应用。
5. 打开模块 App 调整开关；隐藏桌面图标后，仍可从 LSPosed 模块页右下角
   的悬浮按钮打开。

更新模块后请重载目标应用：

```bash
adb install --no-incremental -r GeminiLiveTweaks.apk
adb shell am force-stop com.google.android.googlequicksearchbox
```

## 构建

依赖：

- JDK 17+（`javac`、`jar`、`keytool`）
- Android SDK Platform 36（`android.jar`）
- Android build-tools（`aapt2`、`d8`、`zipalign`、`apksigner`）
- Python 3
- `libs/api-82.jar`（Xposed API 82，已包含）

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/Android/Sdk
./build.sh
```

或显式指定工具路径：

```bash
ANDROID_JAR=/path/to/android-36/android.jar \
BUILD_TOOLS=/path/to/build-tools/36.0.0 \
./build.sh
```

输出：`build/GeminiLiveTweaks.apk`。

仓库内的调试签名仅用于模块更新一致性，不要用于正式发布。

## 警告

- 锁屏 Live 会跳过 Google 应用在锁屏进入 Live 时的解锁流程。本模块不修改
  热词检测或声纹识别；他人能否用 “Hey Google” 唤醒设备，取决于 Google
  应用的 Voice Match 与热词设置。如果设备会对非机主声音响应，附近的人也能
  触发助手，并在此开关开启时无需解锁直接进入 Live。请在可信环境中使用；
  不需要时关闭“锁屏进入 Live”，并在 Google 应用中启用 Voice Match 以限制
  陌生声音访问个人结果。
- 模块修改 GSA 内部流程，Google 应用更新后可能失效、崩溃或行为异常。
- 默认 Live 会影响所有热词唤醒；不需要时在模块 App 中关闭。
- 锁屏 Live 不会真正解除系统锁屏，也不修改锁屏密码/指纹。
- 本模块只修改 Live 的启动、锁屏显示和退出，不修改 SoundTrigger / DSP
  热词检测本身。
- 需要 LSPosed；按现状提供，无任何担保。本项目与 Google 无关。

## License

本项目代码采用 WTFPL v2 授权，完整文本见 [LICENSE](LICENSE)。

注意：`libs/api-82.jar` 是 Xposed API 82，仍遵循其原始 Apache-2.0
许可，不适用本项目的 WTFPL。
