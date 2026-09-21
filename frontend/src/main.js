import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router/index.js'
import './style.css'

import { useAuthStore } from './stores/auth.js'
import { http } from './api/http.js'
import { installAuthInterceptors } from './api/authInterceptors.js'

const pinia = createPinia()
const app = createApp(App).use(pinia)
installAuthInterceptors(http, useAuthStore(pinia))
app.use(router).mount('#app')
