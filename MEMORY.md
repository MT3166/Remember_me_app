# MEMORY.md
<!-- 核心状态机与索引 -->

## Meta [Strict]
- 最后更新：2026-09-15 20:29
- 项目画像：面向 5 岁女孩、荣耀 V8 Pro 的语音与文字日记 APP
- 技术栈：Kotlin、Jetpack Compose、Android Keystore、MiniMax/OpenAI

## 状态机 [Strict]
- **当前指针**：`.moss-mem/tasks/20260915-202742_task.md`
- **全局目标**：0.4.0 默认 MiniMax 语音识别及密钥复用，已交付 APK
- **最后状态**：✅ Completed
- **最后动作**：Completed: 已完成 Git 首次提交与远端推送：仅包含 README.md，未提交 Minimax Key

## 下一步指令 [Strict]
- 等待下一项开发任务
- 27 单元测试、44 Android 检查、构建、Lint 和 APK 签名通过；语音配置横竖窄屏截图通过。

## 暂存与备忘区 (Scratchpad) [Free]
<!-- 留给 Agent 的自由发挥空间 -->





















## 雷区与技术契约 [Strict/Append-only]
<!-- 只增不删 -->
- 语音接口固定 api.minimaxi.com/v1/speech_to_text，asr-1.0、language zh、AAC multipart；默认复用 MiniMax Key，不借用 GPT Key。
- 失败录音仅会话内重试，旋转保留，进程终止不能恢复；真实 API 和儿童语音未联调。无新增依赖。
- moss-mem state set 完成归档后遗留失效指针，validate --fix 失败；本次手动修复 Strict 区块，历史归档保存了 0.3.2 状态，0.4.0 详情以 docs/verification-0.4.0.md 为准。

## 已归档任务 [Strict/Append-only]
<!-- 保留最近 5-10 条记录 -->
- 2026-09-07 儿童日记初版至 0.3.2 → `.moss-mem/tasks/archive/20260907-210947_20260907-141958_task.md`
