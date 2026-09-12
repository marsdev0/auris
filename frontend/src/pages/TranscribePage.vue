<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue'
import { submitTask, getRecord, type RecordDetail, ApiError } from '../services/api'

const emit = defineEmits<{ done: [] }>()

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
  if (phase.value !== 'idle' || !file.value) {
    if (!file.value) err.value = '请先选择音频文件'
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
    <h2 class="text-xl font-semibold">文件转写</h2>

    <div class="bg-white rounded-xl shadow p-6 space-y-4">
      <label class="block border-2 border-dashed rounded-xl p-8 text-center cursor-pointer hover:border-blue-400 transition-colors">
        <input type="file" accept="audio/*,video/*" class="hidden" @change="pick(($event.target as HTMLInputElement).files?.[0] ?? null)" />
        <div v-if="!file" class="text-gray-500">
          <p class="text-lg">点击选择音频文件</p>
          <p class="text-sm mt-1">wav / mp3 / m4a …(≤10MB)</p>
        </div>
        <div v-else class="text-blue-600 font-medium">{{ file.name }}</div>
      </label>

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
        <span>recordId: <code class="text-gray-700">{{ detail.recordId || recordId }}</code></span>
        <span class="font-medium" :class="{
          'text-blue-600': detail.status === 'transcribing',
          'text-green-600': detail.status === 'completed',
          'text-red-500': detail.status === 'failed',
        }">{{ detail.status }}</span>
      </div>

      <div v-if="detail.status === 'transcribing'" class="h-2 bg-gray-100 rounded-full overflow-hidden">
        <div class="h-full bg-blue-500 rounded-full transition-all duration-500" :style="{ width: `${pct}%` }" />
      </div>

      <div v-if="detail.status === 'transcribing'" class="text-sm text-gray-500 text-center">
        转写中 {{ pct }}%
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
