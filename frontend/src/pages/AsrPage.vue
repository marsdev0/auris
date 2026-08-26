<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { AsrClient, type AsrPartial } from '../services/asrClient'
import { decodeToPcm16k, framePcm, FRAME_MS } from '../utils/pcm'

/** 实时转写页面:上传音频 → 1x 节奏推流 → partial/final 实时渲染 */
const PROVIDER = 'qwen-audio-streaming'

type Phase = 'idle' | 'decoding' | 'streaming' | 'done' | 'error'

const phase = ref<Phase>('idle')
const errorMsg = ref('')
const fileName = ref('')
const fileInput = ref<HTMLInputElement | null>(null)

/** 已收口的 final 句子 */
const finals = ref<{ text: string; beg_ms: number | null; end_ms: number | null }[]>([])
/** 当前句子的 partial 预览(覆盖渲染——服务端实测为句内累积语义) */
const currentText = ref('')
const stats = ref({ partials: 0, finals: 0, firstCharMs: 0, elapsedMs: 0 })

const transcriptEl = ref<HTMLElement | null>(null)

let client: AsrClient | null = null
let t0 = 0

async function onFileChosen(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  reset()
  fileName.value = file.name
  phase.value = 'decoding'

  try {
    const { pcm } = await decodeToPcm16k(file)
    await startStream(framePcm(pcm))
  } catch (err) {
    phase.value = 'error'
    errorMsg.value = err instanceof Error ? err.message : String(err)
  }
}

async function startStream(frames: ArrayBuffer[]) {
  phase.value = 'streaming'
  t0 = performance.now()

  client = new AsrClient({
    provider: PROVIDER,
    onResult: (r) => onResult(r),
    onError: (msg) => {
      phase.value = 'error'
      errorMsg.value = msg
      client?.close()
    },
    onClose: () => {
      // stop 后服务端发完余量 final 会关闭连接
      if (phase.value === 'streaming') finish()
    },
  })
  await client.connect()

  // 1x 实时节奏发送(与 ws_stream_client.py --realtime 同口径)——
  // 云端 VAD 断句依赖真实节奏,全速发送会导致断句行为失真
  for (const f of frames) {
    if (phase.value !== 'streaming') break
    client.sendAudio(f)
    await sleep(FRAME_MS)
  }
  if (phase.value === 'streaming') {
    client.stop() // 触发 finish-task;余量 final 到齐后连接关闭 → finish()
  }
}

function onResult(r: AsrPartial) {
  if (r.is_final) {
    finals.value.push({ text: r.text, beg_ms: r.beg_ms, end_ms: r.end_ms })
    currentText.value = ''
    stats.value.finals++
  } else {
    if (!r.text) return
    if (stats.value.partials === 0) {
      stats.value.firstCharMs = Math.round(performance.now() - t0)
    }
    currentText.value = r.text
    stats.value.partials++
  }
  stats.value.elapsedMs = Math.round(performance.now() - t0)
  void nextTick(() => transcriptEl.value?.scrollTo({ top: transcriptEl.value.scrollHeight }))
}

function finish() {
  phase.value = 'done'
  client?.close()
}

function reset() {
  client?.close()
  client = null
  phase.value = 'idle'
  errorMsg.value = ''
  fileName.value = ''
  finals.value = []
  currentText.value = ''
  stats.value = { partials: 0, finals: 0, firstCharMs: 0, elapsedMs: 0 }
  if (fileInput.value) fileInput.value.value = ''
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

const phaseLabel: Record<Phase, string> = {
  idle: '待上传',
  decoding: '解码音频…',
  streaming: '识别中…',
  done: '完成',
  error: '出错',
}
</script>

<template>
  <div class="min-h-screen bg-auris-base text-auris-text">
    <div class="mx-auto max-w-3xl px-6 py-12">
      <header class="mb-10">
        <h1 class="text-2xl font-bold tracking-tight">auris · 实时语音识别</h1>
        <p class="mt-2 text-sm text-auris-text-secondary">
          上传音频文件,按实时节奏推流至 engine(provider:
          <code class="rounded bg-auris-elevated px-1.5 py-0.5 text-auris-accent">{{ PROVIDER }}</code
          >),识别结果边说边出。
        </p>
      </header>

      <!-- 上传区 -->
      <div
        v-if="phase === 'idle'"
        class="rounded-xl border-2 border-dashed border-auris-border p-10 text-center transition-colors hover:border-auris-accent"
        @click="fileInput?.click()"
      >
        <p class="text-auris-text-secondary">点击选择音频文件</p>
        <p class="mt-1 text-xs text-auris-text-muted">wav / mp3 / m4a / ogg,任意时长</p>
        <input
          ref="fileInput"
          type="file"
          accept="audio/*"
          class="hidden"
          @change="onFileChosen"
        />
      </div>

      <!-- 状态条 -->
      <div v-else class="mb-4 flex items-center justify-between rounded-lg bg-auris-surface px-4 py-3 text-sm">
        <div class="flex items-center gap-3">
          <span
            class="inline-block size-2 rounded-full"
            :class="{
              'bg-auris-accent animate-pulse': phase === 'decoding' || phase === 'streaming',
              'bg-auris-success': phase === 'done',
              'bg-auris-danger': phase === 'error',
            }"
          />
          <span class="font-medium">{{ phaseLabel[phase] }}</span>
          <span class="text-auris-text-muted">{{ fileName }}</span>
        </div>
        <div class="flex items-center gap-4 text-auris-text-secondary">
          <span>partial {{ stats.partials }}</span>
          <span>final {{ stats.finals }}</span>
          <span v-if="stats.firstCharMs">首字 {{ (stats.firstCharMs / 1000).toFixed(2) }}s</span>
        </div>
      </div>

      <!-- 错误 -->
      <div v-if="phase === 'error'" class="rounded-lg border border-auris-danger/40 bg-red-950/30 px-4 py-3 text-sm text-red-300">
        {{ errorMsg }}
      </div>

      <!-- 转写区 -->
      <div
        v-if="phase !== 'idle'"
        ref="transcriptEl"
        class="max-h-[50vh] overflow-y-auto rounded-xl border border-auris-border bg-auris-surface p-6 leading-loose"
      >
        <template v-if="finals.length || currentText">
          <p v-for="(f, i) in finals" :key="i" class="text-auris-text">
            {{ f.text }}
          </p>
          <p v-if="currentText" class="text-auris-text-secondary">
            {{ currentText }}<span class="ml-0.5 animate-pulse text-auris-accent">▌</span>
          </p>
        </template>
        <p v-else class="text-auris-text-muted">
          {{ phase === 'streaming' ? '等待首个识别结果…' : '' }}
        </p>
      </div>

      <!-- 重来 -->
      <button
        v-if="phase === 'done' || phase === 'error'"
        class="mt-6 rounded-lg bg-auris-accent px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-auris-accent/80"
        @click="reset"
      >
        再来一个
      </button>
    </div>
  </div>
</template>
