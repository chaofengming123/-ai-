import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '../views/DashboardView.vue'
import KnowledgeBaseView from '../views/KnowledgeBaseView.vue'
import DocumentView from '../views/DocumentView.vue'
import ChatView from '../views/ChatView.vue'
import NotFoundView from '../views/NotFoundView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/knowledge-bases' },
    { path: '/dashboard', component: DashboardView, meta: { title: '工作台' } },
    { path: '/knowledge-bases', component: KnowledgeBaseView, meta: { title: '知识库' } },
    { path: '/documents', component: DocumentView, meta: { title: '文档管理' } },
    { path: '/chat', component: ChatView, meta: { title: 'AI 问答' } },
    { path: '/:pathMatch(.*)*', component: NotFoundView, meta: { title: '页面不存在' } },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})

router.afterEach(to => {
  document.title = `${to.meta.title} · AI Knowledge`
})

export default router
