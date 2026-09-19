<script setup>
import { knowledgeBases } from '../data/knowledgeBases.js'
</script>

<template>
  <section id="knowledge-bases" class="page" aria-labelledby="page-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">团队知识空间</p>
        <h1 id="page-title">知识库</h1>
        <p class="page-description">集中整理团队文档，让知识有处可寻。</p>
      </div>
      <span class="demo-badge">演示数据</span>
    </div>
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
    <p class="demo-note">当前展示示例知识库，文档内容尚未接入。</p>
  </section>
</template>
