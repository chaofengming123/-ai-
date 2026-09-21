<script setup>
import { RouterLink, RouterView, useRoute } from 'vue-router'
import AppSidebar from './components/AppSidebar.vue'

import { useAuthStore } from './stores/auth.js'
const auth = useAuthStore()
const route = useRoute()
</script>

<template>
  <div class="app-layout">
    <AppSidebar />
    <main id="main-content" class="main-content">
      <header class="topbar">
        <span>企业工作空间 <span class="breadcrumb">/ {{ route.meta.title }}</span></span>
        <div class="account-controls">
          <template v-if="auth.isLoggedIn">
            <span>{{ auth.user.username }}</span>
            <button type="button" class="secondary-button" @click="auth.logout()">退出</button>
          </template>
          <RouterLink v-else to="/login">登录</RouterLink>
        </div>
      </header>
      <RouterView v-slot="{ Component }">
        <!-- 保留搜索词和表单等页面状态；知识库业务数据由 Pinia 共享管理。 -->
        <KeepAlive include="KnowledgeBaseView">
          <component :is="Component" />
        </KeepAlive>
      </RouterView>
    </main>
  </div>
</template>
