<script setup>
import AppSidebar from './components/AppSidebar.vue'
import KnowledgeBaseView from './views/KnowledgeBaseView.vue'
</script>

<template>
  <div class="app-layout">
    <AppSidebar />
    <main id="main-content" class="main-content">
      <header class="topbar">
        <span>企业工作空间 <span class="breadcrumb">/ 知识库</span></span>
        <span class="workspace-badge">本地学习版</span>
      </header>
      <KnowledgeBaseView />
    </main>
  </div>
</template>
