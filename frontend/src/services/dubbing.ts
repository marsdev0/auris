/**
 * Dubbing task-board service layer — real endpoints (P12-D6/D8, routed to ai-service via gateway).
 *
 *   POST   /v1/dubbing                          submit (body carries url; idempotent: in-flight url returns existing)
 *   GET    /v1/dubbing                          list (paginated, pure DB read)
 *   GET    /v1/dubbing/{id}                     status (poll while running; Java calls engine only for non-terminal)
 *   DELETE /v1/dubbing/{id}                     delete
 *   GET    /v1/dubbing/{id}/asset?kind=...      legacy streaming proxy (Range passthrough, fallback)
 *   GET    /v1/dubbing/{id}/asset-url?kind=...  presigned url issuing (D8)
 */

import { request, type PageResp } from './api'

// ---------- types (aligned with backend DubbingTaskResp) ----------

// init = accepted but not started, rendered same as running (in-flight check: ['init','running'].includes(status))
export type DubbingStatus = 'init' | 'running' | 'completed' | 'failed'
export type DubKind = 'audio' | 'video'

export interface DubbingTask {
  /** always String (P3 rule: snowflake ids exceed JS 2^53) */
  id: string
  title: string | null
  url: string
  kind: DubKind
  status: DubbingStatus
  /** 0..stageTotal-1; in-flight means "executing step stageIndex+1" */
  stageIndex: number
  stageTotal: number
  audioBytes: number | null
  error: string | null
  /** UTC without suffix (full-chain UTC discipline, rendered via fmtUtc) */
  createdAt: string
}

// ---------- dual-form stage labels (audio 6 steps / video 8 steps, picked by kind) ----------

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

// ---------- api ----------

export async function listDubbing(page = 1, size = 20): Promise<DubbingTask[]> {
  const data = await request<PageResp<DubbingTask>>(`/v1/dubbing?page=${page}&size=${size}`)
  return data.records   // note: backend PageResp serializes as records (same as transcribe);
                        // reading the wrong field leaves tasks=undefined and crashes the page (2026-10-25)
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

// D8: switch to asset-url presigned links — JS only fetches the credential, no longer moves bytes.
// <video>/<audio> hit object storage directly: native streaming + automatic Range
// (goodbye D6 full-blob waiting and memory footprint).
// Sign fresh on every click (microsecond local computation server-side), no cache —
// which also sidesteps stale short-lived urls.
export interface AssetUrlResp {
  url: string
  /** credential ttl in seconds; re-sign on expiry */
  expiresIn: number
}

export async function fetchAssetUrl(id: string, kind: 'audio' | 'video' | 'srt' = 'audio'): Promise<string> {
  const data = await request<AssetUrlResp>(`/v1/dubbing/${id}/asset-url?kind=${kind}`)
  return data.url
}

/** Subtitle download (presigned srt url via <a>; cross-origin ignores the download attr, new tab as fallback; video tasks only) */
export async function downloadSubtitle(id: string, title: string): Promise<void> {
  const url = await fetchAssetUrl(id, 'srt')
  const a = document.createElement('a')
  a.href = url
  a.target = '_blank'
  a.download = `${(title || 'dubbing').slice(0, 60)}.srt`
  a.click()
}
