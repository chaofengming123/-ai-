<script setup>
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { watch } from 'vue'
import AppSidebar from './components/AppSidebar.vue'
import { canVisit } from './utils/permissions.js'

import { useAuthStore } from './stores/auth.js'
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
watch(() => auth.isLoggedIn, loggedIn => {
  if (!loggedIn && route.meta.requiresAuth) {
    router.replace({ path: '/login', query: { redirect: route.path } })
  }
})
</script>

<template>
  <div class="app-layout">
    <AppSidebar />
    <main id="main-content" class="main-content">
      <header class="topbar">
        <span>企业工作空间 <span class="breadcrumb">/ {{ route.meta.title }}</span></span>
        <div class="account-controls">
          <template v-if="auth.isLoggedIn">
            <span>{{ auth.user.username }} · {{ auth.roleLabel }}</span>
            <button type="button" class="secondary-button" @click="auth.logout()">退出</button>
          </template>
          <RouterLink v-else to="/login">登录</RouterLink>
        </div>
      </header>
      <RouterView v-slot="{ Component }">
        <!-- 保留搜索词和表单等页面状态；知识库业务数据由 Pinia 共享管理。 -->
        <KeepAlive :key="auth.sessionVersion" include="KnowledgeBaseView">
          <component v-if="!route.meta.requiresAuth || (auth.isLoggedIn && canVisit(auth.user, route.path))" :is="Component" />
          <p v-else class="empty-panel">当前账号没有访问此页面的权限，请从左侧选择可用功能。</p>
        </KeepAlive>
      </RouterView>
    </main>
  </div>
</template>
