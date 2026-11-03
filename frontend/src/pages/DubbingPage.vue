<script setup lang="ts">
/**
 * Dubbing task board (P12-D6/D8) — submit + task list in one page, real api (services/dubbing.ts).
 *
 * Dual form: kind=video (8 steps, <video> plays mp4 + srt download) / audio (6 steps, <audio> playback).
 * In-flight progress driven by a 3s heartbeat polling GET /v1/dubbing (Java calls engine only for non-terminal).
 */
import { ref, onMounted, onUnmounted } from 'vue'
import {
  listDubbing, getDubbing, submitDubbing, deleteDubbing, fetchAssetUrl, downloadSubtitle,
  stagesFor, type DubbingTask,
} from '../services/dubbing'
import { fmtUtc } from '../services/api'

const emit = defineEmits<{ back: []; toModels: [] }>()

const tasks = ref<DubbingTask[]>([])
const url = ref('')
const submitting = ref(false)
/** play overlay: { task, src (presigned url) } */
const playing = ref<{ task: DubbingTask; src: string } | null>(null)
/** lightweight in-page toast */
const toast = ref('')
let toastTimer: number | undefined
let tickTimer: number | undefined

async function refresh() {
  // list first, then getTask per in-flight row — only getTask makes Java call engine and
  // advance the DB (a plain list never does; tasks stuck in init forever, lesson 2026-10-25)
  tasks.value = await listDubbing()
  const inflight = tasks.value.filter(t => inFlight(t))
  if (inflight.length > 0) {
    await Promise.all(inflight.map(t => getDubbing(t.id).catch(() => null)))
    tasks.value = await listDubbing()
  }
}

onMounted(async () => {
  await refresh()
  tickTimer = window.setInterval(refresh, 3000)
})

onUnmounted(() => {
  if (tickTimer) window.clearInterval(tickTimer)
})

function showToast(text: string) {
  toast.value = text
  if (toastTimer) window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => { toast.value = '' }, 2500)
}

const inFlight = (t: DubbingTask) => t.status === 'init' || t.status === 'running'

async function submit() {
  const u = url.value.trim()
  if (!/^https?:\/\//.test(u)) {
    showToast('请粘贴视频/播客链接(B站/直链)')
    return
  }
  submitting.value = true
  try {
    await submitDubbing(u)
    url.value = ''
    await refresh()
    showToast('已发起,任务已入队')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发起失败')
  } finally {
    submitting.value = false
  }
}

async function retry(t: DubbingTask) {
  try {
    await submitDubbing(t.url)
    await refresh()
    showToast('已重新发起(同 URL 在途会幂等复用)')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发起失败')
  }
}

async function remove(t: DubbingTask) {
  if (!window.confirm(`删除任务「${t.title || t.url}」?(产物由引擎保留期清理)`)) return
  await deleteDubbing(t.id)
  await refresh()
}

async function openPlaying(t: DubbingTask) {
  try {
    const src = await fetchAssetUrl(t.id, t.kind === 'video' ? 'video' : 'audio')
    playing.value = { task: t, src }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '产物加载失败')
  }
}

async function downloadAsset(t: DubbingTask) {
  try {
    // presigned link: browsers ignore the download attribute cross-origin, new tab as fallback (non-renderable types download directly)
    const src = await fetchAssetUrl(t.id, t.kind === 'video' ? 'video' : 'audio')
    const a = document.createElement('a')
    a.href = src
    a.target = '_blank'
    a.download = `${(t.title || 'dubbing').slice(0, 60)}.${t.kind === 'video' ? 'mp4' : 'mp3'}`
    a.click()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '下载失败')
  }
}

async function subtitle(t: DubbingTask) {
  try {
    await downloadSubtitle(t.id, t.title || 'dubbing')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '字幕下载失败')
  }
}

function statusBadge(s: DubbingTask['status']) {
  switch (s) {
    case 'completed': return { text: '已完成', cls: 'bg-green-50 text-green-600' }
    case 'failed': return { text: '失败', cls: 'bg-red-50 text-red-500' }
    default: return { text: '进行中', cls: 'bg-amber-50 text-amber-600' }
  }
}

function fmtBytes(n: number | null): string {
  if (!n) return '-'
  if (n > 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)}MB`
  return `${Math.round(n / 1024)}KB`
}
</script>

<template>
  <div class="max-w-3xl mx-auto space-y-5">
    <!-- submit area -->
    <div class="bg-white rounded-xl shadow p-5 flex gap-3">
      <input
        v-model="url"
        placeholder="粘贴视频/播客链接(B站/直链 mp4/mp3),英文内容 → 中文配音"
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

    <!-- task card stream -->
    <div v-for="t in tasks" :key="t.id" class="bg-white rounded-xl shadow p-5 space-y-3">
      <div class="flex items-center justify-between gap-3">
        <div class="min-w-0">
          <p class="font-medium truncate">
            {{ t.title || t.url }}
            <span class="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 text-gray-500 ml-1">
              {{ t.kind === 'video' ? '视频' : '播客' }}
            </span>
          </p>
          <p class="text-xs text-gray-400 mt-0.5 truncate">{{ t.url }} · {{ fmtBytes(t.audioBytes) }} · {{ fmtUtc(t.createdAt) }}</p>
        </div>
        <span class="text-xs px-2 py-1 rounded font-medium shrink-0" :class="statusBadge(t.status).cls">
          {{ statusBadge(t.status).text }}{{ inFlight(t) ? ` ${t.stageIndex}/${t.stageTotal}` : '' }}
        </span>
      </div>

      <!-- in-flight: progress bar + stage lights (steps by kind, capped by stageTotal) -->
      <template v-if="inFlight(t)">
        <div class="h-1.5 bg-gray-100 rounded-full overflow-hidden">
          <div class="h-full bg-blue-500 rounded-full transition-all duration-1000"
               :style="{ width: `${Math.min(t.stageIndex, t.stageTotal) / t.stageTotal * 100}%` }" />
        </div>
        <div class="flex flex-wrap gap-1.5">
          <span
            v-for="(s, i) in stagesFor(t.kind).slice(0, t.stageTotal)"
            :key="s.key"
            class="text-[11px] px-2 py-0.5 rounded-full"
            :class="i < t.stageIndex ? 'bg-blue-50 text-blue-500' : i === t.stageIndex ? 'bg-amber-50 text-amber-600 animate-pulse' : 'bg-gray-100 text-gray-400'"
          >{{ i < t.stageIndex ? '✓ ' : i === t.stageIndex ? '● ' : '' }}{{ s.label }}</span>
        </div>
        <p class="text-xs text-gray-400">中断自动断点续跑(引擎 stage 级检查点)</p>
      </template>

      <!-- failed: error + resubmit -->
      <template v-else-if="t.status === 'failed'">
        <p class="text-xs text-red-500 bg-red-50 rounded px-3 py-2">{{ t.error }}</p>
        <div class="flex gap-3">
          <button class="px-4 py-1.5 rounded-lg text-sm bg-blue-600 text-white hover:bg-blue-700" @click="retry(t)">重新发起</button>
          <button class="px-4 py-1.5 rounded-lg text-sm text-red-500 hover:bg-red-50" @click="remove(t)">删除</button>
        </div>
      </template>

      <!-- completed: play/download/subtitle -->
      <template v-else>
        <div class="flex items-center gap-3">
          <button
            class="px-4 py-1.5 rounded-lg text-sm font-medium bg-blue-600 text-white hover:bg-blue-700"
            @click="openPlaying(t)"
          >▶ 播放</button>
          <button class="px-4 py-1.5 rounded-lg text-sm border hover:bg-gray-50" @click="downloadAsset(t)">
            {{ t.kind === 'video' ? '下载视频' : '下载音频' }}
          </button>
          <button v-if="t.kind === 'video'" class="px-4 py-1.5 rounded-lg text-sm border hover:bg-gray-50" @click="subtitle(t)">字幕 srt</button>
          <button class="text-sm text-gray-400 hover:text-red-500 ml-auto" @click="remove(t)">删除</button>
        </div>
      </template>
    </div>

    <p v-if="tasks.length === 0" class="text-center text-sm text-gray-400 py-10">
      还没有配音任务——贴一个外语视频/播客链接试试
    </p>

    <!-- play overlay: video/audio by kind -->
    <div v-if="playing" class="fixed inset-0 bg-black/60 z-20 flex items-center justify-center px-4" @click.self="playing = null">
      <div class="bg-white rounded-xl shadow-xl p-4 w-full max-w-2xl space-y-3">
        <div class="flex items-center justify-between">
          <p class="font-medium truncate pr-4">{{ playing.task.title }} · 中文配音版</p>
          <button class="text-gray-400 hover:text-gray-600" @click="playing = null">✕</button>
        </div>
        <video v-if="playing.task.kind === 'video'" :src="playing.src" controls autoplay class="w-full rounded-lg bg-black" />
        <audio v-else :src="playing.src" controls autoplay class="w-full" />
        <p class="text-[11px] text-gray-400">asset 流式代理(Range 透传,可拖进度条)</p>
      </div>
    </div>
  </div>
</template>
