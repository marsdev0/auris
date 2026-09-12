/**
 * Java 服务 API 客户端(经 vite proxy)。
 *
 * 两条铁律(P3 契约):
 *  1. recordId 一律按 String 处理——19 位雪花超出 JS Number 2^53,
 *     任何 Number()/parseInt 都会让尾数变 0,之后按 id 查全 404;
 *  2. 接口返回的时间是 UTC 无后缀(全链路 UTC 决策),展示前必须补 'Z' 转本地。
 */

const TOKEN_KEY = 'auris_token'

export interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

// ---------- token ----------

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

// ---------- 时间:UTC → 本地 ----------

/** ISO 无后缀(UTC) → 本地时间字符串;空值返回 '-' */
export function fmtUtc(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso.endsWith('Z') ? iso : iso + 'Z')
  if (isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 毫秒时长 → "1分32秒" */
export function fmtDuration(ms: number | null | undefined): string {
  if (ms == null) return '-'
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}秒`
  return `${Math.floor(s / 60)}分${s % 60}秒`
}

// ---------- 请求 ----------

export class ApiError extends Error {
  code: number
  constructor(code: number, msg: string) {
    super(msg)
    this.code = code
  }
}

/** 401 时广播,App.vue 监听后清 token 回登录页 */
export const authExpired = new EventTarget()

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers)
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const resp = await fetch(path, { ...init, headers })

  // 后端 AurisException 渲染的业务错误体也是 {code,msg,data}
  const body = (await resp.json().catch(() => null)) as ApiResponse<T> | null
  if (resp.status === 401 || body?.code === 401_001) {
    authExpired.dispatchEvent(new Event('expired'))
    throw new ApiError(401_001, '登录已过期,请重新登录')
  }
  if (!body) throw new ApiError(-1, `HTTP ${resp.status}`)
  if (body.code !== 200_000) throw new ApiError(body.code, body.msg ?? '请求失败')
  return body.data
}

// ---------- 类型(对齐后端 DTO) ----------

export interface LoginResp {
  accessToken: string
  refreshToken?: string
}

/** POST /v1/transcribe/task/start 的响应 —— recordId 是 String,严禁转 Number */
export interface SubmitResp {
  recordId: string
}

export interface RecordDetail {
  recordId: string
  status: 'transcribing' | 'completed' | 'failed' | string
  progress: number
  result: { text: string } | null
  errorMsg: string | null
}

export interface RecordItem {
  recordId: string
  title: string | null
  status: 'transcribing' | 'completed' | 'failed' | string
  durationMs: number | null
  createdAt: string // UTC 无后缀
}

export interface PageResp<T> {
  total: number
  current: number
  size: number
  records: T[]
}

// ---------- API ----------

export async function login(username: string, password: string): Promise<LoginResp> {
  // 登录本身不带 token,单独走裸 fetch 以免递归
  const resp = await fetch('/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const body = (await resp.json()) as ApiResponse<LoginResp>
  if (body.code !== 200_000) throw new ApiError(body.code, body.msg)
  return body.data
}

export async function register(username: string, password: string, nickname?: string): Promise<void> {
  const resp = await fetch('/v1/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, nickname }),
  })
  const body = (await resp.json()) as ApiResponse<null>
  if (body.code !== 200_000) throw new ApiError(body.code, body.msg)
}

/** 提交异步转写任务,返回 recordId(String) */
export async function submitTask(file: File): Promise<SubmitResp> {
  const form = new FormData()
  form.append('audio', file)
  return request<SubmitResp>('/v1/transcribe/task/start', { method: 'POST', body: form })
}

/** 轮询/详情 */
export async function getRecord(recordId: string): Promise<RecordDetail> {
  return request<RecordDetail>(`/v1/transcribe/records/${recordId}`)
}

/** 历史分页 */
export async function getRecords(page: number, size: number): Promise<PageResp<RecordItem>> {
  return request<PageResp<RecordItem>>(`/v1/transcribe/records?page=${page}&size=${size}`)
}

/** 删除(硬删) */
export async function deleteRecord(recordId: string): Promise<void> {
  await request<null>(`/v1/transcribe/records/${recordId}`, { method: 'DELETE' })
}
