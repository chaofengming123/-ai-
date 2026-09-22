import { ref } from 'vue'

export function createDocumentPreview(fetchText, sessionVersion) {
  const preview = ref(null), previewingId = ref(null), previewError = ref('')
  let generation = 0, controller
  function closePreview() {
    generation++; controller?.abort(); preview.value = null; previewingId.value = null; previewError.value = ''
  }
  async function showPreview(item) {
    closePreview()
    const current = generation, version = sessionVersion()
    controller = new AbortController()
    previewingId.value = item.id
    try {
      const result = await fetchText(item.id, controller.signal)
      if (current === generation && version === sessionVersion()) preview.value = result
    } catch (error) {
      if (current === generation && version === sessionVersion() && error.code !== 'ERR_CANCELED')
        previewError.value = error.response?.data?.message || '正文预览失败，请检查后端或稍后再试。'
    } finally { if (current === generation) previewingId.value = null }
  }
  return { preview, previewingId, previewError, closePreview, showPreview }
}
