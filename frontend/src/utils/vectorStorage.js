import { ref } from 'vue'

export function createVectorStorage(api, sessionVersion) {
  const status = ref(null), result = ref(null), busy = ref(false), error = ref(''), message = ref('')
  let generation = 0, controller
  function reset() {
    generation++; controller?.abort(); status.value = null; result.value = null
    busy.value = false; error.value = ''; message.value = ''
  }
  async function run(action, input) {
    if (busy.value) return
    const current = ++generation, version = sessionVersion()
    controller = new AbortController(); busy.value = true; error.value = ''; message.value = ''; result.value = null
    try {
      const data = await api[action](input, controller.signal)
      if (current !== generation || version !== sessionVersion()) return
      if (action === 'search') result.value = data
      else status.value = data
      if (action === 'save') message.value = '文字已保存。现在可以刷新页面，再输入问题检索。'
    } catch (failure) {
      if (current === generation && version === sessionVersion()) {
        error.value = failure.response?.data?.message || '操作未确认成功，请检查服务后刷新状态。系统没有自动重试。'
        if (action !== 'search') status.value = null
      }
    } finally { if (current === generation) busy.value = false }
  }
  return { status, result, busy, error, message, run, reset }
}
