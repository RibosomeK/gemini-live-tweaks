# Gemini Live Tweaks

作者：Claude

一个只作用于 Google 应用（GSA）的 LSPosed 模块，提供两个 Gemini Live 相关功能：

1. **锁屏进入 Live**：在锁屏/熄屏状态下说 “Hey Google, let's talk”，绕过
   Google 的“必须先解锁才能启动 Live”限制，直接进入 Live。
2. **默认进入 Live**：普通 “Hey Google” 热词唤醒后不再停留在普通语音浮层，
   而是自动进入 Gemini Live。

> 本模块只负责 Live 行为。熄屏 "Hey Google" 的 DSP 热词修复在另一个独立
> 项目 [hotword-arch-fix](../hotword-arch-fix/) 中；两个模块可以一起安装，
> 也可以单独使用（单独使用本模块时，亮屏唤醒仍可触发默认 Live）。

## 功能实现

- **锁屏 Live**：GSA 在锁屏时启动 Live 前会先打开
  `...lockscreen.keyguard.authenticationactivity.AuthenticationActivity`。
  模块拦截该 Activity 的 `KeyguardManager.requestDismissKeyguard` 调用，
  伪造 `onDismissSucceeded` 回调，并在短暂窗口内让
  `isKeyguardLocked`/`isDeviceLocked` 返回 false，使 Live 启动流程继续。
  同时给 Live 的 `MainActivity` 设置 `showWhenLocked`，让界面显示在锁屏之上。
  这不会真正解除系统锁屏，也不修改锁屏密码/指纹。
- **默认 Live**：热词唤醒会先创建 `FloatyActivity`。模块在它创建后直接构造
  一个带 `RobinRouterArgs$FullScreen$ConversationMode` 的 `MainActivity`
  Intent 并启动，然后关闭浮层。

## 适用环境

- 已安装 LSPosed。
- Google 应用，测试版本 `17.68.28.ve.arm64`。
- OnePlus 15R `CPH2769`，OxygenOS `16.0.5.701(EX01)`，Android 16（SDK 36）。
- GSA 内部类名是混淆的，Google 应用更新后模块可能失效，需要重新适配。

## 安装与使用

1. 安装 `build/GeminiLiveTweaks.apk`。
2. 在 LSPosed 管理器中启用 **Gemini Live Tweaks**。
3. 作用域只勾选 Google（`com.google.android.googlequicksearchbox`）。
   本模块不需要 System Framework。
4. 重启手机或至少重启 Google 应用。
5. 默认设置下：亮屏或锁屏说 “Hey Google” 应直接进入 Gemini Live
   （锁屏时 Live 显示在锁屏之上）。若把 `debug.hotword.live.default` 设为 0，
   则可以用 “Hey Google, let's talk” 测试锁屏 Live 绕过路径。

两项功能默认开启。如需临时关闭（属性重启后失效）：

```bash
# 默认 Live 关闭；重启后会恢复默认开启
adb shell su -c 'setprop debug.hotword.live.default 0'

# 锁屏 Live 绕过关闭
adb shell su -c 'setprop debug.hotword.live.autoauth 0'
```

`autoauth=0` 时锁屏进入 Live 会恢复 Google 原生的“需要解锁”行为；
`default=0` 时普通唤醒恢复普通 Gemini 语音浮层。

如果之前安装过合并版 `moe.debug.hotwordarch`（包含 Live 钩子），请先禁用/
卸载旧模块，再安装本模块，避免重复 hook。

从旧版更新本模块后，建议使用：

```bash
adb install --no-incremental -r GeminiLiveTweaks.apk
```

然后重启 Google 应用（或手机）。

## 构建

依赖：

- JDK 17+（`javac`、`jar`、`keytool`）
- Android SDK Platform 36（`android.jar`）
- Android build-tools，包含 `aapt2`、`d8`、`zipalign`、`apksigner`
- Python 3
- 本仓库 `libs/api-82.jar`（Xposed API 82，已包含）

示例：

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/Android/Sdk
./build.sh
```

或直接指定：

```bash
ANDROID_JAR=/path/to/android-36/android.jar \
BUILD_TOOLS=/path/to/build-tools/36.0.0 \
./build.sh
```

输出：`build/GeminiLiveTweaks.apk`。

仓库内 `keystore/debug.keystore` 是公开调试签名，仅用于保持一致更新签名；
不要用于正式发布。

## 警告

- **隐私/安全**：本模块绕过了 Google 对锁屏启动 Gemini Live 的限制。
  锁屏状态下，附近的人可能直接唤起 Live 并与助手交互。请只在你自己的
  设备上使用，并自行评估隐私和安全风险。
- **需要 LSPosed**。模块修改 GSA 内部流程，Google 应用更新后可能失效、
  崩溃或产生未预期的助手行为；升级后请重新测试。
- **默认 Live 会影响所有热词唤醒**：每次说 “Hey Google” 都会进入 Live，
  而不是普通一问一答。若不想要此行为，设置 `debug.hotword.live.default=0`
  或禁用模块。
- **不会真正解锁手机**：锁屏 Live 只是在锁屏之上显示 Live 界面，系统锁屏
  仍然存在，不会改变锁屏密码/指纹。
- 本模块不包含 DSP 熄屏热词修复；熄屏唤醒需要另外安装
  [hotword-arch-fix](../hotword-arch-fix/)。
- 不要在非本人设备或生产环境使用；按现状提供，无任何担保。
- 本项目与 Google 无关，不包含 Google 的私有授权代码。
