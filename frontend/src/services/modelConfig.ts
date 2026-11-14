/**
 * 模型配置(BYOK)服务层 —— 真实接口(P12-1 §4,经 gateway 归 ai-service)。
 *
 *   GET  /v1/byok/list             列表(掩码回显)
 *   POST /v1/byok/save             保存(body 带 domain;落库即密文,换 key/换接入点才重置 unverified)
 *   POST /v1/byok/validate?domain= 连通性校验(engine 实测;三域均已上线 P13 D3)
 *   POST /v1/byok/delete?domain=   删除
 */

import { fmtUtc, request } from './api'

// ---------- 类型(对齐后端 VO/Req) ----------

export type ModelDomain = 'llm' | 'tts' | 'asr'

export type ConfigStatus = 'verified' | 'failed' | 'unverified'

export interface ModelConfig {
  domain: ModelDomain
  provider: string
  baseUrl?: string | null
  /** 掩码回显:sk-***ab12;明文只在保存上行出现一次,永不回显 */
  apiKeyMasked?: string | null
  model?: string | null
  voice?: string | null
  status: ConfigStatus
  /** UTC 无后缀(全链路 UTC 纪律,展示用 fmtUtc) */
  lastCheckedAt?: string | null
  /** 校验失败原因(validate 落地后回填) */
  validateDetail?: string | null
  /** 校验延迟 ms(validate 落地后回填) */
  lastLatencyMs?: number | null
}

/** 表单草稿(保存时上行的形状;apiKey 留空 = 不修改已有 key) */
export interface ConfigDraft {
  provider: string
  baseUrl: string
  apiKey: string
  model: string
  voice: string
}

export interface ProviderMeta {
  value: string
  label: string
  needsKey: boolean
  /** model 是否必填(llm/asr 需要;tts 统一可选;DeepSeek ASR 无模型概念不需要) */
  needsModel?: boolean
  /** 预设端点:选中即回填 baseUrl,保留可编辑(代理/中转场景);自定义项为空 */
  presetBaseUrl?: string
  /** 待实现:下拉禁用可见(路线图展示),不入后端白名单,不可保存 */
  comingSoon?: boolean
  note?: string
}

// ---------- provider 选项(静态展示数据,与后端白名单/engine factory 同源) ----------
// 同源三处:此数组 / ai-service ASR_PROVIDERS / engine byok/factory 分支;
// 未实现的选项不入列(dashscope-asr 待 Filetrans 插件落地回归;fish_audio 待 engine 实现)

export const llmProviders: ProviderMeta[] = [
  { value: 'openai_compat', label: 'OpenAI 兼容接口', needsKey: true, needsModel: true, note: 'DeepSeek/硅基流动/one-api 中转等' },
  { value: 'dashscope', label: '阿里云百炼', needsKey: true, needsModel: true, presetBaseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', note: '百炼 compatible-mode(OpenAI 兼容)' },
]

export const ttsProviders: ProviderMeta[] = [
  { value: 'edge_tts', label: 'Edge TTS', needsKey: false, note: '免费 · 无需 key · 无模型概念' },
  { value: 'openai_tts', label: 'OpenAI TTS 兼容', needsKey: true, note: 'model 可选,默认 tts-1 · 填兼容端点 baseUrl' },
  { value: 'fish_audio', label: 'Fish Audio', needsKey: true, comingSoon: true, note: '音色克隆 · engine 插件待实现' },
]

export const asrProviders: ProviderMeta[] = [
  {
    value: 'deepseek', label: 'DeepSeek 语音识别', needsKey: true, needsModel: false,
    presetBaseUrl: 'https://api.deepseek.com',
    note: '与 LLM 复用同一个 key · ≤120s/次 · 无时间戳 → 播报✓ 视频△',
  },
  {
    value: 'siliconflow', label: '硅基流动 SenseVoice', needsKey: true, needsModel: true,
    presetBaseUrl: 'https://api.siliconflow.cn/v1',
    note: '注册送额度 · 无时长限制 · 无时间戳 → 播报✓ 视频△ · model 填 FunAudioLLM/SenseVoiceSmall',
  },
  {
    value: 'glm', label: 'GLM-ASR-2512', needsKey: true, needsModel: false,
    presetBaseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    note: '≤30s/次(仅短视频/播报) · 支持热词 · 无时间戳 → 播报✓ 视频△',
  },
  {
    value: 'openai_compat', label: 'OpenAI 兼容(自定义)', needsKey: true, needsModel: true,
    note: '自建 vLLM/omlx/one-api 中转 · 有 verbose_json 句级时间戳的端点可支撑视频字幕',
  },
  {
    value: 'dashscope', label: '阿里云 Filetrans', needsKey: true, needsModel: false,
    comingSoon: true,
    note: '词级时间戳+说话人分离 · 视频字幕最优解 · 插件待实现(需公网存储前置)',
  },
]

/** edge-tts 音色下拉 */
export const edgeVoices = [
  { value: 'zh-CN-XiaoxiaoNeural', label: '晓晓(女)' },
  { value: 'zh-CN-YunxiNeural', label: '云希(男)' },
  { value: 'zh-CN-YunjianNeural', label: '云健(男·沉稳)' },
]

// ---------- API ----------

/** GET /v1/byok/list —— 后端只返回已配置的域,这里补全成三域 Record(null=未配置) */
export async function listConfigs(): Promise<Record<ModelDomain, ModelConfig | null>> {
  const list = await request<ModelConfig[]>('/v1/byok/list')
  const res: Record<ModelDomain, ModelConfig | null> = { llm: null, tts: null, asr: null }
  for (const c of list) res[c.domain] = c
  return res
}

/** POST /v1/byok/save —— apiKey 留空 = 沿用旧 key(编辑语义) */
export function saveConfig(domain: ModelDomain, draft: ConfigDraft): Promise<boolean> {
  return request<boolean>('/v1/byok/save', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      domain,
      provider: draft.provider,
      baseUrl: draft.baseUrl || null,
      apiKey: draft.apiKey || null,
      model: draft.model || null,
      voice: draft.voice || null,
    }),
  })
}

/**
 * POST /v1/byok/validate?domain=xx —— engine 实测连通性,ai 侧回写 verified/failed。
 * 三域均已上线(P13 D3,2026-11-11):llm 拉模型列表+1token 补全;tts 合成样音;asr 识别 1s 静音。
 */
export async function validateConfig(domain: ModelDomain): Promise<boolean> {
  return request<boolean>(`/v1/byok/validate?domain=${domain}`, { method: 'POST' })
}

/** POST /v1/byok/delete?domain=xx */
export function deleteConfig(domain: ModelDomain): Promise<boolean> {
  return request<boolean>(`/v1/byok/delete?domain=${domain}`, { method: 'POST' })
}

/** P12 配音闸门:返回"缺失或未验证"的域(BYOK 契约:未 verified 视为缺失,后端 403 兜底) */
export async function gateMissing(): Promise<ModelDomain[]> {
  const configs = await listConfigs()
  return (Object.keys(configs) as ModelDomain[]).filter(d => {
    const c = configs[d]
    return !c || c.status !== 'verified'
  })
}

// ---------- 展示工具 ----------

export function statusBadge(s: ConfigStatus): { text: string; cls: string } {
  if (s === 'verified') return { text: '已验证', cls: 'bg-green-50 text-green-600' }
  if (s === 'failed') return { text: '校验失败', cls: 'bg-red-50 text-red-600' }
  return { text: '未验证', cls: 'bg-gray-100 text-gray-500' }
}

export function fmtChecked(cfg: ModelConfig | null): string {
  return cfg?.lastCheckedAt ? fmtUtc(cfg.lastCheckedAt) : '-'
}
