<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth.js'
import { useKnowledgeBaseStore } from '../stores/knowledgeBases.js'
import { fetchDocuments, uploadDocument, downloadDocument } from '../api/documents.js'
import { validateDocument, formatFileSize } from '../utils/documents.js'

const auth = useAuthStore()
const bases = useKnowledgeBaseStore()
const canRead = computed(() => auth.user?.permissions?.includes('document:read'))
const canUpload = computed(() => auth.user?.permissions?.includes('document:upload'))
const selectedId = ref('')
const documents = ref([])
const file = ref(null)
const fileInput = ref(null)
const loading = ref(false)
const uploading = ref(false)
const loadError = ref('')
const uploadError = ref('')
const notice = ref('')
const downloadingId = ref(null)
const downloadError = ref('')
let active = true
let requestId = 0
let controller

async function load() {
  controller?.abort()
  const currentRequest = ++requestId
  const version = auth.sessionVersion
  documents.value = []
  loadError.value = ''
  loading.value = false
  if (!selectedId.value || !canRead.value) return
  controller = new AbortController()
  loading.value = true
  try {
    const result = await fetchDocuments(selectedId.value, controller.signal)
    if (active && currentRequest === requestId && version === auth.sessionVersion) documents.value = result
  } catch (error) {
    if (active && currentRequest === requestId && version === auth.sessionVersion && error.code !== 'ERR_CANCELED') {
      loadError.value = error.response?.data?.message || '文档列表加载失败，请重试。'
    }
  } finally {
    if (active && currentRequest === requestId) loading.value = false
  }
}
async function loadBases() {
  const version = auth.sessionVersion
  await bases.loadKnowledgeBases()
  if (!active || version !== auth.sessionVersion) return
  if (!bases.knowledgeBases.some(base => String(base.id) === selectedId.value)) {
    selectedId.value = bases.knowledgeBases.length ? String(bases.knowledgeBases[0].id) : ''
  }
}
watch(selectedId, () => {
  file.value = null
  if (fileInput.value) fileInput.value.value = ''
  uploadError.value = ''
  notice.value = ''
  downloadError.value = ''
  void load()
})
onMounted(loadBases)
onBeforeUnmount(() => { active = false; requestId++; controller?.abort() })

async function download(item) {
  if (downloadingId.value !== null || !canRead.value) return
  const version = auth.sessionVersion
  const baseId = selectedId.value
  downloadingId.value = item.id
  downloadError.value = ''
  try {
    const blob = await downloadDocument(item.id)
    if (!active || version !== auth.sessionVersion || baseId !== selectedId.value) return
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = item.fileName
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) {
    if (!active || version !== auth.sessionVersion || baseId !== selectedId.value) return
    // responseType=blob 时，后端 JSON 错误同样会以 Blob 返回。
    let message
    try { message = JSON.parse(await error.response?.data?.text()).message } catch { /* 使用通用提示 */ }
    if (active && version === auth.sessionVersion && baseId === selectedId.value)
      downloadError.value = message || '下载失败，请检查后端和文件存储后重试。'
  } finally {
    if (active) downloadingId.value = null
  }
}

async function submit() {
  if (!canUpload.value || uploading.value || !selectedId.value) return
  uploadError.value = validateDocument(file.value)
  if (uploadError.value) return
  const version = auth.sessionVersion
  const baseId = selectedId.value
  uploading.value = true
  notice.value = ''
  try {
    const result = await uploadDocument(baseId, file.value)
    if (version !== auth.sessionVersion) return
    await bases.loadKnowledgeBases()
    if (!active || version !== auth.sessionVersion || baseId !== selectedId.value) return
    file.value = null
    if (fileInput.value) fileInput.value.value = ''
    notice.value = `“${result.fileName}”上传成功，当前状态为已上传。`
    await load()
  } catch (error) {
    if (!active || version !== auth.sessionVersion) return
    uploadError.value = error.response?.data?.message || '未能确认上传结果。请先刷新文档列表，确认是否已上传，再决定是否重试。'
  } finally {
    if (active) uploading.value = false
  }
}
</script>

<template>
  <section class="page" aria-labelledby="documents-title">
    <div class="page-heading">
      <div><p class="eyebrow">团队知识空间</p><h1 id="documents-title">文档管理</h1></div>
    </div>
    <p class="demo-note">支持 TXT、Markdown、PDF 和 DOCX，每个文件不超过 1 MB。文本文件需为 UTF-8；PDF 需未加密且不超过 500 页。上传后可下载原文件，尚未提取正文或用于 AI 问答。</p>
    <div v-if="bases.loadError" class="load-error" role="alert">
      <p>{{ bases.loadError }}</p><button class="secondary-button" @click="loadBases">重新加载知识库</button>
    </div>
    <p v-if="bases.isLoading" role="status">正在加载知识库……</p>
    <p v-else-if="!bases.loadError && !bases.knowledgeBases.length" class="empty-panel">暂无知识库，请先创建知识库或联系管理员。</p>
    <div v-if="bases.knowledgeBases.length" class="document-controls">
      <label for="document-base">所属知识库</label>
      <select id="document-base" v-model="selectedId" :disabled="uploading">
        <option v-for="base in bases.knowledgeBases" :key="base.id" :value="String(base.id)">{{ base.name }}</option>
      </select>
      <button class="secondary-button" :disabled="loading || uploading || !canRead" @click="load">刷新文档列表</button>
    </div>
    <form v-if="canUpload && selectedId" class="document-upload" @submit.prevent="submit">
      <label for="document-file">选择文档</label>
      <input id="document-file" ref="fileInput" type="file" accept=".txt,.md,.pdf,.docx" :disabled="uploading" @change="file = $event.target.files[0] ?? null; uploadError = ''; notice = ''">
      <button type="submit" class="primary-button" :disabled="uploading || loading">{{ uploading ? '正在上传……' : '上传文档' }}</button>
    </form>
    <p v-else-if="selectedId" class="demo-note">当前账号没有上传权限，可联系管理员分配编辑者角色。</p>
    <p v-if="uploadError" class="load-error" role="alert">{{ uploadError }}</p>
    <p v-if="notice" class="loading-notice" role="status">{{ notice }}</p>
    <p v-if="downloadError" class="load-error" role="alert">{{ downloadError }}</p>
    <p v-if="!canRead" class="load-error">当前账号没有查看文档的权限。</p>
    <p v-else-if="loading" role="status">正在加载文档……</p>
    <p v-else-if="loadError" class="load-error" role="alert">{{ loadError }}</p>
    <div v-else-if="selectedId && !documents.length" class="empty-panel">这个知识库还没有上传文档。</div>
    <ul v-else class="document-list" aria-label="已上传文档">
      <li v-for="item in documents" :key="item.id">
        <div><strong>{{ item.fileName }}</strong><small>{{ item.fileType.toUpperCase() }} · {{ formatFileSize(item.fileSize) }} · {{ item.createdAt.replace('T', ' ') }}</small></div>
        <div class="document-actions">
          <span class="demo-badge">{{ item.status === 'UPLOADED' ? '已上传' : item.status }}</span>
          <button v-if="canRead" type="button" class="secondary-button" :disabled="downloadingId !== null" :aria-label="`下载 ${item.fileName}`" @click="download(item)">{{ downloadingId === item.id ? '正在下载……' : '下载原文件' }}</button>
        </div>
      </li>
    </ul>
  </section>
</template>
