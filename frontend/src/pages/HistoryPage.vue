<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getRecords, deleteRecord, getRecord, fmtUtc, fmtDuration, type RecordItem, type RecordDetail, ApiError } from '../services/api'

const items = ref<RecordItem[]>([])
const total = ref(0)
const page = ref(1)
const size = 10
const err = ref('')
const loading = ref(false)
const detail = ref<RecordDetail | null>(null)
const detailRecordId = ref('')

onMounted(load)

async function load() {
  loading.value = true
  err.value = ''
  detail.value = null
  try {
    const resp = await getRecords(page.value, size)
    items.value = resp.records
    total.value = resp.total
  } catch (e) {
    err.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function prev() {
  if (page.value > 1) {
    page.value--
    load()
  }
}

function next() {
  if (page.value * size < total.value) {
    page.value++
    load()
  }
}

async function open(item: RecordItem) {
  // completed/failed 终态可直接拉详情(回源 DB);transcribing 详情接口也兼容(透传 engine 进度)
  detailRecordId.value = item.recordId
  try {
    detail.value = await getRecord(item.recordId)
  } catch (e) {
    err.value = e instanceof ApiError ? e.message : '详情加载失败'
  }
}

async function remove(item: RecordItem) {
  if (!confirm(`删除记录「${item.title ?? item.recordId}」?不可恢复`)) return
  try {
    await deleteRecord(item.recordId)
    load()
  } catch (e) {
    err.value = e instanceof ApiError ? e.message : '删除失败'
  }
}

function statusClass(s: string) {
  return {
    'text-blue-600': s === 'transcribing',
    'text-green-600': s === 'completed',
    'text-red-500': s === 'failed',
  }
}
</script>

<template>
  <div class="max-w-3xl mx-auto space-y-4">
    <div class="flex items-center justify-between">
      <h2 class="text-xl font-semibold">我的转写</h2>
      <button class="text-sm text-blue-600 hover:underline" @click="load">刷新</button>
    </div>

    <p v-if="err" class="text-sm text-red-500">{{ err }}</p>

    <div class="bg-white rounded-xl shadow divide-y">
      <p v-if="!loading && items.length === 0" class="p-8 text-center text-gray-400">还没有转写记录</p>

      <div v-for="item in items" :key="item.recordId" class="p-4 flex items-center gap-4 hover:bg-gray-50">
        <div class="flex-1 min-w-0 cursor-pointer" @click="open(item)">
          <p class="font-medium truncate">{{ item.title ?? '未命名' }}</p>
          <p class="text-xs text-gray-400 mt-0.5">
            <!-- createdAt 是 UTC 无后缀,fmtUtc 内补 Z 转本地 -->
            {{ fmtUtc(item.createdAt) }} · {{ fmtDuration(item.durationMs) }}
            <code class="ml-1">{{ item.recordId.slice(-6) }}</code>
          </p>
        </div>
        <span class="text-xs font-medium" :class="statusClass(item.status)">{{ item.status }}</span>
        <button class="text-xs text-red-400 hover:text-red-600" @click="remove(item)">删除</button>
      </div>
    </div>

    <div v-if="total > size" class="flex items-center justify-center gap-4 text-sm">
      <button :disabled="page <= 1" class="px-3 py-1 border rounded hover:bg-white disabled:opacity-40" @click="prev">上一页</button>
      <span class="text-gray-500">{{ page }} / {{ Math.ceil(total / size) }}(共 {{ total }} 条)</span>
      <button :disabled="page * size >= total" class="px-3 py-1 border rounded hover:bg-white disabled:opacity-40" @click="next">下一页</button>
    </div>

    <!-- 详情(点击行展开) -->
    <div v-if="detail" class="bg-white rounded-xl shadow p-6 space-y-3">
      <div class="flex items-center justify-between text-sm text-gray-500">
        <span>recordId: <code class="text-gray-700">{{ detailRecordId }}</code></span>
        <span class="font-medium" :class="statusClass(detail.status)">{{ detail.status }}</span>
      </div>
      <div v-if="detail.errorMsg" class="text-red-500 bg-red-50 rounded-lg p-3 text-sm">{{ detail.errorMsg }}</div>
      <div v-if="detail.result?.text" class="whitespace-pre-wrap leading-relaxed text-sm">{{ detail.result.text }}</div>
    </div>
  </div>
</template>
