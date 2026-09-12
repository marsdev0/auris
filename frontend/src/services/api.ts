/**
 * Java 服务 API 客户端(经 vite proxy)。
 *
 * 两条铁律(P3 契约):
 *  1. recordId 一律按 String 处理——19 位雪花超出 JS Number 2^53,
 *     任何 Number()/parseInt 都会让尾数变 0,之后按 id 查全 404;
 *  2. 接口返回的时间是 UTC 无后缀(全链路 UTC 决策),展示前必须补 'Z' 转本地。
 */

const TOKEN_KEY = 'auris_token'
const PROFILE_KEY = 'auris_profile'

// ---------- 会话 profile(登录响应缓存,/me 未就绪时的降级数据源) ----------

export interface UserProfile {
  username: string
  nickname?: string | null
  avatarUrl?: string | null
  createdAt?: string | null // UTC 无后缀
}

export function getProfile(): UserProfile | null {
  const raw = localStorage.getItem(PROFILE_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as UserProfile
  } catch {
    return null
  }
}

export function setProfile(p: UserProfile) {
  localStorage.setItem(PROFILE_KEY, JSON.stringify(p))
}

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
  localStorage.removeItem(PROFILE_KEY)
}

/** 从 JWT payload 解 username(展示用途,无需验签;token 里只有 username,无昵称/注册时间) */
export function decodeUsername(): string | null {
  const token = getToken()
  if (!token) return null
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    return typeof payload.username === 'string' ? payload.username : null
  } catch {
    return null
  }
}

// ---------- 个人信息 ----------

export interface MeResp {
  username: string
  nickname?: string | null
  avatarUrl?: string | null
  createdAt?: string | null // UTC 无后缀
}

/**
 * GET /v1/auth/me —— 后端接口就绪前的降级链:
 * /me 接口 > 登录时缓存的 profile(含昵称/头像) > JWT payload 的 username。
 * 后端补上 /me 后此函数无需改动,自动点亮。
 */
export async function getMe(): Promise<MeResp> {
  try {
    return await request<MeResp>('/v1/auth/me')
  } catch {
    const cached = getProfile()
    return {
      username: cached?.username ?? decodeUsername() ?? '未知用户',
      nickname: cached?.nickname ?? null,
      avatarUrl: cached?.avatarUrl ?? null,
      createdAt: cached?.createdAt ?? null,
    }
  }
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
  username?: string
  nickname?: string | null
  avatarUrl?: string | null
  createdAt?: string | null // UTC 无后缀
}

/** POST /v1/transcribe/task/start 的响应 —— recordId 是 String,严禁转 Number */
export interface SubmitResp {
  recordId: string
}

export interface RecordDetail {
  recordId: string
  status: 'downloading' | 'transcribing' | 'completed' | 'failed' | string
  progress: number
  title: string | null
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

/** 贴 URL 提交(engine 抓取+转写,recordId 同款 String) */
export async function submitUrl(url: string): Promise<SubmitResp> {
  return request<SubmitResp>('/v1/transcribe/url', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url }),
  })
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
