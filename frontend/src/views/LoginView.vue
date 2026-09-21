<script setup>
import { ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'
import { safeDestination } from '../router/authGuard.js'
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const username = ref('')
const password = ref('')
const error = ref('')

async function submit() {
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码。'
    return
  }
  try {
    const result = await auth.login(username.value, password.value)
    if (result.error) error.value = result.error
    else if (result.success) await router.replace(safeDestination(route.query.redirect))
  } finally {
    password.value = ''
  }
}
</script>

<template>
  <section class="page" aria-labelledby="login-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">登录与访问保护</p>
        <h1 id="login-title">登录</h1>
        <p class="page-description">使用上一课创建的账号，验证你的身份。</p>
      </div>
    </div>
    <div v-if="auth.isLoggedIn" class="registration-form">
      <h2>你好，{{ auth.user.username }}</h2>
      <p>账号编号：{{ auth.user.id }}</p>
      <p class="form-hint">本次登录最长 15 分钟。刷新页面需要重新登录。</p>
      <button type="button" class="primary-button" :disabled="auth.isBusy" @click="auth.verifySession()">
        {{ auth.isBusy ? '正在核对…' : '核对登录状态' }}
      </button>
      <button type="button" class="secondary-button" @click="auth.logout()">退出登录</button>
    </div>
    <form v-else class="registration-form" novalidate @submit.prevent="submit" :aria-busy="auth.isBusy">
      <label for="login-username">用户名</label>
      <input id="login-username" v-model="username" autocomplete="username" autocapitalize="none"
        spellcheck="false" :disabled="auth.isBusy" />
      <label for="login-password">密码</label>
      <input id="login-password" v-model="password" type="password" autocomplete="current-password"
        :disabled="auth.isBusy" />
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <button type="submit" class="primary-button" :disabled="auth.isBusy">
        {{ auth.isBusy ? '正在登录…' : '登录' }}
      </button>
      <RouterLink to="/register">还没有账号？创建账号</RouterLink>
    </form>
    <p v-if="auth.notice" class="success-notice" role="status">{{ auth.notice }}</p>
    <p class="demo-note">知识库需要登录后访问。当前所有登录用户共享知识库，尚未区分角色和数据归属。</p>
  </section>
</template>
