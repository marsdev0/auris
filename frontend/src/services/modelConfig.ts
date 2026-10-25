/**
 * 模型配置(BYOK)服务层 —— 真实接口(P12-1 §4,经 gateway 归 ai-service)。
 *
 *   GET  /v1/byok/list             列表(掩码回显)
 *   POST /v1/byok/save             保存(body 带 domain;落库即密文,换 key/换接入点才重置 unverified)
 *   POST /v1/byok/validate?domain= 连通性校验(engine 实测;llm 已通,tts/asr 待 engine 落地)
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
  /** model 是否必填(渲染上三域统一都显示;true 时保存轻拦截,如 llm/asr;
   *  tts 统一可选,edge_tts 无模型概念,其余可填 tts-1/speech-1.5 等) */
  needsModel?: boolean
  note?: string
}

// ---------- provider 选项(静态展示数据,与后端 provider 枚举对齐) ----------

export const llmProviders: ProviderMeta[] = [
  { value: 'openai_compat', label: 'OpenAI 兼容接口', needsKey: true, needsModel: true, note: '硅基流动/DeepSeek/one-api 中转等' },
  { value: 'dashscope', label: '阿里云百炼', needsKey: true, needsModel: true },
]

export const ttsProviders: ProviderMeta[] = [
  { value: 'edge_tts', label: 'Edge TTS', needsKey: false, note: '免费 · 无需 key · 无模型概念' },
  { value: 'openai_tts', label: 'OpenAI TTS', needsKey: true, note: 'model 可选,默认 tts-1' },
  { value: 'fish_audio', label: 'Fish Audio', needsKey: true, note: 'model 可选;支持音色克隆' },
]

export const asrProviders: ProviderMeta[] = [
  { value: 'dashscope', label: '阿里云 DashScope', needsKey: true, needsModel: true, note: 'qwen-audio 文件转写,词级时间戳 ✅' },
  { value: 'openai_whisper', label: 'OpenAI Whisper API', needsKey: true, needsModel: true, note: '无词级时间戳,配音不可用 ❌' },
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
 * llm 域已通(engine _PROBES 仅注册 llm);tts/asr 待 engine 侧落地,先显式拦住——
 * 否则 engine KeyError 兜底会被误解为"配置校验失败"
 */
export async function validateConfig(domain: ModelDomain): Promise<boolean> {
  if (domain !== 'llm') {
    throw new Error('该域校验暂未上线(engine 待支持)')
  }
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
