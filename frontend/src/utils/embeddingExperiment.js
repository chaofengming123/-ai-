import { ref } from 'vue'
export function createEmbeddingExperiment(compare, sessionVersion) {
  const result = ref(null), error = ref(''), busy = ref(false)
  let generation = 0, controller
  function reset() { generation++; controller?.abort(); result.value = null; error.value = ''; busy.value = false }
  async function run(query, candidates) {
    if (busy.value) return
    result.value = null; error.value = ''
    if (!candidates.length || candidates.length > 5 || [query, ...candidates].some(text => typeof text !== 'string' || !text.trim() || text.length > 1000)) {
      error.value = '问题和每段候选文字需为 1–1000 个字符，候选文字需为 1–5 段。'; return
    }
    const current = ++generation, version = sessionVersion()
    controller = new AbortController(); busy.value = true
    try {
      const data = await compare(query, candidates, controller.signal)
      if (current === generation && version === sessionVersion()) result.value = { ...data, query }
    } catch (failure) {
      if (current === generation && version === sessionVersion()) error.value = failure.response?.data?.message || '向量实验失败，请检查当前模型服务；系统没有自动重试。'
    } finally { if (current === generation) busy.value = false }
  }
  return { result, error, busy, reset, run }
}
