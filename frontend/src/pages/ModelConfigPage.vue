<script setup lang="ts">
/**
 * 模型配置页(BYOK,P12-1 §6)。
 *
 * 数据源:services/modelConfig.ts 真实接口(经 gateway 归 ai-service);
 * validate 待后端 D2 落地,当前点击提示未上线。
 */
import { ref, reactive, onMounted } from 'vue'
import {
  listConfigs, saveConfig, validateConfig, deleteConfig,
  llmProviders, ttsProviders, asrProviders, edgeVoices,
  statusBadge, fmtChecked,
  type ModelDomain, type ModelConfig, type ConfigDraft, type ProviderMeta,
} from '../services/modelConfig'

const emit = defineEmits<{ back: [] }>()

/** 三域的展示元数据(provider 选项用 mock 数据) */
const domainMeta: Record<ModelDomain, { title: string; desc: string; providers: ProviderMeta[] }> = {
  llm: { title: '大模型 LLM', desc: '翻译 / 润色 / 摘要', providers: llmProviders },
  tts: { title: '语音合成 TTS', desc: '中文配音', providers: ttsProviders },
  asr: { title: '语音识别 ASR', desc: '英文视频转写 · 需词级时间戳', providers: asrProviders },
}
const domains = Object.keys(domainMeta) as ModelDomain[]

const configs = ref<Record<ModelDomain, ModelConfig | null>>({ llm: null, tts: null, asr: null })
/** 表单草稿:apiKey 留空 = 不修改(明文只在保存/校验上行出现一次) */
const drafts = reactive<Record<ModelDomain, ConfigDraft>>(emptyDrafts())
/** 按钮态:saving[domain] / validating[domain] */
const saving = reactive<Record<ModelDomain, boolean>>({ llm: false, tts: false, asr: false })
const validating = reactive<Record<ModelDomain, boolean>>({ llm: false, tts: false, asr: false })
/** 页内轻提示(成功绿/失败红),3s 自动消 */
const toast = ref<{ domain: ModelDomain; ok: boolean; text: string } | null>(null)
let toastTimer: number | undefined

function emptyDrafts(): Record<ModelDomain, ConfigDraft> {
  return {
    llm: { provider: 'openai_compat', baseUrl: '', apiKey: '', model: '', voice: '' },
    tts: { provider: 'edge_tts', baseUrl: '', apiKey: '', model: '', voice: '' },
    asr: { provider: 'dashscope', baseUrl: '', apiKey: '', model: '', voice: '' },
  }
}

onMounted(async () => {
  configs.value = await listConfigs()
  hydrateDrafts()
})

/** 已有配置回填表单(apiKey 永远留空,占位符显示掩码) */
function hydrateDrafts() {
  for (const d of domains) {
    const c = configs.value[d]
    if (!c) continue
    drafts[d] = { provider: c.provider, baseUrl: c.baseUrl ?? '', apiKey: '', model: c.model ?? '', voice: c.voice ?? '' }
  }
}

function providerMeta(d: ModelDomain): ProviderMeta {
  return domainMeta[d].providers.find(p => p.value === drafts[d].provider) ?? domainMeta[d].providers[0]
}

function keyPlaceholder(d: ModelDomain): string {
  const masked = configs.value[d]?.apiKeyMasked
  return masked ? `已保存 ${masked} · 留空则不修改` : 'sk-...'
}

/** 模型名占位符:按域/供应商给典型值,edge_tts 明示无需填写 */
function modelPlaceholder(d: ModelDomain): string {
  if (d === 'llm') return 'deepseek-v3'
  if (d === 'asr') return 'paraformer-v2'
  if (drafts[d].provider === 'edge_tts') return '无需填写'
  if (drafts[d].provider === 'fish_audio') return 'speech-1.5'
  return 'tts-1'
}

function showToast(domain: ModelDomain, ok: boolean, text: string) {
  toast.value = { domain, ok, text }
  if (toastTimer) window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => { toast.value = null }, 3000)
}

/** 保存前的轻拦截:按 provider 元数据拦住明显缺失的字段,后端已放宽 model 校验 */
async function save(d: ModelDomain) {
  const meta = providerMeta(d)
  if (meta.needsModel && !drafts[d].model) {
    showToast(d, false, `${meta.label} 需填写模型名`)
    return
  }
  if (d === 'tts' && drafts[d].provider === 'edge_tts' && !drafts[d].voice) {
    showToast(d, false, 'Edge TTS 需选择音色')
    return
  }
  saving[d] = true
  try {
    await saveConfig(d, drafts[d])
    configs.value = await listConfigs()
    hydrateDrafts()
    showToast(d, true, '已保存,等待校验')
  } catch (e) {
    showToast(d, false, e instanceof Error ? e.message : '保存失败')
  } finally {
    saving[d] = false
  }
}

/** 测试连接:engine 实测回写 verified/failed(后端 D2 未上线,当前提示) */
async function validate(d: ModelDomain) {
  validating[d] = true
  try {
    configs.value[d] = await validateConfig(d)
    showToast(d, true, '连通正常')
  } catch (e) {
    showToast(d, false, e instanceof Error ? e.message : '校验失败')
  } finally {
    validating[d] = false
  }
}

async function remove(d: ModelDomain) {
  if (!window.confirm('删除该域配置?配音任务将因缺少配置被拒绝。')) return
  await deleteConfig(d)
  configs.value[d] = null
  drafts[d] = emptyDrafts()[d]
  showToast(d, true, '已删除')
}
</script>

<template>
  <div class="max-w-3xl mx-auto space-y-5">
    <button class="text-sm text-gray-500 hover:text-gray-800" @click="emit('back')">← 返回</button>

    <div class="flex items-baseline justify-between">
      <h1 class="text-xl font-bold">模型配置</h1>
    </div>
    <p class="text-sm text-gray-500 -mt-3">
      自带模型 Key(BYOK):配音等重任务走你自己的云服务,平台不代付 token 费用。
      密钥加密存储,仅回显掩码。
    </p>

    <!-- 三域卡片 -->
    <div v-for="d in domains" :key="d" class="bg-white rounded-xl shadow p-6 space-y-4">
      <div class="flex items-center justify-between">
        <div>
          <p class="font-semibold">{{ domainMeta[d].title }}</p>
          <p class="text-xs text-gray-400 mt-0.5">{{ domainMeta[d].desc }}</p>
        </div>
        <span
          v-if="configs[d]"
          class="text-xs px-2 py-1 rounded font-medium"
          :class="statusBadge(configs[d]!.status).cls"
        >{{ statusBadge(configs[d]!.status).text }}</span>
        <span v-else class="text-xs px-2 py-1 rounded bg-gray-100 text-gray-400">未配置</span>
      </div>

      <!-- 校验结果详情 -->
      <p v-if="configs[d]?.status === 'failed' && configs[d]?.validateDetail" class="text-xs text-red-500 bg-red-50 rounded px-3 py-2">
        {{ configs[d]!.validateDetail }}
      </p>
      <p v-if="configs[d]?.status === 'verified'" class="text-xs text-gray-400">
        上次校验 {{ fmtChecked(configs[d]) }}<span v-if="configs[d]!.lastLatencyMs != null"> · {{ configs[d]!.lastLatencyMs }}ms</span>
      </p>

      <div class="grid grid-cols-2 gap-3">
        <label class="block">
          <span class="text-xs text-gray-500">服务商</span>
          <select v-model="drafts[d].provider" class="mt-1 w-full border rounded-lg px-3 py-2 text-sm bg-white">
            <option v-for="p in domainMeta[d].providers" :key="p.value" :value="p.value">{{ p.label }}</option>
          </select>
          <span v-if="providerMeta(d).note" class="text-[11px] text-gray-400">{{ providerMeta(d).note }}</span>
        </label>

        <!-- 模型名:三域统一渲染,必填性由 provider 元数据控制(tts 可选) -->
        <label class="block">
          <span class="text-xs text-gray-500">模型名{{ d === 'tts' ? '(可选)' : '' }}</span>
          <input v-model.trim="drafts[d].model" :placeholder="modelPlaceholder(d)" class="mt-1 w-full border rounded-lg px-3 py-2 text-sm" />
        </label>

        <!-- tts 音色:edge_tts 用预置下拉,其他 provider 填音色 ID/名称(可选) -->
        <label v-if="d === 'tts' && drafts[d].provider === 'edge_tts'" class="block">
          <span class="text-xs text-gray-500">音色</span>
          <select v-model="drafts[d].voice" class="mt-1 w-full border rounded-lg px-3 py-2 text-sm bg-white">
            <option v-for="v in edgeVoices" :key="v.value" :value="v.value">{{ v.label }}</option>
          </select>
        </label>
        <label v-else-if="d === 'tts'" class="block">
          <span class="text-xs text-gray-500">音色(可选)</span>
          <input v-model.trim="drafts[d].voice" :placeholder="drafts[d].provider === 'fish_audio' ? '音色 ID' : 'alloy'" class="mt-1 w-full border rounded-lg px-3 py-2 text-sm" />
        </label>

        <label v-if="providerMeta(d).needsKey && d !== 'tts'" class="block col-span-2">
          <span class="text-xs text-gray-500">Base URL</span>
          <input v-model.trim="drafts[d].baseUrl" placeholder="https://api.example.com/v1" class="mt-1 w-full border rounded-lg px-3 py-2 text-sm" />
        </label>
      </div>

      <label v-if="providerMeta(d).needsKey" class="block">
        <span class="text-xs text-gray-500">API Key</span>
        <input
          v-model.trim="drafts[d].apiKey"
          type="password" autocomplete="off"
          :placeholder="keyPlaceholder(d)"
          class="mt-1 w-full border rounded-lg px-3 py-2 text-sm font-mono"
        />
        <span class="text-[11px] text-gray-400">仅保存时上行一次,落库即密文,保存后不可查看原文</span>
      </label>

      <div class="flex items-center gap-3 pt-1">
        <button
          class="px-4 py-2 rounded-lg text-sm font-medium bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50"
          :disabled="saving[d]"
          @click="save(d)"
        >{{ saving[d] ? '保存中…' : '保存' }}</button>
        <button
          class="px-4 py-2 rounded-lg text-sm border hover:bg-gray-50 disabled:opacity-50"
          :disabled="!configs[d] || validating[d]"
          @click="validate(d)"
        >{{ validating[d] ? '测试中…' : '测试连接' }}</button>
        <button
          v-if="configs[d]"
          class="ml-auto text-sm text-red-500 hover:text-red-600"
          @click="remove(d)"
        >删除</button>
      </div>

      <!-- 页内提示 -->
      <p v-if="toast && toast.domain === d" class="text-xs" :class="toast.ok ? 'text-green-600' : 'text-red-500'">
        {{ toast.text }}
      </p>
    </div>
  </div>
</template>
