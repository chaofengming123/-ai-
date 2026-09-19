<script setup>
import { ref } from 'vue'
import { knowledgeBases as initialKnowledgeBases } from '../data/knowledgeBases.js'

// 将初始数据复制到响应式列表，新增操作不会修改原始示例数组。
const knowledgeBases = ref(initialKnowledgeBases.map(item => ({ ...item })))
const createDialog = ref(null)
const name = ref('')
const description = ref('')
const error = ref('')
const notice = ref('')

function openCreateDialog() {
  name.value = ''
  description.value = ''
  error.value = ''
  notice.value = ''
  createDialog.value.showModal()
}

function createKnowledgeBase() {
  const trimmedName = name.value.trim()
  const trimmedDescription = description.value.trim()

  if (!trimmedName) {
    error.value = '请输入知识库名称，不能只填写空格。'
    return
  }
  if (trimmedName.length > 60 || trimmedDescription.length > 300) {
    error.value = '名称最多 60 个字符，描述最多 300 个字符。'
    return
  }
  if (knowledgeBases.value.some(item => item.name === trimmedName)) {
    error.value = '这个名称已经存在，请换一个名称。'
    return
  }

  knowledgeBases.value.push({
    id: crypto.randomUUID(),
    name: trimmedName,
    description: trimmedDescription || '暂无描述',
    documentCount: 0,
    category: '自建知识库',
  })
  createDialog.value.close()
  notice.value = `已创建“${trimmedName}”。刷新页面后将恢复示例数据。`
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
        <span class="demo-badge">演示数据</span>
        <button type="button" class="primary-button" @click="openCreateDialog">+ 新建知识库</button>
      </div>
    </div>
    <p v-if="notice" class="success-notice" role="status">{{ notice }}</p>
    <div class="section-heading">
      <h2>全部知识库 <span>{{ knowledgeBases.length }}</span></h2>
      <span>按业务领域整理</span>
    </div>
    <div class="knowledge-grid">
      <article v-for="knowledgeBase in knowledgeBases" :key="knowledgeBase.id" class="knowledge-card">
        <div class="card-topline">
          <span class="folder-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v10H3V7Z" />
              <path d="M3 10h18" />
            </svg>
          </span>
          <span class="category">{{ knowledgeBase.category }}</span>
        </div>
        <h3>{{ knowledgeBase.name }}</h3>
        <p>{{ knowledgeBase.description }}</p>
        <footer><span class="document-dot" aria-hidden="true"></span>{{ knowledgeBase.documentCount }} 份文档</footer>
      </article>
    </div>
    <p class="demo-note">当前使用模拟数据；新增内容仅在本次页面打开期间有效，刷新后恢复初始数据。</p>
    <dialog ref="createDialog" class="create-dialog" aria-labelledby="create-title" aria-describedby="create-hint">
      <form novalidate @submit.prevent="createKnowledgeBase">
        <h2 id="create-title">新建知识库</h2>
        <p id="create-hint" class="form-hint">为一类团队资料建立知识库。刷新页面后，新增内容不会保留。</p>
        <label for="kb-name">名称 <span>必填</span></label>
        <input id="kb-name" v-model="name" type="text" maxlength="60" required autofocus
          placeholder="例如：产品设计知识库" :aria-invalid="error ? 'true' : undefined"
          :aria-describedby="error ? 'create-error' : undefined" />
        <label for="kb-description">描述 <span>选填</span></label>
        <textarea id="kb-description" v-model="description" rows="3" maxlength="300"
          placeholder="简单说明这个知识库收录什么资料" />
        <p v-if="error" id="create-error" class="form-error" role="alert">{{ error }}</p>
        <div class="dialog-actions">
          <button type="button" class="secondary-button" @click="createDialog.close()">取消</button>
          <button type="submit" class="primary-button">创建</button>
        </div>
      </form>
    </dialog>
  </section>
</template>
