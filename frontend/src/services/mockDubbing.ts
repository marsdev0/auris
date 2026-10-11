/**
 * 配音任务台服务层 —— **全部为 mock_ 前缀占位实现**。
 *
 * 对应后端契约(P12-0 §2/§4):
 *   POST /v1/dubbing            发起(Java 侧先做 BYOK 闸门+配额)
 *   GET  /v1/dubbing/list       任务列表(状态机:running/completed/failed)
 *   POST /v1/dubbing/retry      断点续跑(从失败 stage 恢复,body 带 id)
 *   POST /v1/dubbing/delete     删除(body 带 id)
 *   GET  /v1/dubbing/asset      产物凭证(流式代理,支持 Range)
 *
 * 后端就绪后:删除本文件,在 services/api.ts 按契约实现同名业务函数,页面只改 import。
 */

import { gateMissing } from './modelConfig'

// ---------- 类型(契约,后续真实实现沿用) ----------

export type DubbingStatus = 'running' | 'completed' | 'failed'

export interface DubbingTask {
  /** 一律 String(P3 铁律:雪花 id 超 JS 2^53) */
  id: string
  title: string
  url: string
  durationSec: number
  status: DubbingStatus
  /** 0..10;running 时表示"正在执行第 stageIndex+1 步" */
  stageIndex: number
  error?: string
  /** UTC 无后缀 */
  createdAt: string
}

// ---------- mock 数据:10 步管线(与 P12-0 §1 管线图一一对应) ----------

export const mock_stages = [
  { key: 'download', label: '下载' },
  { key: 'extract', label: '音轨' },
  { key: 'asr', label: '转写' },
  { key: 'segment', label: '断句' },
  { key: 'terms', label: '术语' },
  { key: 'translate', label: '翻译' },
  { key: 'resplit', label: '切分' },
  { key: 'tts', label: 'TTS' },
  { key: 'align', label: '对齐' },
  { key: 'mix', label: '混流' },
] as const

/** mock 占位视频(真实场景 = asset 流式代理地址) */
export const mock_sampleVideoUrl = 'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4'

// ---------- mock 存储(模块级,刷新重置) ----------

let mock_nextId = 4

const mock_taskStore: DubbingTask[] = [
  {
    id: 'mock_1001',
    title: 'Understanding React Server Components',
    url: 'https://www.bilibili.com/video/BV1mock1001',
    durationSec: 754,
    status: 'completed',
    stageIndex: 10,
    createdAt: '2026-10-09T14:20:00',
  },
  {
    id: 'mock_1002',
    title: 'What is Database Sharding?',
    url: 'https://www.bilibili.com/video/BV1mock1002',
    durationSec: 512,
    status: 'running',
    stageIndex: 4,
    createdAt: '2026-10-10T07:40:00',
  },
  {
    id: 'mock_1003',
    title: 'Kubernetes Networking Deep Dive',
    url: 'https://www.bilibili.com/video/BV1mock1003',
    durationSec: 1520,
    status: 'failed',
    stageIndex: 7,
    error: 'TTS 合成失败:edge-tts 连接超时(第 3 次重试后放弃,已保留断点)',
    createdAt: '2026-10-08T09:12:00',
  },
]

// ---------- mock 实现 ----------

function sleep(ms: number) {
  return new Promise(r => setTimeout(r, ms))
}

/** GET /v1/dubbing/list —— 任务台列表 */
export async function mock_listTasks(): Promise<DubbingTask[]> {
  await sleep(200)
  return [...mock_taskStore].sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
}

/** 发起前的 BYOK 闸门(本地先拦一次,真实后端 403 MODEL_CONFIG_REQUIRED 兜底) */
export function mock_gateCheck(): { ok: boolean; missing: string[] } {
  const missing = gateMissing()
  return { ok: missing.length === 0, missing }
}

/** POST /v1/dubbing —— 发起(闸门通过后才会走到) */
export async function mock_submitDubbing(url: string): Promise<DubbingTask> {
  await sleep(400)
  const task: DubbingTask = {
    id: `mock_100${mock_nextId++}`,
    // mock:真实场景由 sources 回填 <title>(P5 已有先例)
    title: `《新任务》${url.slice(-12)}`,
    url,
    durationSec: 600 + Math.floor(Math.random() * 600),
    status: 'running',
    stageIndex: 0,
    createdAt: new Date().toISOString().slice(0, 19),
  }
  mock_taskStore.unshift(task)
  return task
}

/** 心跳推进:页面每 2.2s 调一次,running 任务走一步;mock 演示"流水灯"用 */
export function mock_tickTasks(): DubbingTask[] {
  for (const t of mock_taskStore) {
    if (t.status !== 'running') continue
    t.stageIndex += 1
    if (t.stageIndex >= mock_stages.length) {
      t.stageIndex = mock_stages.length
      t.status = 'completed'
    }
  }
  return [...mock_taskStore].sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
}

/** POST /v1/dubbing/retry —— 断点续跑:从失败的 stage 恢复(P12 验收线之一) */
export async function mock_retryTask(id: string): Promise<void> {
  await sleep(300)
  const t = mock_taskStore.find(x => x.id === id)
  if (t && t.status === 'failed') {
    t.status = 'running'
    t.error = undefined
  }
}

/** POST /v1/dubbing/delete */
export async function mock_deleteTask(id: string): Promise<void> {
  await sleep(200)
  const i = mock_taskStore.findIndex(x => x.id === id)
  if (i >= 0) mock_taskStore.splice(i, 1)
}

// ---------- 展示工具 ----------

export function fmtDur(sec: number): string {
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

/** 当前进行中的步骤名 */
export function currentStageLabel(t: DubbingTask): string {
  if (t.status === 'completed') return '已完成'
  return mock_stages[Math.min(t.stageIndex, mock_stages.length - 1)].label
}

export function statusBadge(s: DubbingStatus): { text: string; cls: string } {
  if (s === 'completed') return { text: '已完成', cls: 'bg-green-50 text-green-600' }
  if (s === 'failed') return { text: '失败', cls: 'bg-red-50 text-red-600' }
  return { text: '进行中', cls: 'bg-blue-50 text-blue-600' }
}
