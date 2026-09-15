# 内置供应商接口依据

2026-09-07 核对官方文档。未使用真实 API Key 发起模型请求。

## OpenAI

- 文本基础地址：`https://api.openai.com/v1`，使用 Chat Completions。
- 默认文本模型：`gpt-4.1-mini`，文档明确支持 Chat Completions；适用于这里的短文本校准，未做最新型号迁移。
- 图片完整地址：`https://api.openai.com/v1/images/generations`。
- 默认图片模型：`gpt-image-2`。请求使用 `size=1536x1024`、`quality=medium`、`output_format=jpeg`、`n=1`，读取 `data[0].b64_json`。
- GPT Image API 返回 base64，不发送 MiniMax 的 `aspect_ratio`、`response_format=base64` 参数。
- 官方文档提示 GPT Image 的账户权限可能需要组织验证，具体可用性以用户账号与真实调用为准。

依据：

- https://developers.openai.com/api/docs/models/gpt-4.1-mini.md
- https://developers.openai.com/api/docs/guides/image-generation.md

## MiniMax 国内

- 文本基础地址：`https://api.minimax.cn/v1`。0.3.1 按用户要求将默认文本模型改为官方示例中的 `MiniMax-M3`；已保存的模型选择保留，模型名可在高级设置修改。
- 图片完整地址：`https://api.minimax.cn/v1/image_generation`，默认 `image-01`。
- 请求使用 `aspect_ratio=4:3`、`response_format=base64`、`n=1`，读取 `data.image_base64[0]`。
- MiniMax 的 OpenAI 兼容文字请求保留 `reasoning_split`；OpenAI 官方文字请求不发送该扩展。

依据：

- https://platform.minimaxi.com/docs/api-reference/text-openai-api.md
- https://platform.minimaxi.com/docs/guides/image-generation.md

预设供应商的官方地址由代码提供，不能被供应商缓存里的 URL 改写。自定义协议和地址单独配置，不将它们伪装为官方供应商。
