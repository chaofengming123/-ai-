<script setup>
import { RouterLink } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useKnowledgeBaseStore } from '../stores/knowledgeBases.js'

const knowledgeBaseStore = useKnowledgeBaseStore()
const { knowledgeBaseCount } = storeToRefs(knowledgeBaseStore)
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
      <strong>{{ knowledgeBaseCount }}</strong>
      <p>包含示例与本次新建的知识库。刷新后恢复示例数据。</p>
    </div>
    <div class="empty-panel">
      <h2>建立你的知识库</h2>
      <p>按业务领域整理资料。当前可以查看示例知识库，或创建一个临时知识库。</p>
      <RouterLink to="/knowledge-bases" class="primary-button page-link">前往知识库</RouterLink>
    </div>
  </section>
</template>
