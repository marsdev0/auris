<script setup lang="ts">
import { ref } from 'vue'
import { login, register, setToken, setProfile, ApiError } from '../services/api'

const emit = defineEmits<{ logged: [] }>()

const mode = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const nickname = ref('')
const err = ref('')
const busy = ref(false)

async function submit() {
  err.value = ''
  if (!username.value || !password.value) {
    err.value = '用户名和密码不能为空'
    return
  }
  busy.value = true
  try {
    if (mode.value === 'login') {
      const r = await login(username.value, password.value)
      setToken(r.accessToken)
      setProfile({
        username: r.username ?? username.value,
        nickname: r.nickname,
        avatarUrl: r.avatarUrl,
      })
      emit('logged')
    } else {
      await register(username.value, password.value, nickname.value || undefined)
      mode.value = 'login'
      err.value = '注册成功,请登录'
    }
  } catch (e) {
    err.value = e instanceof ApiError ? e.message : '网络异常,请稍后重试'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-gray-50">
    <div class="w-96 bg-white rounded-xl shadow p-8">
      <h1 class="text-2xl font-bold mb-1">auris</h1>
      <p class="text-sm text-gray-500 mb-6">音视频转写 · 历史与洞察</p>

      <div class="flex mb-6 text-sm border-b">
        <button
          class="pb-2 px-4 flex-1"
          :class="mode === 'login' ? 'border-b-2 border-blue-600 text-blue-600 font-medium' : 'text-gray-500'"
          @click="mode = 'login'"
        >登录</button>
        <button
          class="pb-2 px-4 flex-1"
          :class="mode === 'register' ? 'border-b-2 border-blue-600 text-blue-600 font-medium' : 'text-gray-500'"
          @click="mode = 'register'"
        >注册</button>
      </div>

      <form class="space-y-4" @submit.prevent="submit">
        <input v-model="username" placeholder="用户名" class="w-full border rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500" />
        <input v-model="password" type="password" placeholder="密码" class="w-full border rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500" />
        <input v-if="mode === 'register'" v-model="nickname" placeholder="昵称(可选)" class="w-full border rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500" />

        <p v-if="err" class="text-sm" :class="err.includes('成功') ? 'text-green-600' : 'text-red-500'">{{ err }}</p>

        <button
          type="submit"
          :disabled="busy"
          class="w-full bg-blue-600 text-white rounded-lg py-2.5 font-medium hover:bg-blue-700 disabled:opacity-50"
        >{{ busy ? '请稍候…' : mode === 'login' ? '登录' : '注册' }}</button>
      </form>
    </div>
  </div>
</template>
