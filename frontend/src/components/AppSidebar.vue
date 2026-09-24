<script setup>
import { RouterLink } from 'vue-router'
import { computed } from 'vue'
import { useAuthStore } from '../stores/auth.js'
import { canVisit } from '../utils/permissions.js'
const auth = useAuthStore()

const navigation = [
  { path: '/dashboard', label: '工作台', icon: '▦' },
  { path: '/knowledge-bases', label: '知识库', icon: '▤' },
  { path: '/documents', label: '文档资料', icon: '▧' },
  { path: '/chat', label: 'AI 问答', icon: '✧' },
  { path: '/login', label: '登录', icon: '○' },
  { path: '/register', label: '创建账号', icon: '＋' },
]
const visibleNavigation = computed(() => navigation.filter(item =>
  auth.isLoggedIn ? !['/login', '/register'].includes(item.path) && canVisit(auth.user, item.path)
    : ['/login', '/register'].includes(item.path)))
</script>

<template>
  <aside class="sidebar">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <div class="brand">
      <span class="brand-icon" aria-hidden="true">K</span>
      <div><strong>AI Knowledge</strong><small>企业知识管理平台</small></div>
    </div>
    <p class="nav-label">工作空间</p>
    <nav aria-label="主导航">
      <RouterLink v-for="item in visibleNavigation" :key="item.path" :to="item.path"
        class="nav-item" exact-active-class="active">
        <span aria-hidden="true">{{ item.icon }}</span> {{ item.path === '/documents' && auth.user?.permissions?.includes('document:upload') ? '文档管理' : item.label }}
      </RouterLink>
    </nav>
    <div class="sidebar-footer"><span class="status-dot"></span> 本地工作空间 <span>v0.1</span></div>
  </aside>
</template>
