<script setup lang="ts">
import { ref, onMounted } from 'vue'
import LoginPage from './pages/LoginPage.vue'
import TranscribePage from './pages/TranscribePage.vue'
import HistoryPage from './pages/HistoryPage.vue'
import AsrPage from './pages/AsrPage.vue'
import { getToken, clearToken, authExpired } from './services/api'

const logged = ref(!!getToken())
const tab = ref<'transcribe' | 'history' | 'stream'>('transcribe')
// 转写终态后通知历史页刷新
const historyNonce = ref(0)

onMounted(() => {
  authExpired.addEventListener('expired', () => {
    clearToken()
    logged.value = false
  })
})

function onLogged() {
  logged.value = true
  tab.value = 'transcribe'
}

function logout() {
  clearToken()
  logged.value = false
}

const tabs = [
  { key: 'transcribe', label: '转写' },
  { key: 'history', label: '历史' },
  { key: 'stream', label: '实时' },
] as const
</script>

<template>
  <LoginPage v-if="!logged" @logged="onLogged" />

  <div v-else class="min-h-screen bg-gray-50">
    <header class="bg-white border-b">
      <div class="max-w-3xl mx-auto flex items-center justify-between h-14 px-4">
        <div class="flex items-center gap-6">
          <span class="font-bold">auris</span>
          <nav class="flex gap-1 text-sm">
            <button
              v-for="t in tabs"
              :key="t.key"
              class="px-3 py-1.5 rounded-lg"
              :class="tab === t.key ? 'bg-blue-50 text-blue-600 font-medium' : 'text-gray-500 hover:text-gray-800'"
              @click="tab = t.key"
            >{{ t.label }}</button>
          </nav>
        </div>
        <button class="text-sm text-gray-400 hover:text-gray-600" @click="logout">退出</button>
      </div>
    </header>

    <main class="py-8 px-4">
      <TranscribePage v-show="tab === 'transcribe'" @done="historyNonce++" />
      <HistoryPage v-if="tab === 'history'" :key="historyNonce" />
      <AsrPage v-if="tab === 'stream'" />
    </main>
  </div>
</template>
