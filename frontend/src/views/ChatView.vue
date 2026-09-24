<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth.js'
import { fetchChatConfiguration } from '../api/chat.js'
import { sendChatStream } from '../api/chatStream.js'
import { createStreamConversation } from '../utils/streamConversation.js'
import { useKnowledgeBaseStore } from '../stores/knowledgeBases.js'

const auth = useAuthStore()
const canChat = computed(() => auth.user?.permissions?.includes('chat:send'))
const canRead = computed(() => ['knowledge-base:read', 'document:read'].every(p => auth.user?.permissions?.includes(p)))
const canManage = computed(() => auth.user?.permissions?.includes('document:index'))
const bases = useKnowledgeBaseStore()
const mode = ref('auto')
const baseId = ref('')
const { messages, draft, isBusy, error, notice, send, reset, stop } = createStreamConversation(
  (messages, signal, delta) => sendChatStream(messages, signal, delta, auth, fetch,
    { mode: canRead.value ? mode.value : 'general', knowledgeBaseId: baseId.value ? Number(baseId.value) : null }), () => auth.sessionVersion)
watch([mode, baseId], reset)
// 未收到任何文字的失败请求不留下空白 AI 气泡；错误统一在输入框上方提示。
const visibleMessages = computed(() => messages.value.filter(message => message.role !== 'assistant' || message.content))
watch(() => auth.sessionVersion, reset)
const configuration = ref(null)
const configurationError = ref('')
const checking = ref(false)
const transcript = ref(null)
watch(() => messages.value.at(-1)?.content, async () => {
  await nextTick()
  if (transcript.value) transcript.value.scrollTop = transcript.value.scrollHeight
})
let active = true
let checkController
async function checkConfiguration() {
  if (!canChat.value || checking.value) return
  const version = auth.sessionVersion
  checking.value = true
  configurationError.value = ''
  checkController = new AbortController()
  try {
    const result = await fetchChatConfiguration(checkController.signal)
    if (active && version === auth.sessionVersion) configuration.value = result
  } catch (failure) {
    if (active && version === auth.sessionVersion) configurationError.value = failure.response?.data?.message || '无法确认模型配置，请检查后端服务。'
  } finally { if (active) checking.value = false }
}
onMounted(() => { checkConfiguration(); if (canRead.value) bases.loadKnowledgeBases() })
onBeforeUnmount(() => { active = false; checkController?.abort(); reset() })
function submit() { if (canChat.value && configuration.value?.configured) void send() }
</script>

<template>
  <section class="page" aria-labelledby="chat-title">
    <div class="page-heading">
      <div><p class="eyebrow">团队知识空间</p><h1 id="chat-title">AI 问答</h1></div>
    </div>
    <p v-if="!canChat" class="load-error">当前账号没有 AI 对话权限，请核对登录状态或联系管理员。</p>
    <div v-else>
      <p v-if="canRead && bases.loadError" class="load-error" role="alert">{{ bases.loadError }} <button class="secondary-button" @click="bases.loadKnowledgeBases()">重新加载</button></p>
      <div class="load-controls">
        <span v-if="canManage && configuration?.configured">模型：{{ configuration.model }}</span>
        <button v-if="canManage || configurationError" class="secondary-button" :disabled="checking || isBusy" @click="checkConfiguration">{{ checking ? '正在检查……' : '检查配置' }}</button>
        <button class="secondary-button" :disabled="isBusy" @click="reset">新对话</button>
      </div>
      <p v-if="configurationError" class="load-error" role="alert">{{ configurationError }}</p>
      <p v-if="configuration && !configuration.configured" class="loading-notice">模型尚未配置，请联系管理员完成配置。</p>
      <div ref="transcript" class="chat-transcript" role="log" aria-label="对话记录" aria-live="polite">
        <div v-if="!messages.length" class="chat-empty"><h2>今天想了解什么？</h2><p>试试询问资料中的流程、制度，或直接提出一个日常问题。需要严格依据文档时请选择“仅知识库”。</p></div>
        <article v-for="(message, index) in visibleMessages" :key="index" class="chat-message" :class="message.role">
          <strong>{{ message.role === 'user' ? '你' : 'AI' }}</strong>
          <p>{{ message.content }}</p>
          <span v-if="message.mode" class="demo-badge">{{ message.mode === 'knowledge' ? '知识库回答' : '普通 AI 回答' }}</span>
          <small v-if="message.notice" class="answer-notice">{{ message.notice }}</small>
          <details v-for="source in message.sources" :key="source.sourceId" class="answer-source"><summary>[{{ source.sourceId }}] {{ source.knowledgeBaseName }} / {{ source.fileName }}</summary><p>{{ source.text }}</p></details>
        </article>
        <p v-if="isBusy" role="status">正在检索资料或生成回复，请稍候……</p>
      </div>
      <p v-if="error" class="load-error" role="alert">{{ error }}</p>
      <p v-if="notice" class="loading-notice" role="status">{{ notice }}</p>
      <form class="chat-form" @submit.prevent="submit">
        <label for="chat-question">你的问题</label>
        <textarea id="chat-question" v-model="draft" rows="3" :maxlength="canRead && mode !== 'general' ? 1000 : 2000" :disabled="isBusy" placeholder="输入问题；知识库提问请写明主题和完整问题。"></textarea>
        <div v-if="canRead" class="assistant-toolbar">
          <label>回答方式<select v-model="mode" :disabled="isBusy"><option value="auto">自动判断</option><option value="knowledge">仅知识库</option><option value="general">普通对话</option></select></label>
          <label v-if="mode !== 'general'">检索范围<select v-model="baseId" :disabled="isBusy || bases.isLoading"><option value="">全部知识库（最多 5 个）</option><option v-for="base in bases.knowledgeBases" :key="base.id" :value="String(base.id)">{{ base.name }}</option></select></label>
          <span class="form-hint">切换范围将开启新对话</span>
        </div>
        <div><span>{{ draft.length }} / {{ canRead && mode !== 'general' ? 1000 : 2000 }}</span><button type="submit" class="primary-button" :disabled="isBusy || !configuration?.configured || !draft.trim()">{{ isBusy ? '等待回复中' : '发送' }}</button></div>
        <button v-if="isBusy" type="button" class="secondary-button" @click="stop">停止接收</button>
      </form>
      <p class="form-hint">普通对话最多携带五轮上下文；知识库每次按完整问题重新检索，相关问题和资料片段会发送给模型服务。自动判断可能有误，请核对来源。离开页面后清空对话。</p>
    </div>
  </section>
</template>
