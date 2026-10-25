/**
 * 配音任务台服务层 —— 真实接口(P12-D6,经 gateway 归 ai-service)。
 *
 *   POST   /v1/dubbing            发起(body 带 url;幂等:同 url 在途回已有)
 *   GET    /v1/dubbing            列表(分页,纯读 DB)
 *   GET    /v1/dubbing/{id}       状态(running 时轮询本接口;Java 非终态才回源 engine)
 *   DELETE /v1/dubbing/{id}       删除
 *   GET    /v1/dubbing/{id}/asset?kind=audio|video|srt 产物流式代理(Range 透传)
 */

import { request, getToken, type PageResp } from './api'

// ---------- 类型(对齐后端 DubbingTaskResp) ----------

// init=已受理未开工,渲染与 running 同(进行中判断:['init','running'].includes(status))
export type DubbingStatus = 'init' | 'running' | 'completed' | 'failed'
export type DubKind = 'audio' | 'video'

export interface DubbingTask {
  /** 一律 String(P3 铁律:雪花 id 超 JS 2^53) */
  id: string
  title: string | null
  url: string
  kind: DubKind
  status: DubbingStatus
  /** 0..stageTotal-1;进行中表示"正在执行第 stageIndex+1 步" */
  stageIndex: number
  stageTotal: number
  audioBytes: number | null
  error: string | null
  /** UTC 无后缀(全链路 UTC 纪律,展示用 fmtUtc) */
  createdAt: string
}

// ---------- 双形态 stage 标签(音频六步/视频八步,渲染按 kind 取集合) ----------

export const dub_stages = {
  audio: [
    { key: 'download', label: '下载' },
    { key: 'transcribe', label: '转写' },
    { key: 'translate', label: '翻译' },
    { key: 'chunk', label: '切段' },
    { key: 'synth', label: '合成' },
    { key: 'concat', label: '拼接' },
  ],
  video: [
    { key: 'download', label: '下载' },
    { key: 'demux', label: '分离' },
    { key: 'transcribe', label: '转写' },
    { key: 'translate', label: '翻译' },
    { key: 'resplit', label: '重切' },
    { key: 'synth', label: '合成' },
    { key: 'align', label: '对齐' },
    { key: 'mux', label: '混流' },
  ],
} as const

export function stagesFor(kind: DubKind) {
  return dub_stages[kind] ?? dub_stages.audio
}

// ---------- 接口 ----------

export async function listDubbing(page = 1, size = 20): Promise<DubbingTask[]> {
  const data = await request<PageResp<DubbingTask>>(`/v1/dubbing?page=${page}&size=${size}`)
  return data.records   // 注意:后端 PageResp 序列化为 records(与 transcribe 同款),
                        // 取错字段会让 tasks=undefined → 页面渲染崩(实测 2026-10-25)
}

export async function getDubbing(id: string): Promise<DubbingTask> {
  return request<DubbingTask>(`/v1/dubbing/${id}`)
}

export async function submitDubbing(url: string): Promise<string> {
  const data = await request<{ recordId: string }>('/v1/dubbing', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url }),
  })
  return data.recordId
}

export async function deleteDubbing(id: string): Promise<void> {
  await request<void>(`/v1/dubbing/${id}`, { method: 'DELETE' })
}

// <audio>/<video> 标签带不了 Authorization 头,fetch 全量拿 blob 再 objectURL
// (本地 seek 可拖;Range 透传仍实现——浏览器直连/下载场景受益)
const assetUrlCache = new Map<string, string>()

export async function fetchAssetUrl(id: string, kind: 'audio' | 'video' | 'srt' = 'audio'): Promise<string> {
  const key = `${id}:${kind}`
  const cached = assetUrlCache.get(key)
  if (cached) return cached
  const headers: Record<string, string> = {}
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  const resp = await fetch(`/v1/dubbing/${id}/asset?kind=${kind}`, { headers })
  if (!resp.ok) throw new Error(`asset HTTP ${resp.status}`)
  const url = URL.createObjectURL(await resp.blob())
  assetUrlCache.set(key, url)
  return url
}

/** 字幕下载(srt blob → a[download] 触发;视频任务才有) */
export async function downloadSubtitle(id: string, title: string): Promise<void> {
  const url = await fetchAssetUrl(id, 'srt')
  const a = document.createElement('a')
  a.href = url
  a.download = `${(title || 'dubbing').slice(0, 60)}.srt`
  a.click()
}
