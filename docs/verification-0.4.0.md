# 0.4.0 MiniMax 语音识别验证

- 按官方文档 https://platform.minimaxi.com/docs/api-reference/speech-to-text 实现。
- POST `https://api.minimaxi.com/v1/speech_to_text`，Bearer Key，`language: zh`。
- multipart：`model=asr-1.0`、`response_format=json`、`stream=false`、AAC 文件；读取 JSON `text`。
- 录音为单声道 16 kHz、64 kbps AAC ADTS，应用限制 480 秒，上传限制 50 MB。
- 默认复用 MiniMax 文本/图片 Key，包括切换供应商前保存的 MiniMax 配置；不借用 GPT 或自定义 Key，不从同一槽位缓存复活已清空 Key。可独立设置语音 Key，沿用 Keystore 加密存储。
- 默认值兼容旧版设置；文字默认 MiniMax-M3 不变，已保存选择保留。

## 已完成

- `assembleDebug`、`testDebugUnitTest`、`assembleDebugAndroidTest`、`lintDebug` 离线构建成功。
- 27 项 JUnit 测试通过，覆盖共享 Key 回归、语音默认值、独立 Key、供应商隔离、multipart 参数与边界。
- 模拟器上 44 项 Android 检查通过，覆盖旧设置迁移、JSON 成功/空内容/错误返回、AAC 实际录制、模拟失败重试、取消后不交付结果、日记归属。
- UI 在 1600x2560、2560x1600、800x1600 检查通过，切换方向保留语音标签页；截图位于 artifacts/speech-settings-*.png。
- Lint 0 errors、6 warnings、1 hint；提示主要为现有依赖版本更新。
- UI 首次运行遇到模拟器 System UI 无响应弹窗，关闭后重跑通过，无应用崩溃。

## 使用限制

- 未使用真实 API Key、儿童录音或荣耀 V8 Pro 联调；请求合同和错误响应使用模拟传输验证。
- 失败录音仅支持当前应用会话内重试，旋转屏幕保留；进程终止后不能恢复。成功识别或手动丢弃删除音频。
- 后台停止录音并取消上传，回到对应日记可重试；网络取消不会保证服务端停止计费。
- LLM 仍基于转写文字校准，需要家长确认儿童发音中的歧义。
