<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import UserAvatar from '../components/UserAvatar.vue'
import { getMe, fmtUtc, type MeResp } from '../services/api'

const emit = defineEmits<{ back: []; toHistory: [] }>()

const me = ref<MeResp | null>(null)

onMounted(async () => {
  me.value = await getMe()
})

const displayName = computed(() => me.value?.nickname || me.value?.username || '…')
</script>

<template>
  <div class="max-w-3xl mx-auto space-y-6">
    <button class="text-sm text-gray-500 hover:text-gray-800" @click="emit('back')">← 返回</button>

    <div class="bg-white rounded-xl shadow p-8">
      <!-- 账号区 -->
      <div class="flex items-center gap-5">
        <UserAvatar :url="me?.avatarUrl ?? null" :name="displayName" :size="64" />
        <div class="min-w-0">
          <p class="text-lg font-semibold truncate">{{ displayName }}</p>
          <p class="text-sm text-gray-400">@{{ me?.username ?? '…' }}</p>
          <!-- 注册时间是 UTC 无后缀,fmtUtc 补 Z 转本地;/me 未就绪时为占位 -->
          <p class="text-xs text-gray-400 mt-1">注册于 {{ me?.createdAt ? fmtUtc(me.createdAt) : '-' }}</p>
        </div>
      </div>

      <!-- 我的转写入口 -->
      <button
        class="mt-8 w-full flex items-center justify-between border rounded-lg px-4 py-3 hover:bg-gray-50 transition-colors"
        @click="emit('toHistory')"
      >
        <span class="font-medium">我的转写</span>
        <span class="text-gray-400">→</span>
      </button>

      <!-- 统计卡:后端 stats 接口就绪后再上,此处预留注释位 -->
    </div>
  </div>
</template>
