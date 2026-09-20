<script setup>
import { onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useKnowledgeBaseStore } from '../stores/knowledgeBases.js'

const knowledgeBaseStore = useKnowledgeBaseStore()
const { knowledgeBaseCount, isLoading, loadError, hasLoaded } = storeToRefs(knowledgeBaseStore)
onMounted(() => {
  if (!hasLoaded.value) knowledgeBaseStore.loadKnowledgeBases()
})
</script>

<template>
  <section class="page" aria-labelledby="dashboard-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">团队知识空间</p>
        <h1 id="dashboard-title">工作台</h1>
        <p class="page-description">从整理团队知识开始。</p>
      </div>
    </div>
    <div class="overview-stat" aria-label="知识库统计">
      <span>知识库总数</span>
      <strong v-if="hasLoaded">{{ knowledgeBaseCount }}</strong>
      <p v-if="isLoading" role="status">正在加载知识库统计…</p>
      <div v-if="loadError" class="load-error" role="alert">
        <p>{{ loadError }}<span v-if="hasLoaded"> 当前显示上次成功加载的数量。</span></p>
        <button type="button" class="secondary-button" :disabled="isLoading" @click="knowledgeBaseStore.loadKnowledgeBases()">重试</button>
      </div>
      <p>统计本次读取的后端记录。刷新不清空数据，重启后端后恢复示例。</p>
    </div>
    <div class="empty-panel">
      <h2>建立你的知识库</h2>
      <p>按业务领域整理资料。当前可以查看示例知识库，或创建一个知识库。</p>
      <RouterLink to="/knowledge-bases" class="primary-button page-link">前往知识库</RouterLink>
    </div>
  </section>
</template>
