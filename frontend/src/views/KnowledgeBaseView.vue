<script setup>
import { computed, onMounted, ref } from 'vue'
import KnowledgeBaseCard from '../components/KnowledgeBaseCard.vue'
import { storeToRefs } from 'pinia'
import { useKnowledgeBaseStore } from '../stores/knowledgeBases.js'

// 两个页面使用同一份 store，搜索词与弹窗仍由当前页面管理。
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
  name.value = ''
  description.value = ''
  error.value = ''
  notice.value = ''
  createDialog.value.showModal()
}

async function createKnowledgeBase() {
  error.value = ''
  const result = await knowledgeBaseStore.addKnowledgeBase({
    name: name.value,
    description: description.value,
  })
  if (result.error) {
    error.value = result.error
    return
  }
  createDialog.value.close()
  notice.value = `已创建“${result.knowledgeBase.name}”。已保存到数据库。${searchQuery.value.trim() ? '当前列表仍按搜索词筛选。' : ''}`
}
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
        <button type="button" class="primary-button" :disabled="isLoading || isSaving || !hasLoaded" @click="openCreateDialog">+ 新建知识库</button>
      </div>
    </div>
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
        @view-detail="openKnowledgeBaseDetail"
      />
    </div>
    <div v-else-if="hasLoaded" class="empty-panel">
      <h2>没有匹配的知识库</h2>
      <p>试试其他关键词，或清空搜索查看全部知识库。</p>
    </div>
    <p class="demo-note">当前记录保存在 MySQL 中，刷新页面或重启后端后仍可读取。</p>
    <dialog @cancel="isSaving && $event.preventDefault()" ref="createDialog" class="create-dialog" aria-labelledby="create-title" aria-describedby="create-hint">
      <form novalidate @submit.prevent="createKnowledgeBase">
        <h2 id="create-title">新建知识库</h2>
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
          <button type="submit" class="primary-button" :disabled="isSaving">{{ isSaving ? '正在提交…' : '创建' }}</button>
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
