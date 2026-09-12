<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import LoginPage from './pages/LoginPage.vue'
import TranscribePage from './pages/TranscribePage.vue'
import HistoryPage from './pages/HistoryPage.vue'
import ProfilePage from './pages/ProfilePage.vue'
import AsrPage from './pages/AsrPage.vue'
import UserAvatar from './components/UserAvatar.vue'
import { getToken, clearToken, getProfile, authExpired } from './services/api'

const logged = ref(!!getToken())
/** 视图:tab 页(转写/实时) + 菜单进入的页面(个人/历史);返回时回上一个视图 */
const view = ref<'transcribe' | 'stream' | 'profile' | 'history'>('transcribe')
let prevView: 'transcribe' | 'stream' = 'transcribe'
/** 转写终态后通知历史页刷新 */
const historyNonce = ref(0)
/** 头像下拉菜单开合 */
const menuOpen = ref(false)
const profile = ref(getProfile())
const menuRef = ref<HTMLElement | null>(null)

onMounted(() => {
  authExpired.addEventListener('expired', () => {
    clearToken()
    logged.value = false
  })
  document.addEventListener('click', onClickOutside)
})

onUnmounted(() => document.removeEventListener('click', onClickOutside))

function onClickOutside(e: MouseEvent) {
  if (menuOpen.value && menuRef.value && !menuRef.value.contains(e.target as Node)) {
    menuOpen.value = false
  }
}

function onLogged() {
  logged.value = true
  profile.value = getProfile()
  goTab('transcribe')
}

function logout() {
  clearToken()
  logged.value = false
  menuOpen.value = false
}

function goTab(t: 'transcribe' | 'stream') {
  view.value = t
  menuOpen.value = false
}

function goPage(p: 'profile' | 'history') {
  if (view.value === 'transcribe' || view.value === 'stream') prevView = view.value
  view.value = p
  menuOpen.value = false
}

function goBack() {
  view.value = prevView
}

const tabs = [
  { key: 'transcribe', label: '转写' },
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
              :class="view === t.key ? 'bg-blue-50 text-blue-600 font-medium' : 'text-gray-500 hover:text-gray-800'"
              @click="goTab(t.key)"
            >{{ t.label }}</button>
          </nav>
        </div>

        <!-- 头像下拉:个人资产(个人信息/我的转写)收在这里,与功能 tab 分层 -->
        <div ref="menuRef" class="relative">
          <button
            class="flex items-center gap-2 text-sm hover:opacity-80"
            @click="menuOpen = !menuOpen"
          >
            <UserAvatar :url="profile?.avatarUrl" :name="profile?.nickname || profile?.username" :size="32" />
            <span class="text-gray-400 text-xs">▼</span>
          </button>

          <div
            v-if="menuOpen"
            class="absolute right-0 mt-2 w-44 bg-white border rounded-xl shadow-lg py-1 z-10"
          >
            <p class="px-4 py-2 text-sm font-medium text-gray-700 border-b truncate">{{ profile?.nickname || profile?.username || '…' }}</p>
            <button class="w-full text-left px-4 py-2 text-sm text-gray-600 hover:bg-gray-50" @click="goPage('profile')">个人信息</button>
            <button class="w-full text-left px-4 py-2 text-sm text-gray-600 hover:bg-gray-50" @click="goPage('history')">我的转写</button>
            <div class="border-t my-1"></div>
            <button class="w-full text-left px-4 py-2 text-sm text-gray-600 hover:bg-gray-50" @click="logout">退出登录</button>
          </div>
        </div>
      </div>
    </header>

    <main class="py-8 px-4">
      <TranscribePage v-show="view === 'transcribe'" @done="historyNonce++" />
      <HistoryPage v-if="view === 'history'" :key="historyNonce" @back="goBack" />
      <ProfilePage v-if="view === 'profile'" @back="goBack" @to-history="view = 'history'" />
      <AsrPage v-if="view === 'stream'" />
    </main>
  </div>
</template>
