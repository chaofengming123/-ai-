<script setup>
import { computed, onMounted, onBeforeUnmount } from 'vue'
import { useAuthStore } from '../stores/auth.js'
import { fetchDocumentIndex, buildDocumentIndex } from '../api/documents.js'
import { createVectorStorage } from '../utils/vectorStorage.js'
import DocumentSearchPanel from './DocumentSearchPanel.vue'
const props = defineProps({ document: { type: Object, required: true } })
defineEmits(['close'])
const auth = useAuthStore()
const canIndex = computed(() => auth.user?.permissions?.includes('document:index'))
const { status, busy, error, run, reset } = createVectorStorage({
  status: (_, signal) => fetchDocumentIndex(props.document.id, signal),
  build: (_, signal) => buildDocumentIndex(props.document.id, signal),
}, () => auth.sessionVersion)
const labels = { NOT_INDEXED: '尚未建立', PROCESSING: '正在处理', READY: '最近一次建立成功', FAILED: '最近一次处理失败' }
onMounted(() => run('status'))
onBeforeUnmount(reset)
</script>
<template>
  <section class="chunk-panel" aria-label="文档索引">
    <button class="secondary-button" @click="$emit('close')">关闭索引面板</button>
    <h2>{{ document.fileName }} · 文档索引</h2>
    <p>建立索引会将提取的正文分块发送到硅基流动。每份正文最多 4000 字符、PDF 最多 50 页；超限会拒绝，不保存截断内容。扫描图片不做 OCR。</p>
    <p v-if="status">处理状态：{{ labels[status.state] || status.state }}</p>
    <p v-if="status?.state === 'PROCESSING'">可稍后刷新状态。若后端曾意外退出，距上次开始十分钟后可重新建立。</p>
    <p v-if="status?.error" class="load-error">{{ status.error }}</p>
    <template v-if="status?.hasActiveIndex">
      <p>已保留的成功版本：{{ status.chunks }} 块 · {{ status.characters }} 字符 · {{ status.dimensions }} 维 · {{ status.model }}</p>
      <p>建立时间：{{ status.indexedAt?.replace('T', ' ') }}</p>
      <p>{{ status.note }}</p>
      <p v-if="!status.currentModel" class="load-error">当前模型配置已变化，需要重新建立索引后再用于当前模型的检索。</p>
    </template>
    <div class="load-controls">
      <button v-if="canIndex" class="primary-button" :disabled="busy" @click="run('build')">{{ busy ? '正在处理，请等待……' : (status?.hasActiveIndex ? '重新建立索引' : '建立索引') }}</button>
      <button class="secondary-button" :disabled="busy" @click="run('status')">刷新索引状态</button>
    </div>
    <p v-if="!canIndex">当前账号可查看状态，建立索引需要编辑者或管理员权限。</p>
    <p>本次可能需要数分钟。关闭面板不等于停止服务器处理；重建失败会保留上一次成功版本。下方可检索片段或基于文档提问。</p>
    <p v-if="error" class="load-error" role="alert">{{ error }}</p>
    <DocumentSearchPanel :key="`${document.id}-${status?.indexedAt ?? ''}`" :document-id="document.id" :available="!!status?.hasActiveIndex && !!status?.currentModel" />
  </section>
</template>
