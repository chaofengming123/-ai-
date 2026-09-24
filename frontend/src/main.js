import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router/index.js'
import './style.css'

import { useAuthStore } from './stores/auth.js'
import { http } from './api/http.js'
import { installAuthInterceptors } from './api/authInterceptors.js'
import { LOGIN_MEMORY_KEY } from './utils/loginMemory.js'

const pinia = createPinia()
const app = createApp(App).use(pinia)
const auth = useAuthStore(pinia)
installAuthInterceptors(http, auth)
// Keep the five-minute return window fresh while this page is visible.
setInterval(() => { if (document.visibilityState === 'visible') auth.rememberSession() }, 30000)
window.addEventListener('pagehide', () => auth.rememberSession())
document.addEventListener('visibilitychange', () => auth.rememberSession())
window.addEventListener('storage', event => {
  if ((event.key === LOGIN_MEMORY_KEY || event.key === null) && event.newValue === null)
    auth.logout('登录已在其他页面退出。')
})
auth.restoreSession().finally(() => app.use(router).mount('#app'))
