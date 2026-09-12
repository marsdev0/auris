<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue'
import { submitTask, submitUrl, getRecord, type RecordDetail, ApiError } from '../services/api'

const emit = defineEmits<{ done: [] }>()

/** 输入模式:文件上传 / 贴 URL(P4) */
const mode = ref<'file' | 'url'>('file')
const url = ref('')
const file = ref<File | null>(null)
/** 按钮状态机:idle 可提交 → submitting 提交中 → transcribing 轮询中 → 终态回 idle */
const phase = ref<'idle' | 'submitting' | 'transcribing'>('idle')
const err = ref('')
const detail = ref<RecordDetail | null>(null)
/** engine 的 progress 是 0~100 百分数(E2E 实测 30.0/100.0),clamp 防御异常值 */
const pct = computed(() => Math.min(100, Math.max(0, Math.round(detail.value?.progress ?? 0))))
/** recordId 全程 String——19 位雪花转 Number 尾数会变 0(P3 契约) */
const recordId = ref<string>('')
let timer: number | undefined

onUnmounted(() => clearInterval(timer))

function pick(f: File | null) {
  file.value = f
  err.value = ''
}

async function start() {
  if (phase.value !== 'idle') return
  if (mode.value === 'url') {
    const u = url.value.trim()
    if (!/^https?:\/\//.test(u)) {
      err.value = '请输入 http(s) 链接'
      return
    }
    phase.value = 'submitting'
    err.value = ''
    detail.value = null
    try {
      const resp = await submitUrl(u)
      recordId.value = resp.recordId
      phase.value = 'transcribing'
      poll()
    } catch (e) {
      err.value = e instanceof ApiError ? e.message : '提交失败,请稍后重试'
      phase.value = 'idle'
    }
    return
  }
  if (!file.value) {
    err.value = '请先选择音频文件'
    return
  }
  phase.value = 'submitting'
  err.value = ''
  detail.value = null
  try {
    const resp = await submitTask(file.value)
    recordId.value = resp.recordId
    phase.value = 'transcribing'
    poll()
  } catch (e) {
    err.value = e instanceof ApiError ? e.message : '提交失败,请稍后重试'
    phase.value = 'idle'
  }
}

function poll() {
  clearInterval(timer)
  timer = window.setInterval(async () => {
    try {
      detail.value = await getRecord(recordId.value)
      if (detail.value.status === 'completed' || detail.value.status === 'failed') {
        clearInterval(timer)
        phase.value = 'idle' // 终态解锁按钮:失败可重试,完成可提交下一个
        emit('done')
      }
    } catch {
      // 单次轮询失败不终止:网络抖动/服务重启,下个周期自然恢复(P3 §2.7)
    }
  }, 3000)
}
</script>

<template>
  <div class="max-w-3xl mx-auto space-y-6">
    <h2 class="text-xl font-semibold">转写</h2>

    <div class="bg-white rounded-xl shadow p-6 space-y-4">
      <!-- 模式切换:文件 / 链接 -->
      <div class="flex gap-2">
        <button
          class="px-4 py-1.5 rounded-lg text-sm"
          :class="mode === 'file' ? 'bg-blue-50 text-blue-600 font-medium' : 'text-gray-500 hover:text-gray-800'"
          :disabled="phase !== 'idle'"
          @click="mode = 'file'"
        >文件</button>
        <button
          class="px-4 py-1.5 rounded-lg text-sm"
          :class="mode === 'url' ? 'bg-blue-50 text-blue-600 font-medium' : 'text-gray-500 hover:text-gray-800'"
          :disabled="phase !== 'idle'"
          @click="mode = 'url'"
        >链接</button>
      </div>

      <label v-if="mode === 'file'" class="block border-2 border-dashed rounded-xl p-8 text-center cursor-pointer hover:border-blue-400 transition-colors">
        <input type="file" accept="audio/*,video/*" class="hidden" @change="pick(($event.target as HTMLInputElement).files?.[0] ?? null)" />
        <div v-if="!file" class="text-gray-500">
          <p class="text-lg">点击选择音频文件</p>
          <p class="text-sm mt-1">wav / mp3 / m4a …(≤10MB)</p>
        </div>
        <div v-else class="text-blue-600 font-medium">{{ file.name }}</div>
      </label>

      <div v-else class="space-y-2">
        <input
          v-model="url"
          placeholder="贴入小宇宙单集 / B站视频 / 音频直链…"
          class="w-full border rounded-lg px-3 py-2.5 focus:outline-none focus:ring-2 focus:ring-blue-500"
          :disabled="phase !== 'idle'"
        />
        <p class="text-xs text-gray-400">engine 会抓取链接内容并转写,支持:小宇宙、B站(自动取音轨)、音频直链</p>
      </div>

      <button
        :disabled="phase !== 'idle'"
        class="bg-blue-600 text-white rounded-lg px-6 py-2.5 font-medium hover:bg-blue-700 disabled:opacity-50"
        @click="start"
      >{{ phase === 'idle' ? '开始转写' : phase === 'submitting' ? '提交中…' : '转写中…' }}</button>

      <p v-if="err" class="text-sm text-red-500">{{ err }}</p>
    </div>

    <!-- 轮询进度 / 结果 -->
    <div v-if="detail" class="bg-white rounded-xl shadow p-6 space-y-4">
      <div class="flex items-center justify-between text-sm text-gray-500">
        <span class="min-w-0 truncate">
          <span v-if="detail.title" class="text-gray-700 font-medium">{{ detail.title }}</span>
          <code v-else class="text-gray-700">{{ detail.recordId || recordId }}</code>
        </span>
        <span class="font-medium shrink-0 ml-2" :class="{
          'text-blue-600': detail.status === 'transcribing' || detail.status === 'downloading',
          'text-green-600': detail.status === 'completed',
          'text-red-500': detail.status === 'failed',
        }">{{ detail.status === 'downloading' ? '下载中' : detail.status }}</span>
      </div>

      <!-- downloading:无可靠总量进度(Content-Length 可缺席),转圈不显百分比(P4 §2.2) -->
      <div v-if="detail.status === 'downloading'" class="text-sm text-gray-500 text-center animate-pulse py-2">
        正在下载音频…
      </div>

      <div v-else-if="detail.status === 'transcribing'" class="space-y-2">
        <div class="h-2 bg-gray-100 rounded-full overflow-hidden">
          <div class="h-full bg-blue-500 rounded-full transition-all duration-500" :style="{ width: `${pct}%` }" />
        </div>
        <div class="text-sm text-gray-500 text-center">转写中 {{ pct }}%</div>
      </div>

      <div v-if="detail.status === 'failed'" class="text-red-500 bg-red-50 rounded-lg p-4 text-sm">
        {{ detail.errorMsg || '转写失败' }}
      </div>

      <div v-if="detail.result?.text" class="prose prose-sm max-w-none whitespace-pre-wrap leading-relaxed">
        {{ detail.result.text }}
      </div>
    </div>
  </div>
</template>
