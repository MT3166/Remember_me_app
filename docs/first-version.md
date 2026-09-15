# 儿童日记 APP 第一版

## 已确认需求

- 使用者为 5 岁女孩，主要说普通话，发音可能不标准。
- 安卓 APP，适配平板横竖屏，同时支持语音输入与文字输入、编辑。
- 目标设备为荣耀平板 V8 Pro；实际系统版本和第三方 APP 可调用的中文语音识别服务待真机检测。
- LLM 校准转写文字，支持自定义 OpenAI Chat Completions 和 Anthropic Messages 兼容接口。
- 文字与生图默认使用 MiniMax 国内服务，确认正文后生成一张图片封面。

## 建议交互与范围

1. 日记列表以封面、日期、标题展示，主入口为录音与新建文字日记。
2. 点按开始、停止语音输入，支持多段追加；输入文字后同样可以校准。
3. 原始文字、校准建议、最终正文分别保存；采纳前可以修改或保留原文。
4. 确认后先保存正文，再生成封面；封面失败允许单独重试，不丢失正文。
5. 本机保存日记和封面，支持重启后阅读、编辑、删除确认；草稿自动保存。
6. 大触控目标，录音状态清晰；平板横屏可并列显示正文与封面。
7. 家长设置中分别配置文字协议、地址、模型、密钥和生图配置。密钥由家长在设备上输入，通过 Android Keystore 保护，不写入源码或日志。

## 语音与校准边界

- 语音识别先生成文字，LLM 再结合上下文校准。仅靠转写文字不能可靠还原所有发音错误。
- 建议先使用 Android SpeechRecognizer；必须检查服务可用性和中文支持，不能承诺任意国产平板可用或离线识别。
- 系统识别不可用时仍可输入文字、使用键盘语音；完整的 APP 内语音体验可能需要另选云端 ASR。
- 自动纠正标点、明显错字与识别错误，保留第一人称、童言和实际经历，不扩写为成人作文。
- 对歧义保留原词，并给出待确认项；模型建议不能充当确定事实。
- 模型推理内容不得显示或保存为日记正文。接口失败、空响应、结构错误时保留原文。
- 封面建议采用绘本插画，以确认后的正文提炼场景；生成提示词不包含姓名、学校、住址等具体身份信息。

## MiniMax 接口核对

核对日期：2026-09-07。仅核对公开文档，尚未进行有密钥的真实请求。

| 功能 | 文档给出的地址或模型 |
| --- | --- |
| OpenAI 兼容基础地址 | https://api.minimax.cn/v1 |
| Anthropic 兼容基础地址 | https://api.minimax.cn/anthropic |
| 图片生成 | https://api.minimax.cn/v1/image_generation |
| 图片模型 | image-01 |

文字模型名可配置，最终使用用户账户可调用的模型。生图使用 MiniMax 原生协议，不能套用 OpenAI 图片响应格式。文档示例通过 `data.image_base64` 返回图片。

官方资料：

- https://platform.minimaxi.com/docs/api-reference/text-openai-api.md
- https://platform.minimaxi.com/docs/api-reference/text-anthropic-api.md
- https://platform.minimaxi.com/docs/guides/image-generation.md
- https://platform.minimaxi.com/docs/llms.txt

公开文档中的语音能力主要是语音合成；已核对的文字接口明确不支持音频输入，不能将其当作 ASR 接口。

## 技术与依赖（已获安装授权）

- Kotlin、Android Gradle Plugin、Gradle：编译原生安卓 APK。
- AndroidX Activity Compose、Compose BOM、Compose UI、Material 3、Lifecycle ViewModel Compose：界面、平板适配和生命周期状态管理。
- kotlinx-coroutines-android：执行网络和磁盘任务，避免阻塞界面。
- JUnit：验证协议解析、校准结果处理和持久化逻辑。
- 网络、JSON、本地存储、语音识别优先使用系统 API，避免额外模型 SDK。

用户已通过“OK”授权安装上述依赖。依赖下载到本机 Gradle 缓存；采用 Kotlin Compose 实现。

本机使用 Android Studio JDK 21、Gradle 8.14.3、Android SDK 36 和 Build Tools 36.1.0。

## 实施与验证

1. 建立安卓工程与日记本地存储 → 验证 APK 编译、草稿重启恢复、离线编辑。
2. 加入录音识别和两种文字协议 → 验证权限拒绝、识别服务缺失、多段追加、校准失败保留原文，以及两种响应解析。
3. 接入 MiniMax 封面生成 → 验证图片持久化、生图失败重试、正文编辑后的封面更新与过期请求隔离。
4. 在目标平板验收 → 验证横竖屏、字体放大、键盘遮挡和真实儿童普通话转写效果。

## 尚待确认

- 荣耀平板 V8 Pro 的实际系统版本与中文语音识别服务可用性，在设备连接后检测；键盘支持语音输入不代表 SpeechRecognizer 可用。
- 真实 MiniMax 模型调用与荣耀平板儿童语音效果验收，需要在设备中配置用户自己的密钥后完成。
