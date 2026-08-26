/**
 * 实时 ASR WebSocket 客户端。
 *
 * 协议(engine /v1/asr/stream,与 scripts/ws_stream_client.py 同口径):
 *   client → server: {"type":"start","config":{"lang":"zh","provider":"..."}}  (text)
 *                    binary PCM 16bit LE mono @16k 帧                          (binary)
 *                    {"type":"stop"}                                          (text)
 *   server → client: {"is_final":bool,"text":str,"beg_ms":int|null,"end_ms":int|null}
 *                    {"type":"error","code":"ASR_FAILED","message":str}
 */

export interface AsrPartial {
  is_final: boolean
  text: string
  beg_ms: number | null
  end_ms: number | null
}

export interface AsrClientOptions {
  provider?: string
  lang?: string
  onResult: (r: AsrPartial) => void
  onError: (message: string) => void
  onClose: () => void
}

/** vite proxy 把 /v1/asr 转发到 engine:18000(见 vite.config.ts),WS 同路径 */
const STREAM_URL = `ws://${location.host}/v1/asr/stream`

export class AsrClient {
  private ws: WebSocket | null = null
  private closed = false
  private opts: AsrClientOptions

  constructor(opts: AsrClientOptions) {
    this.opts = opts
  }

  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(STREAM_URL)
      ws.binaryType = 'arraybuffer'
      this.ws = ws

      ws.onopen = () => {
        ws.send(JSON.stringify({
          type: 'start',
          config: { lang: this.opts.lang ?? 'zh', provider: this.opts.provider },
        }))
        resolve()
      }
      ws.onerror = () => reject(new Error('WebSocket 连接失败(确认 engine 已启动在 18000)'))
      ws.onmessage = (ev) => this.handleMessage(ev)
      ws.onclose = () => {
        if (!this.closed) this.opts.onClose()
      }
    })
  }

  private handleMessage(ev: MessageEvent) {
    if (typeof ev.data !== 'string') return
    let msg: any
    try {
      msg = JSON.parse(ev.data)
    } catch {
      return
    }
    if (msg.type === 'error') {
      this.opts.onError(msg.message ?? '未知错误')
      return
    }
    if (typeof msg.is_final === 'boolean') {
      this.opts.onResult(msg as AsrPartial)
    }
  }

  sendAudio(pcm: ArrayBuffer) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(pcm)
    }
  }

  stop() {
    this.ws?.send(JSON.stringify({ type: 'stop' }))
  }

  close() {
    this.closed = true
    this.ws?.close()
  }
}
