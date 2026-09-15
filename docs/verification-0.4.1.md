# 0.4.1 默认模型迁移

0.4.0 仅修改新配置的默认模型，旧安装保存的 M2.5 仍然显示并用于请求。0.4.1 在 SettingsStore.decode 中按配置版本迁移：版本小于 4 的 MiniMax 官方文本 M2.5（忽略大小写与首尾空格）更新为 MiniMax-M3，同时迁移 textProfiles 中缓存的 MiniMax 配置。

配置序列化版本升为 4。新版保存后的显式模型选择保留；OpenAI、自定义接口、其他 MiniMax 模型、图片模型、语音配置和密钥不修改。旧版没有记录模型是否由用户手动选择，因此旧配置中的官方 M2.5 都按本次要求迁移；仍需 M2.5 时可在新版高级设置中重新选择并保存。

回归覆盖旧配置版本 1/2/3、无版本字段、缓存供应商切回、请求 model 字段、加密保存重载，以及新版明确选择 M2.5 的保留。

验证结果：assembleDebug、testDebugUnitTest、assembleDebugAndroidTest、lintDebug 全部成功；27 项单元测试、模拟器 50 项 Android 合同检查通过。未调用真实收费接口。本次没有布局修改。
