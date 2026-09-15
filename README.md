# 小小日记

面向 5 岁孩子的原生安卓日记 APP，首个目标设备为荣耀平板 V8 Pro。

当前版本 **0.4.1：MiniMax 默认模型迁移修复**。默认使用 `asr-1.0` 识别普通话，复用已配置的 MiniMax 密钥。文字默认使用 M3，沿用绿色声音画室，支持竖屏、横屏与窄屏。

最新试用包：`artifacts/little-diary-0.4.1-debug.apk`。可覆盖安装旧版，沿用原有日记与密钥。旧版 MiniMax 官方配置中的 `MiniMax-M2.5` 自动迁移为 `MiniMax-M3`，包括暂未选中的供应商配置；其他模型选择保留。新版保存后，在高级设置中手动选择 M2.5 也会保留。检查记录见 [0.4.1 检查记录](docs/verification-0.4.1.md)。

## 当前功能

- 文字编辑、MiniMax 普通话语音识别（可切换系统服务）、多段语音追加、系统中文朗读。
- LLM 校准建议与原文对照，采纳后仍可编辑；保留不确定词的确认问题。
- OpenAI Chat Completions 与 Anthropic Messages 兼容文字接口，可自定义地址、模型和密钥。
- OpenAI GPT Image 与 MiniMax 国内 `image-01` 绘本封面，正文先保存，封面可单独重试。
- 文本、图片分别选择供应商，官方 URL 与默认模型内置；每家供应商的 Key 独立记住，同供应商可共用文本 Key。
- 本机日记列表、草稿自动保存、删除确认、横竖屏自适应。
- 首页绿色录音入口、按月份翻阅；语音识别成功后进入校准确认，保存后进入阅读。
- 确认页支持听读、编辑和展开原文；采纳后的正文不会覆盖原始转写。
- 家长设置入口；API Key 使用 Android Keystore AES-GCM 加密保存。

## 构建

使用 Android Studio 打开根目录，或使用 JDK 17/21、Android SDK 36 和 Build Tools 36.1.0：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`local.properties` 中的 `sdk.dir` 指向本机 SDK，不提交到仓库。Gradle Wrapper 固定 8.14.3。

输出：`app/build/outputs/apk/debug/app-debug.apk`，这是供家庭试用的 debug 包。

在专用测试模拟器上执行协议与存储测试（不请求真实模型，不需要 API Key）：

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.littlediary.app.test/com.littlediary.app.ContractInstrumentation
```

## 接口设置

入口：**首页设置图标 → 家长验证 → 配置供应商**。文本、图片、语音使用独立标签页，选好供应商后通常只需填写 API Key 并保存。语音默认复用 MiniMax 的文本或图片 Key，也支持关闭复用并单独填写；不会复用 GPT 或自定义供应商的 Key。语音接口地址内置，无需填写。

| 供应商 | 默认文本模型 | 默认图片模型 |
| --- | --- | --- |
| OpenAI（GPT） | `gpt-4.1-mini` | `gpt-image-2` |
| MiniMax 国内 | `MiniMax-M3` | `image-01` |

可以混用供应商，例如 GPT 文本与 MiniMax 图片。切换供应商不会把旧供应商的 Key 自动带给新供应商；重新选回时可恢复此前配置。同一官方供应商下，两页均可开启共用并编辑“共用 API Key”，只需填写一次。若原来只有一侧填了 Key，就使用该 Key；两侧都有不同 Key 时优先文本 Key，编辑共用字段后同步两侧。改变为不同供应商时会关闭共用，但会给缺少独立 Key 的原供应商保留已有可用凭据。

“高级设置”可修改模型名称。选择“自定义接口”后可填地址，文本支持 OpenAI / Anthropic 兼容协议，图片支持 GPT Image / MiniMax 协议。旧的非预设地址和 Anthropic 配置会迁移为自定义并保留。

文本页提供连接测试，图片页可以独立生成测试图，无需文本 Key。两种测试均按供应商用量计费。正式日记封面仍先由文本模型提炼场景，再交给图片模型生成，因此完整日记流程需要配置文本模型。

所有 Key 和各供应商配置一起使用 Android Keystore 加密保存，不内置到 APK。界面草稿保存在 ViewModel 中，旋转屏幕保留；返回时若有未保存修改，会提供保存并返回、放弃修改、继续编辑三个选择。只有保存后的配置会跨进程恢复。

## 使用边界

- 语音录制为单声道 16 kHz AAC，通过 MiniMax 独立 ASR 接口上传后再交给 LLM 校准。单次最长 8 分钟，需要网络和账户额度。
- 识别失败的录音在当前应用会话中可重试，切换日记不会混用；横竖屏切换保留状态。退出后台停止录音并保留重试；系统终止进程后未转写录音不能恢复。成功识别或手动丢弃会删除缓存音频。
- 键盘支持语音不代表第三方 APP 可以调用系统识别服务。荣耀 V8 Pro 仍需真机检查。
- LLM 根据转写文字校准，无法保证恢复所有儿童发音错误；家长应确认歧义，不将模型建议当作确定事实。
- 朗读依赖系统安装的中文 TTS 音色；语音识别及朗读引擎可能需要联网。
- 日记与图片位于 APP 私有目录；模型设置位于禁止备份的加密文件。没有账户、云同步和导出功能，卸载或清除 APP 数据会删除日记。
- 校准向文字接口发送正文，生图先经文字模型提炼泛化场景，再向图片接口发送描述。提示词要求去除身份信息，但模型处理并非确定性脱敏。
- 家长算式用于减少孩子误改设置，不是身份认证或安全锁。
- 本版本未内置任何密钥。没有使用用户真实录音或真实模型调用作验证。

## 代码入口

- `DiaryUi.kt`、`MainActivity.kt`：界面、权限、生命周期与朗读。
- `ProviderSettingsUi.kt`：独立的供应商页面、文本/图片标签、Key 与高级配置。
- `DiaryViewModel.kt`：编辑、校准、封面任务和异步保存状态。
- `DiaryStore.kt`、`SettingsStore.kt`：原子写入、版本防回退、密钥加密。
- `ModelClient.kt`、`Models.kt`：协议构造、响应校验、地址规则。
- `ModelTransport.kt`：HTTP 执行边界，允许测试模拟响应并核验请求凭据；生产默认使用真实 HTTPS。
- `SpeechController.kt`、`SpeechClient.kt`：AAC 录音、MiniMax multipart 上传、重试取消与系统语音识别。

需求与验收计划见 [第一版方案](docs/first-version.md)，设计选择见 [已选方向](design-demos/direction-approved.md)。
