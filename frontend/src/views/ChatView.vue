<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useAuthStore } from '../stores/auth.js'
import { fetchChatConfiguration, sendChat } from '../api/chat.js'
import { createChatConversation } from '../utils/chat.js'

const auth = useAuthStore()
const canChat = computed(() => auth.user?.permissions?.includes('chat:send'))
const { messages, draft, isBusy, error, notice, send, reset } = createChatConversation(sendChat, () => auth.sessionVersion)
const configuration = ref(null)
const configurationError = ref('')
const checking = ref(false)
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
onMounted(checkConfiguration)
onBeforeUnmount(() => { active = false; checkController?.abort(); reset() })
function submit() { if (canChat.value && configuration.value?.configured) void send() }
</script>

<template>
  <section class="page" aria-labelledby="chat-title">
    <div class="page-heading">
      <div><p class="eyebrow">团队知识空间</p><h1 id="chat-title">AI 问答</h1></div>
    </div>
    <p class="demo-note">基础模型对话：发送的问题和最近几轮消息会交给已配置的模型服务。当前不读取知识库文档。离开页面或刷新后清空对话。</p>
    <p v-if="!canChat" class="load-error">当前账号没有 AI 对话权限，请核对登录状态或联系管理员。</p>
    <div v-else>
      <div class="load-controls">
        <span v-if="configuration?.configured">模型：{{ configuration.model }}</span>
        <button class="secondary-button" :disabled="checking || isBusy" @click="checkConfiguration">{{ checking ? '正在检查……' : '检查配置' }}</button>
        <button class="secondary-button" :disabled="isBusy" @click="reset">新对话</button>
      </div>
      <p v-if="configurationError" class="load-error" role="alert">{{ configurationError }}</p>
      <p v-if="configuration && !configuration.configured" class="loading-notice">模型尚未配置，请按第二十一课讲义完成后端配置并重启，然后点击“检查配置”。</p>
      <div class="chat-transcript" role="log" aria-label="对话记录" aria-live="polite">
        <p v-if="!messages.length" class="chat-empty">可以先问：“请用一个例子解释 Controller 和 Service 的区别。”</p>
        <article v-for="(message, index) in messages" :key="index" class="chat-message" :class="message.role">
          <strong>{{ message.role === 'user' ? '你' : 'AI' }}</strong>
          <p>{{ message.content }}</p>
        </article>
        <p v-if="isBusy" role="status">正在等待模型回复……</p>
      </div>
      <p v-if="error" class="load-error" role="alert">{{ error }}</p>
      <p v-if="notice" class="loading-notice" role="status">{{ notice }}</p>
      <form class="chat-form" @submit.prevent="submit">
        <label for="chat-question">你的问题</label>
        <textarea id="chat-question" v-model="draft" rows="4" maxlength="2000" :disabled="isBusy" placeholder="输入问题；可结合上一轮继续追问。"></textarea>
        <div><span>{{ draft.length }} / 2000</span><button type="submit" class="primary-button" :disabled="isBusy || !configuration?.configured || !draft.trim()">{{ isBusy ? '等待回复中' : '发送' }}</button></div>
      </form>
      <p class="form-hint">每次最多携带最近五轮完整对话；内容过长时减少轮数。模型回复可能有误，请核对重要信息。</p>
    </div>
  </section>
</template>
