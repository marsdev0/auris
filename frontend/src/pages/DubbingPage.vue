<script setup lang="ts">
/**
 * 配音任务台(P12-0 D5)—— 顶层第三个 tab,发起 + 任务列表一页流。
 *
 * 当前数据源为 services/mockDubbing.ts 的 mock_ 前缀占位实现;
 * 后端四条接口就绪后换 import 来源,本页结构不动。
 * 进行中任务的"流水灯"由页面 2.2s 心跳调 mock_tickTasks 驱动(mock 演示;
 * 真实场景为轮询 GET /v1/dubbing 或完成事件后端推送)。
 */
import { ref, onMounted, onUnmounted } from 'vue'
import {
  mock_listTasks, mock_submitDubbing, mock_tickTasks, mock_retryTask, mock_deleteTask,
  mock_gateCheck, mock_stages, mock_sampleVideoUrl,
  fmtDur, currentStageLabel, statusBadge,
  type DubbingTask,
} from '../services/mockDubbing'
import type { ModelDomain } from '../services/modelConfig'

/** 去模型配置页的跳转(BYOK 闸门引导,P12-1 §6) */
const emit = defineEmits<{ back: []; toModels: [] }>()

const tasks = ref<DubbingTask[]>([])
const url = ref('')
const submitting = ref(false)
/** BYOK 闸门弹层:缺失域列表 */
const gateMissing = ref<ModelDomain[] | null>(null)
/** 播放弹层(真实场景接 asset 流式代理) */
const playing = ref<DubbingTask | null>(null)
/** 页内轻提示 */
const toast = ref('')
let toastTimer: number | undefined
let tickTimer: number | undefined

const domainNames: Record<ModelDomain, string> = { llm: '大模型 LLM', tts: '语音合成 TTS', asr: '语音识别 ASR' }

onMounted(async () => {
  tasks.value = await mock_listTasks()
  tickTimer = window.setInterval(() => { tasks.value = mock_tickTasks() }, 2200)
})

onUnmounted(() => {
  if (tickTimer) window.clearInterval(tickTimer)
})

function showToast(text: string) {
  toast.value = text
  if (toastTimer) window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => { toast.value = '' }, 2500)
}

async function submit() {
  const u = url.value.trim()
  if (!/^https?:\/\//.test(u)) {
    showToast('请粘贴视频链接(B站/YouTube/直链)')
    return
  }
  // BYOK 闸门:三域缺一或未验证 → 弹层引导(P12-1 回退策略:贵域直接拒绝)
  const gate = mock_gateCheck()
  if (!gate.ok) {
    gateMissing.value = gate.missing as ModelDomain[]
    return
  }
  submitting.value = true
  try {
    await mock_submitDubbing(u)
    url.value = ''
    tasks.value = await mock_listTasks()
    showToast('已发起,任务已入队')
  } finally {
    submitting.value = false
  }
}

async function retry(t: DubbingTask) {
  await mock_retryTask(t.id)
  tasks.value = await mock_listTasks()
  showToast(`已从断点恢复(第 ${t.stageIndex + 1} 步起)`)
}

async function remove(t: DubbingTask) {
  if (!window.confirm(`删除任务「${t.title}」?产物文件将一并清理(7 天保留期同款规则)。`)) return
  await mock_deleteTask(t.id)
  tasks.value = await mock_listTasks()
}

function downloadAsset(t: DubbingTask) {
  showToast(`mock:下载将走 asset 流式代理(${t.id}/output.mp4)`)
}

function downloadSubtitle(t: DubbingTask) {
  showToast(`mock:字幕产物 ${t.id}/dub.srt`)
}
</script>

<template>
  <div class="max-w-3xl mx-auto space-y-5">
    <!-- 发起区 -->
    <div class="bg-white rounded-xl shadow p-5 flex gap-3">
      <input
        v-model="url"
        placeholder="粘贴视频链接(B站/YouTube/直链),英文/日文等外语视频 → 中文配音"
        class="flex-1 border rounded-lg px-3 py-2.5 text-sm"
        @keyup.enter="submit"
      />
      <button
        class="px-5 py-2.5 rounded-lg text-sm font-medium bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 shrink-0"
        :disabled="submitting"
        @click="submit"
      >{{ submitting ? '发起中…' : '开始配音' }}</button>
    </div>

    <p v-if="toast" class="text-xs text-gray-500 -my-2">{{ toast }}</p>

    <!-- 任务卡片流 -->
    <div v-for="t in tasks" :key="t.id" class="bg-white rounded-xl shadow p-5 space-y-3">
      <div class="flex items-center justify-between gap-3">
        <div class="min-w-0">
          <p class="font-medium truncate">{{ t.title }}</p>
          <p class="text-xs text-gray-400 mt-0.5 truncate">{{ t.url }} · 时长 {{ fmtDur(t.durationSec) }}</p>
        </div>
        <span class="text-xs px-2 py-1 rounded font-medium shrink-0" :class="statusBadge(t.status).cls">
          {{ statusBadge(t.status).text }}{{ t.status === 'running' ? ` ${t.stageIndex}/10` : '' }}
        </span>
      </div>

      <!-- 进行中:进度条 + 10 步流水灯 -->
      <template v-if="t.status === 'running'">
        <div class="h-1.5 bg-gray-100 rounded-full overflow-hidden">
          <div class="h-full bg-blue-500 rounded-full transition-all duration-1000" :style="{ width: `${t.stageIndex * 10}%` }" />
        </div>
        <div class="flex flex-wrap gap-1.5">
          <span
            v-for="(s, i) in mock_stages"
            :key="s.key"
            class="text-[11px] px-2 py-0.5 rounded-full"
            :class="i < t.stageIndex ? 'bg-blue-50 text-blue-500' : i === t.stageIndex ? 'bg-amber-50 text-amber-600 animate-pulse' : 'bg-gray-100 text-gray-400'"
          >{{ i < t.stageIndex ? '✓ ' : i === t.stageIndex ? '● ' : '' }}{{ s.label }}</span>
        </div>
        <p class="text-xs text-gray-400">正在执行:{{ currentStageLabel(t) }}(断点续跑:中断后从此步恢复)</p>
      </template>

      <!-- 失败:错误 + 断点重试 -->
      <template v-else-if="t.status === 'failed'">
        <p class="text-xs text-red-500 bg-red-50 rounded px-3 py-2">{{ t.error }}</p>
        <div class="flex gap-3">
          <button class="px-4 py-1.5 rounded-lg text-sm bg-blue-600 text-white hover:bg-blue-700" @click="retry(t)">从断点重试</button>
          <button class="px-4 py-1.5 rounded-lg text-sm text-red-500 hover:bg-red-50" @click="remove(t)">删除</button>
        </div>
      </template>

      <!-- 已完成:播放/下载/字幕 -->
      <template v-else>
        <div class="flex items-center gap-3">
          <button
            class="px-4 py-1.5 rounded-lg text-sm font-medium bg-blue-600 text-white hover:bg-blue-700"
            @click="playing = t"
          >▶ 播放</button>
          <button class="px-4 py-1.5 rounded-lg text-sm border hover:bg-gray-50" @click="downloadAsset(t)">下载视频</button>
          <button class="px-4 py-1.5 rounded-lg text-sm border hover:bg-gray-50" @click="downloadSubtitle(t)">字幕</button>
          <span class="text-[11px] text-gray-400 ml-auto">产物保留 7 天</span>
          <button class="text-sm text-gray-400 hover:text-red-500" @click="remove(t)">删除</button>
        </div>
      </template>
    </div>

    <p v-if="tasks.length === 0" class="text-center text-sm text-gray-400 py-10">
      还没有配音任务——贴一个外语视频链接试试
    </p>

    <!-- BYOK 闸门弹层 -->
    <div v-if="gateMissing" class="fixed inset-0 bg-black/40 z-20 flex items-center justify-center px-4" @click.self="gateMissing = null">
      <div class="bg-white rounded-xl shadow-xl p-6 w-full max-w-sm space-y-4">
        <p class="font-semibold">配音需要自备模型配置</p>
        <p class="text-sm text-gray-500">配音为重消耗任务(转写+翻译+合成),走你自己的云服务 Key,平台不代付。以下配置缺失或未验证:</p>
        <div class="flex flex-wrap gap-2">
          <span v-for="d in gateMissing" :key="d" class="text-xs px-2 py-1 rounded bg-amber-50 text-amber-600">{{ domainNames[d] }}</span>
        </div>
        <div class="flex gap-3 justify-end">
          <button class="px-4 py-2 rounded-lg text-sm text-gray-500 hover:bg-gray-50" @click="gateMissing = null">取消</button>
          <button class="px-4 py-2 rounded-lg text-sm font-medium bg-blue-600 text-white hover:bg-blue-700" @click="gateMissing = null; emit('toModels')">去配置</button>
        </div>
      </div>
    </div>

    <!-- 播放弹层 -->
    <div v-if="playing" class="fixed inset-0 bg-black/60 z-20 flex items-center justify-center px-4" @click.self="playing = null">
      <div class="bg-white rounded-xl shadow-xl p-4 w-full max-w-2xl space-y-3">
        <div class="flex items-center justify-between">
          <p class="font-medium truncate pr-4">{{ playing.title }} · 中文配音版</p>
          <button class="text-gray-400 hover:text-gray-600" @click="playing = null">✕</button>
        </div>
        <!-- mock 占位视频:真实场景为 asset 流式代理(支持 Range 拖进度条) -->
        <video :src="mock_sampleVideoUrl" controls autoplay class="w-full rounded-lg bg-black" />
        <p class="text-[11px] text-gray-400">mock 占位视频 · 真实场景播放 /v1/dubbing/{id}/asset 流式代理</p>
      </div>
    </div>
  </div>
</template>
