import { ref } from 'vue'
import { buildChatMessages } from './chat.js'

export function createStreamConversation(sendRequest, getSessionVersion) {
  const messages = ref([]), draft = ref(''), isBusy = ref(false), error = ref(''), notice = ref('')
  let history = [], generation = 0, controller
  function reset() {
    generation++; controller?.abort(); history = []
    messages.value = []; draft.value = ''; isBusy.value = false; error.value = ''; notice.value = ''
  }
  function stop() { controller?.abort() }
  async function send() {
    if (isBusy.value) return
    const question = draft.value.trim()
    if (!question || question.length > 2000) { error.value = '请输入 1–2000 个字符的问题。'; return }
    const request = buildChatMessages(history, question)
    const current = ++generation, version = getSessionVersion()
    const active = () => current === generation && version === getSessionVersion()
    controller = new AbortController()
    const signal = controller.signal
    messages.value.push({ role: 'user', content: question }, { role: 'assistant', content: '', incomplete: false })
    const answer = messages.value.at(-1)
    isBusy.value = true; error.value = ''; notice.value = ''
    try {
      const result = await sendRequest(request, signal, text => { if (active()) answer.content += text })
      if (!active()) return
      if (signal.aborted) throw new Error('已停止接收。')
      if (!answer.content.trim()) throw new Error('模型没有返回有效文本。')
      // 只有收到 done 后才提交上下文；被截断的回复也不进入下一轮。
      if (result.truncated) {
        answer.incomplete = true
        notice.value = '回复达到输出上限，本轮未加入后续上下文。请缩小问题范围。'
      } else {
        history = [...request, { role: 'assistant', content: answer.content }]
        draft.value = ''
      }
    } catch (failure) {
      if (!active()) return
      answer.incomplete = true
      error.value = signal.aborted ? '已停止接收，本轮未加入后续上下文。' : failure.message || '回复中断，请手动重试。'
    } finally { if (current === generation) isBusy.value = false }
  }
  return { messages, draft, isBusy, error, notice, reset, stop, send }
}
