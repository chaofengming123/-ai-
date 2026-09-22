<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useAuthStore } from '../stores/auth.js'
import { fetchDocumentChunks } from '../api/documents.js'
import { createDocumentPreview } from '../utils/documentPreview.js'
import { validateChunkOptions } from '../utils/chunking.js'

const props = defineProps({ document: { type: Object, required: true } })
defineEmits(['close'])
const auth = useAuthStore()
const size = ref(500), overlap = ref(50)
const { preview, previewingId, previewError, closePreview, showPreview } = createDocumentPreview(
  (id, signal) => fetchDocumentChunks(id, size.value, overlap.value, signal), () => auth.sessionVersion)
function calculate() {
  const error = validateChunkOptions(size.value, overlap.value)
  if (error) { closePreview(); previewError.value = error; return }
  void showPreview(props.document)
}
onMounted(calculate)
onBeforeUnmount(closePreview)
</script>

<template>
  <section class="chunk-panel" aria-label="文档分块预览">
    <div class="load-controls"><h2>{{ document.fileName }} · 分块预览</h2><button type="button" class="secondary-button" @click="$emit('close')">关闭预览</button></div>
    <p>按字符切分，优先保留段落和句子边界。高亮部分与上一块重叠；本次结果仅用于预览。</p>
    <form class="document-controls" @submit.prevent="calculate">
      <label for="chunk-size">块大小上限</label><input id="chunk-size" v-model.number="size" type="number" min="200" max="2000" step="1" required :disabled="previewingId !== null">
      <label for="chunk-overlap">目标重叠长度</label><input id="chunk-overlap" v-model.number="overlap" type="number" min="0" max="200" step="1" required :disabled="previewingId !== null">
      <button class="primary-button" :disabled="previewingId !== null">{{ previewingId !== null ? '正在分块……' : '重新分块' }}</button>
    </form>
    <p v-if="previewError" class="load-error" role="alert">{{ previewError }}</p>
    <p v-if="previewingId !== null" role="status">正在提取正文并生成分块……</p>
    <template v-if="preview">
      <p>本次结果：块上限 {{ preview.chunkSize }} 字符，目标重叠 {{ preview.overlap }} 字符；原文 {{ preview.sourceCharacters }} 字符，共 {{ preview.chunks.length }} 块。</p>
      <p>{{ preview.note }}</p>
      <p v-if="preview.sourceTruncated" class="loading-notice">原文预览已截断，这些分块只覆盖已提取部分，不代表整份文档。</p>
      <p v-if="!preview.chunks.length">未提取到可分块的文字。</p>
      <details v-for="chunk in preview.chunks" :key="chunk.index" :open="chunk.index < 3" class="chunk-item">
        <summary>第 {{ chunk.index + 1 }} 块 · {{ chunk.text.length }} 字符 · 与上一块重叠 {{ chunk.overlap }} 字符</summary>
        <p class="form-hint">正文偏移 [{{ chunk.startOffset }}, {{ chunk.endOffset }})，从 0 开始，包含起点、不包含终点。</p>
        <pre class="document-text-preview"><mark v-if="chunk.overlap">{{ chunk.text.slice(0, chunk.overlap) }}</mark>{{ chunk.text.slice(chunk.overlap) }}</pre>
      </details>
    </template>
  </section>
</template>
