<script setup>
import { RouterView, useRoute } from 'vue-router'
import AppSidebar from './components/AppSidebar.vue'

const route = useRoute()
</script>

<template>
  <div class="app-layout">
    <AppSidebar />
    <main id="main-content" class="main-content">
      <header class="topbar">
        <span>企业工作空间 <span class="breadcrumb">/ {{ route.meta.title }}</span></span>
        <span class="workspace-badge">本地学习版</span>
      </header>
      <RouterView v-slot="{ Component }">
        <!-- 保留知识库页面的内存状态，切换导航不会丢失刚创建的记录。 -->
        <KeepAlive include="KnowledgeBaseView">
          <component :is="Component" />
        </KeepAlive>
      </RouterView>
    </main>
  </div>
</template>
