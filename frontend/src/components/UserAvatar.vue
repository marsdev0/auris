<script setup lang="ts">
import { ref } from 'vue'

/**
 * 用户头像:有 avatarUrl 用图片,否则昵称/用户名首字母圆底。
 * 图片加载失败(onerror)自动回落到字母模式。
 */
const props = withDefaults(defineProps<{
  url?: string | null
  name?: string | null
  size?: number
}>(), { url: null, name: null, size: 32 })

const failed = ref(false)

function initial(): string {
  return (props.name ?? '?').charAt(0).toUpperCase()
}
</script>

<template>
  <img
    v-if="url && !failed"
    :src="url"
    :alt="name ?? 'avatar'"
    :style="{ width: `${size}px`, height: `${size}px` }"
    class="rounded-full object-cover shrink-0"
    @error="failed = true"
  />
  <div
    v-if="!url || failed"
    :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.42)}px` }"
    class="rounded-full bg-blue-600 text-white flex items-center justify-center font-medium shrink-0 select-none"
  >{{ initial() }}</div>
</template>
