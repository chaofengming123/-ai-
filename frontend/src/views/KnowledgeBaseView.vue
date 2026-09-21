<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import KnowledgeBaseCard from '../components/KnowledgeBaseCard.vue'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '../stores/auth.js'
import { useKnowledgeBaseStore } from '../stores/knowledgeBases.js'

// 两个页面使用同一份 store，搜索词与弹窗仍由当前页面管理。
const auth = useAuthStore()
const knowledgeBaseStore = useKnowledgeBaseStore()
const { knowledgeBases, isLoading, isSaving, loadError, hasLoaded } = storeToRefs(knowledgeBaseStore)
const searchQuery = ref('')
onMounted(() => {
  if (!hasLoaded.value) knowledgeBaseStore.loadKnowledgeBases()
})

// 计算结果用于展示，完整列表仍保存在 knowledgeBases 中。
const filteredKnowledgeBases = computed(() => {
  const keyword = searchQuery.value.trim().toLowerCase()
  if (!keyword) return knowledgeBases.value

  return knowledgeBases.value.filter(item =>
    [item.name, item.description, item.category].some(text =>
      text.toLowerCase().includes(keyword),
    ),
  )
})

const createDialog = ref(null)
const editingId = ref(null)
const deleteDialog = ref(null)
const deleteTarget = ref(null)
const deleteError = ref('')
const name = ref('')
const description = ref('')
const error = ref('')
const notice = ref('')
const detailDialog = ref(null)
const selectedKnowledgeBase = ref(null)

function openKnowledgeBaseDetail(id) {
  const knowledgeBase = knowledgeBases.value.find(item => item.id === id)
  if (!knowledgeBase) return

  selectedKnowledgeBase.value = knowledgeBase
  detailDialog.value.showModal()
}

function openCreateDialog() {
  if (!auth.canManageKnowledgeBases) return
  editingId.value = null
  name.value = ''
  description.value = ''
  error.value = ''
  notice.value = ''
  createDialog.value.showModal()
}

function openEditDialog(id) {
  if (!auth.canManageKnowledgeBases) return
  const record = knowledgeBases.value.find(item => item.id === id)
  if (!record) return
  editingId.value = id
  // 复制字段到表单，取消时不会修改列表中的原始记录。
  name.value = record.name
  description.value = record.description
  error.value = ''
  notice.value = ''
  createDialog.value.showModal()
}

async function saveKnowledgeBase() {
  if (!auth.canManageKnowledgeBases) { error.value = '当前账号没有管理权限。'; return }
  error.value = ''
  const data = { name: name.value, description: description.value }
  const result = editingId.value === null
    ? await knowledgeBaseStore.addKnowledgeBase(data)
    : await knowledgeBaseStore.editKnowledgeBase(editingId.value, data)
  if (result.error) {
    error.value = result.error
    return
  }
  createDialog.value.close()
  notice.value = `已${editingId.value === null ? '创建' : '修改'}“${result.knowledgeBase.name}”。已保存到数据库。${searchQuery.value.trim() ? '当前列表仍按搜索词筛选。' : ''}`
}

function openDeleteDialog(id) {
  if (!auth.canManageKnowledgeBases) return
  const record = knowledgeBases.value.find(item => item.id === id)
  if (!record) return
  deleteTarget.value = { id: record.id, name: record.name }
  deleteError.value = ''
  notice.value = ''
  deleteDialog.value.showModal()
}

async function confirmDelete() {
  if (!auth.canManageKnowledgeBases || !deleteTarget.value) return
  deleteError.value = ''
  const result = await knowledgeBaseStore.removeKnowledgeBase(deleteTarget.value.id)
  if (result.error) {
    deleteError.value = result.error
    return
  }
  deleteDialog.value.close()
  notice.value = `已删除“${deleteTarget.value.name}”。`
}
watch(() => auth.canManageKnowledgeBases, allowed => {
  if (!allowed) {
    createDialog.value?.close()
    deleteDialog.value?.close()
    notice.value = '当前账号可查看知识库，管理操作需要管理员权限。'
  }
})
</script>

<template>
  <section id="knowledge-bases" class="page" aria-labelledby="page-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">团队知识空间</p>
        <h1 id="page-title">知识库</h1>
        <p class="page-description">集中整理团队文档，让知识有处可寻。</p>
      </div>
      <div class="heading-actions">
        <span class="demo-badge">MySQL 数据</span>
        <button v-if="auth.canManageKnowledgeBases" type="button" class="primary-button" :disabled="isLoading || isSaving || !hasLoaded" @click="openCreateDialog">+ 新建知识库</button>
      </div>
    </div>
    <p v-if="!auth.canManageKnowledgeBases" class="demo-note">当前为普通用户，可搜索和查看知识库。新建、编辑和删除需要管理员权限。</p>
    <p v-if="notice" class="success-notice" role="status">{{ notice }}</p>
    <div class="load-controls">
      <span>数据来自本地后端服务</span>
      <button type="button" class="secondary-button" :disabled="isLoading || isSaving" @click="knowledgeBaseStore.loadKnowledgeBases()">重新加载</button>
    </div>
    <p v-if="isLoading" class="loading-notice" role="status">正在加载知识库…<span v-if="hasLoaded"> 下方保留上次加载的数据。</span></p>
    <div v-if="loadError" class="load-error" role="alert">
      <p>{{ loadError }}<span v-if="hasLoaded"> 已保留上次加载的数据。</span></p>
      <button type="button" class="secondary-button" @click="knowledgeBaseStore.loadKnowledgeBases()">重试</button>
    </div>
    <div v-if="hasLoaded" class="search-panel" role="search" aria-label="搜索知识库">
      <label for="knowledge-search">搜索知识库</label>
      <div class="search-controls">
        <input id="knowledge-search" v-model="searchQuery" type="search"
          placeholder="输入名称、描述或分类" aria-describedby="search-summary" />
        <button type="button" class="secondary-button" :disabled="!searchQuery" @click="searchQuery = ''">清空</button>
      </div>
      <p id="search-summary" role="status">显示 {{ filteredKnowledgeBases.length }} / {{ knowledgeBases.length }} 个知识库</p>
    </div>
    <div v-if="hasLoaded" class="section-heading">
      <h2>全部知识库 <span>{{ knowledgeBases.length }}</span></h2>
      <span>按业务领域整理</span>
    </div>
    <div v-if="filteredKnowledgeBases.length" class="knowledge-grid">
      <KnowledgeBaseCard
        v-for="knowledgeBase in filteredKnowledgeBases"
        :key="knowledgeBase.id"
        :knowledge-base="knowledgeBase"
        :busy="isLoading || isSaving"
        :can-manage="auth.canManageKnowledgeBases"
        @edit="openEditDialog"
        @delete="openDeleteDialog"
        @view-detail="openKnowledgeBaseDetail"
      />
    </div>
    <div v-else-if="hasLoaded" class="empty-panel">
      <h2>没有匹配的知识库</h2>
      <p>试试其他关键词，或清空搜索查看全部知识库。</p>
    </div>
    <p class="demo-note">当前记录保存在 MySQL 中，刷新页面或重启后端后仍可读取。</p>
    <dialog @cancel="isSaving && $event.preventDefault()" ref="createDialog" class="create-dialog" aria-labelledby="create-title" aria-describedby="create-hint">
      <form novalidate @submit.prevent="saveKnowledgeBase">
        <h2 id="create-title">{{ editingId === null ? '新建知识库' : '编辑知识库' }}</h2>
        <p id="create-hint" class="form-hint">为一类团队资料建立知识库。提交成功后保存到 MySQL，重启后端仍会保留。</p>
        <label for="kb-name">名称 <span>必填</span></label>
        <input id="kb-name" v-model="name" type="text" :disabled="isSaving" maxlength="60" required autofocus
          placeholder="例如：产品设计知识库" :aria-invalid="error ? 'true' : undefined"
          :aria-describedby="error ? 'create-error' : undefined" />
        <label for="kb-description">描述 <span>选填</span></label>
        <textarea id="kb-description" v-model="description" :disabled="isSaving" rows="3" maxlength="300"
          placeholder="简单说明这个知识库收录什么资料" />
        <p v-if="error" id="create-error" class="form-error" role="alert">{{ error }}</p>
        <div class="dialog-actions">
          <button type="button" class="secondary-button" :disabled="isSaving" @click="createDialog.close()">取消</button>
          <button type="submit" class="primary-button" :disabled="isSaving">{{ isSaving ? '正在提交…' : editingId === null ? '创建' : '保存修改' }}</button>
        </div>
      </form>
    </dialog>
    <dialog ref="deleteDialog" class="create-dialog" aria-labelledby="delete-title"
      @cancel="isSaving && $event.preventDefault()">
      <form @submit.prevent="confirmDelete">
        <h2 id="delete-title">删除知识库</h2>
        <p class="delete-warning">确定删除“{{ deleteTarget?.name }}”？这条知识库记录将从数据库中删除，无法在页面中恢复。</p>
        <p class="form-hint">当前尚未接入真实文档，本操作只删除知识库记录。</p>
        <p v-if="deleteError" class="form-error" role="alert">{{ deleteError }}</p>
        <div class="dialog-actions">
          <button type="button" class="secondary-button" autofocus :disabled="isSaving" @click="deleteDialog.close()">取消</button>
          <button type="submit" class="danger-button" :disabled="isSaving">{{ isSaving ? '正在删除…' : '确认删除' }}</button>
        </div>
      </form>
    </dialog>
    <dialog ref="detailDialog" class="create-dialog" aria-labelledby="detail-title">
      <h2 id="detail-title">知识库详情</h2>
      <dl v-if="selectedKnowledgeBase" class="detail-fields">
        <dt>名称</dt><dd>{{ selectedKnowledgeBase.name }}</dd>
        <dt>描述</dt><dd>{{ selectedKnowledgeBase.description }}</dd>
        <dt>分类</dt><dd>{{ selectedKnowledgeBase.category }}</dd>
        <dt>文档数量</dt><dd>{{ selectedKnowledgeBase.documentCount }} 份文档</dd>
      </dl>
      <p class="form-hint">展示本次列表读取的后端记录，尚未接入真实文档。</p>
      <div class="dialog-actions">
        <button type="button" class="primary-button" autofocus @click="detailDialog.close()">关闭</button>
      </div>
    </dialog>
  </section>
</template>
