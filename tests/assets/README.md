# D4 翻译切片 · 回归验收素材

2026-10-24 首轮验收所用音频,edge-tts 合成(音色见下),对应 D4 方案 §9 六条验收标准。
文本与音频一一对应,可直接改 txt 重新合成定制素材:

```bash
uv run edge-tts --voice en-US-AriaNeural --file tests/assets/en.txt --write-media /tmp/en.mp3
uv run edge-tts --voice zh-CN-XiaoxiaoNeural --file tests/assets/zh.txt --write-media /tmp/zh.mp3
uv run edge-tts --voice en-US-GuyNeural --file tests/assets/term.txt --write-media /tmp/term.mp3
```

| 素材 | 音色 | 内容 | 对应验收 |
|---|---|---|---|
| `en` | en-US-AriaNeural | JFK 就职演说片段(~85s) | §9-1 英文→中文全流程;§9-3 kill -9(translate 期间掐点) |
| `zh` | zh-CN-XiaoxiaoNeural | 自写分布式系统播客(~45s) | §9-2 中文源直通;§9-4 中文任务不受 LLM 缺配影响 |
| `term` | en-US-GuyNeural | Kubernetes/gRPC/API/React 技术播客(~55s) | §9-5 术语保留 |

## 注入方式(三选一)

管线只收公网 http(s) 直链(SSRF 预检拦内网,`file://` 不支持),本地素材需先变成直链:

1. **GitHub raw 分支**(首轮验收用法):素材推到 `d4-assets` 分支,取
   `https://raw.githubusercontent.com/<owner>/auris/d4-assets/en.mp3`;
2. 任意可达的公网直链(对象存储/文件服务);
3. **函数级验证**(不起服务、不烧管线):术语保留等 prompt 效果直接调
   `engine.dubbing.translate.translate()`,素材读对应 txt 分段即可。

## 首轮验收的坑(素材侧)

- raw.githubusercontent.com 间歇不可达(回源死),演练前先 `curl -sI` 探活;
- 公网文件服务大多不可用:0x0.st 停服、filebin 对非浏览器 UA 返 HTML、
  temp.sh PUT 301——别浪费时间重试。
