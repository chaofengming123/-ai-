import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '../views/DashboardView.vue'
import KnowledgeBaseView from '../views/KnowledgeBaseView.vue'
import DocumentView from '../views/DocumentView.vue'
import ChatView from '../views/ChatView.vue'
import EmbeddingView from '../views/EmbeddingView.vue'
import LoginView from '../views/LoginView.vue'
import RegisterView from '../views/RegisterView.vue'
import NotFoundView from '../views/NotFoundView.vue'

import { useAuthStore } from '../stores/auth.js'
import { authGuard } from './authGuard.js'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/knowledge-bases' },
    { path: '/dashboard', component: DashboardView, meta: { title: '工作台', requiresAuth: true } },
    { path: '/knowledge-bases', component: KnowledgeBaseView, meta: { title: '知识库', requiresAuth: true } },
    { path: '/documents', component: DocumentView, meta: { title: '文档管理', requiresAuth: true } },
    { path: '/login', component: LoginView, meta: { title: '登录' } },
    { path: '/register', component: RegisterView, meta: { title: '创建账号' } },
    { path: '/chat', component: ChatView, meta: { title: 'AI 问答', requiresAuth: true } },
    { path: '/embeddings', component: EmbeddingView, meta: { title: '向量实验', requiresAuth: true } },
    { path: '/:pathMatch(.*)*', component: NotFoundView, meta: { title: '页面不存在' } },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})

router.beforeEach(to => authGuard(to, useAuthStore().isLoggedIn))

router.afterEach(to => {
  document.title = `${to.meta.title} · AI Knowledge`
})

export default router
