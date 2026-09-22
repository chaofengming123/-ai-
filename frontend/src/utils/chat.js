import { ref } from 'vue'

export function buildChatMessages(history, question) {
  const selected = []
  let characters = question.length
  for (let i = history.length - 2; i >= 0 && selected.length < 10; i -= 2) {
    const pair = history.slice(i, i + 2).map(({ role, content }) => ({ role, content }))
    const size = pair.reduce((sum, item) => sum + item.content.length, 0)
    if (characters + size > 12000) break
    selected.unshift(...pair)
    characters += size
  }
  return [...selected, { role: 'user', content: question }]
}

export function createChatConversation(sendRequest, getSessionVersion) {
  const messages = ref([])
  const draft = ref('')
  const isBusy = ref(false)
  const error = ref('')
  const notice = ref('')
  let generation = 0
  let controller
  function reset() {
    generation++
    controller?.abort()
    messages.value = []
    draft.value = ''
    isBusy.value = false
    error.value = ''
    notice.value = ''
  }
  async function send() {
    if (isBusy.value) return
    const question = draft.value.trim()
    if (!question || question.length > 2000) { error.value = '请输入 1–2000 个字符的问题。'; return }
    const requestMessages = buildChatMessages(messages.value, question)
    const version = getSessionVersion()
    const requestGeneration = ++generation
    controller = new AbortController()
    isBusy.value = true
    error.value = ''
    notice.value = ''
    messages.value.push({ role: 'user', content: question })
    try {
      const result = await sendRequest(requestMessages, controller.signal)
      if (requestGeneration !== generation || version !== getSessionVersion()) return
      messages.value.push({ role: 'assistant', content: result.content })
      draft.value = ''
      if (result.truncated) notice.value = '回复达到本次输出上限，可能不完整。可以缩小问题范围后继续提问。'
    } catch (failure) {
      if (requestGeneration !== generation || version !== getSessionVersion()) return
      messages.value.pop()
      error.value = failure.response?.data?.message || '没有收到模型回复。请求可能已经产生用量，请确认后再手动重试。'
    } finally {
      if (requestGeneration === generation) isBusy.value = false
    }
  }
  return { messages, draft, isBusy, error, notice, reset, send }
}
