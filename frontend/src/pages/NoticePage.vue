<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { listNotifications, markNoticeRead, markAllNoticesRead, fmtUtc, type NoticeItem, ApiError } from '../services/api'

/**
 * 通知列表页(P5 §1.5⑦ 用户视角)。
 * <p>
 * 点击行 = 标已读;已读数的变化通过 read 事件(delta)同步给 App 的红点。
 */
const emit = defineEmits<{ back: []; read: [delta: number] }>()

const items = ref<NoticeItem[]>([])
const err = ref('')
const loading = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  err.value = ''
  try {
    items.value = await listNotifications()
  } catch (e) {
    err.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function read(item: NoticeItem) {
  if (item.readFlag === 1) return
  try {
    await markNoticeRead(item.id)
    item.readFlag = 1
    emit('read', -1)
  } catch (e) {
    err.value = e instanceof ApiError ? e.message : '标记失败'
  }
}

async function readAll() {
  try {
    const affected = await markAllNoticesRead()
    const unread = items.value.filter(i => i.readFlag === 0).length
    items.value.forEach(i => { i.readFlag = 1 })
    emit('read', -Math.min(affected, unread))
  } catch (e) {
    err.value = e instanceof ApiError ? e.message : '操作失败'
  }
}

/** type 枚举 → 中文标签与颜色;未登记的类型原样展示 */
const typeMeta: Record<string, { text: string; cls: string }> = {
  'transcribe.completed': { text: '完成', cls: 'text-green-600' },
  'transcribe.failed': { text: '失败', cls: 'text-red-500' },
  'transcribe.timeout': { text: '超时', cls: 'text-yellow-600' },
  'alert.raised': { text: '告警', cls: 'text-orange-500' },
}
</script>

<template>
  <div class="max-w-3xl mx-auto space-y-4">
    <div class="flex items-center justify-between">
      <div class="flex items-center gap-3">
        <button class="text-sm text-gray-500 hover:text-gray-800" @click="emit('back')">← 返回</button>
        <h2 class="text-xl font-semibold">我的通知</h2>
      </div>
      <div class="flex gap-4">
        <button class="text-sm text-blue-600 hover:underline" @click="load">刷新</button>
        <button
          v-if="items.some(i => i.readFlag === 0)"
          class="text-sm text-gray-500 hover:text-gray-800"
          @click="readAll"
        >全部已读</button>
      </div>
    </div>

    <p v-if="err" class="text-sm text-red-500">{{ err }}</p>

    <div class="bg-white rounded-xl shadow divide-y">
      <p v-if="!loading && items.length === 0" class="p-8 text-center text-gray-400">还没有通知</p>

      <div
        v-for="item in items"
        :key="item.id"
        class="p-4 flex items-start gap-3 cursor-pointer hover:bg-gray-50"
        :class="item.readFlag === 0 ? '' : 'opacity-60'"
        @click="read(item)"
      >
        <!-- 未读蓝点:红点的列表侧形态 -->
        <span class="mt-1.5 w-2 h-2 rounded-full shrink-0" :class="item.readFlag === 0 ? 'bg-blue-500' : 'bg-transparent'"></span>
        <div class="flex-1 min-w-0">
          <p class="truncate" :class="item.readFlag === 0 ? 'font-medium' : ''">{{ item.title ?? item.type }}</p>
          <p class="text-xs text-gray-400 mt-0.5 truncate">{{ item.content }}</p>
          <!-- createdAt 是 UTC 无后缀,fmtUtc 内补 Z 转本地 -->
          <p class="text-xs text-gray-300 mt-0.5">{{ fmtUtc(item.createdAt) }}</p>
        </div>
        <span class="text-xs font-medium shrink-0" :class="typeMeta[item.type]?.cls ?? 'text-gray-400'">
          {{ typeMeta[item.type]?.text ?? item.type }}
        </span>
      </div>
    </div>
  </div>
</template>
