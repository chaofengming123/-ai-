<script setup>
import { onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useKnowledgeBaseStore } from '../stores/knowledgeBases.js'
import { useAuthStore } from '../stores/auth.js'
const auth = useAuthStore()

const knowledgeBaseStore = useKnowledgeBaseStore()
const { knowledgeBaseCount, isLoading, loadError, hasLoaded } = storeToRefs(knowledgeBaseStore)
onMounted(() => {
  if (auth.canReadKnowledgeBases && !hasLoaded.value) knowledgeBaseStore.loadKnowledgeBases()
})
</script>

<template>
  <section class="page" aria-labelledby="dashboard-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">团队知识空间</p>
        <h1 id="dashboard-title">工作台</h1>
        <p class="page-description">{{ auth.user?.username }}，欢迎回来。当前身份：{{ auth.roleLabel }}。</p>
      </div>
    </div>
    <div v-if="auth.canReadKnowledgeBases" class="overview-stat" aria-label="知识库统计">
      <span>知识库总数</span>
      <strong v-if="hasLoaded">{{ knowledgeBaseCount }}</strong>
      <p v-if="isLoading" role="status">正在加载知识库统计…</p>
      <div v-if="loadError" class="load-error" role="alert">
        <p>{{ loadError }}<span v-if="hasLoaded"> 当前显示上次成功加载的数量。</span></p>
        <button type="button" class="secondary-button" :disabled="isLoading" @click="knowledgeBaseStore.loadKnowledgeBases()">重试</button>
      </div>
      <p>统计本次读取的数据库记录。刷新页面或重启后端不会清空。</p>
    </div>
    <div class="empty-panel">
      <h2>{{ auth.canCreateKnowledgeBases ? '管理团队知识' : '查阅与提问' }}</h2>
      <p>{{ auth.canCreateKnowledgeBases ? '创建知识库、上传资料并建立索引，为团队提供可追溯的答案。' : '浏览有权查看的资料，或在 AI 问答中查询所需信息。' }}</p>
      <div class="workspace-shortcuts">
        <RouterLink v-if="auth.user?.permissions?.includes('chat:send')" to="/chat" class="primary-button page-link">开始提问</RouterLink>
        <RouterLink v-if="auth.canReadKnowledgeBases" to="/knowledge-bases" class="secondary-button page-link">浏览知识库</RouterLink>
      </div>
    </div>
  </section>
</template>
