<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import LoginPage from './pages/LoginPage.vue'
import TranscribePage from './pages/TranscribePage.vue'
import HistoryPage from './pages/HistoryPage.vue'
import ProfilePage from './pages/ProfilePage.vue'
import AsrPage from './pages/AsrPage.vue'
import NoticePage from './pages/NoticePage.vue'
import UserAvatar from './components/UserAvatar.vue'
import { getToken, clearToken, getProfile, authExpired, unreadCount } from './services/api'

const logged = ref(!!getToken())
/** 视图:tab 页(转写/实时) + 菜单进入的页面(个人/历史/通知);返回时回上一个视图 */
const view = ref<'transcribe' | 'stream' | 'profile' | 'history' | 'notice'>('transcribe')
let prevView: 'transcribe' | 'stream' = 'transcribe'
/** 转写终态后通知历史页刷新 */
const historyNonce = ref(0)
/** 头像下拉菜单开合 */
const menuOpen = ref(false)
const profile = ref(getProfile())
const menuRef = ref<HTMLElement | null>(null)

/**
 * 通知红点:登录期间 30s 轮询未读数(P5 §1.5⑦)。
 * <p>
 * 请求失败(后端未读回源未就绪等)一律静默——红点不亮,不影响主功能。
 */
const unread = ref(0)
let unreadTimer: number | undefined

function refreshUnread() {
  unreadCount().then(n => { unread.value = n }).catch(() => {})
}

function startUnreadPoll() {
  refreshUnread()
  unreadTimer = window.setInterval(refreshUnread, 30_000)
}

function stopUnreadPoll() {
  if (unreadTimer !== undefined) window.clearInterval(unreadTimer)
  unreadTimer = undefined
  unread.value = 0
}

/** 通知页里发生已读后同步红点(delta 为负增量,clamp 防漂移为负) */
function onNoticeRead(delta: number) {
  unread.value = Math.max(0, unread.value + delta)
}

onMounted(() => {
  authExpired.addEventListener('expired', () => {
    clearToken()
    logged.value = false
    stopUnreadPoll()
  })
  document.addEventListener('click', onClickOutside)
  if (logged.value) startUnreadPoll()
})

onUnmounted(() => {
  document.removeEventListener('click', onClickOutside)
  stopUnreadPoll()
})

function onClickOutside(e: MouseEvent) {
  if (menuOpen.value && menuRef.value && !menuRef.value.contains(e.target as Node)) {
    menuOpen.value = false
  }
}

function onLogged() {
  logged.value = true
  profile.value = getProfile()
  goTab('transcribe')
  startUnreadPoll()
}

function logout() {
  clearToken()
  logged.value = false
  menuOpen.value = false
  stopUnreadPoll()
}

function goTab(t: 'transcribe' | 'stream') {
  view.value = t
  menuOpen.value = false
}

function goPage(p: 'profile' | 'history' | 'notice') {
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

        <!-- 头像下拉:个人资产(个人信息/我的转写/我的通知)收在这里,与功能 tab 分层 -->
        <div ref="menuRef" class="relative">
          <button
            class="flex items-center gap-2 text-sm hover:opacity-80"
            @click="menuOpen = !menuOpen"
          >
            <span class="relative">
              <UserAvatar :url="profile?.avatarUrl" :name="profile?.nickname || profile?.username" :size="32" />
              <!-- 未读红点:99 封顶 -->
              <span
                v-if="unread > 0"
                class="absolute -top-1 -right-1 min-w-4 h-4 px-1 rounded-full bg-red-500 text-white text-[10px] leading-4 text-center"
              >{{ unread > 99 ? '99+' : unread }}</span>
            </span>
            <span class="text-gray-400 text-xs">▼</span>
          </button>

          <div
            v-if="menuOpen"
            class="absolute right-0 mt-2 w-44 bg-white border rounded-xl shadow-lg py-1 z-10"
          >
            <p class="px-4 py-2 text-sm font-medium text-gray-700 border-b truncate">{{ profile?.nickname || profile?.username || '…' }}</p>
            <button class="w-full text-left px-4 py-2 text-sm text-gray-600 hover:bg-gray-50" @click="goPage('profile')">个人信息</button>
            <button class="w-full text-left px-4 py-2 text-sm text-gray-600 hover:bg-gray-50" @click="goPage('history')">我的转写</button>
            <button class="w-full text-left px-4 py-2 text-sm text-gray-600 hover:bg-gray-50" @click="goPage('notice')">我的通知</button>
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
      <NoticePage v-if="view === 'notice'" @back="goBack" @read="onNoticeRead" />
      <AsrPage v-if="view === 'stream'" />
    </main>
  </div>
</template>
