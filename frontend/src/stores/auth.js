import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { loginUser, fetchCurrentUser } from '../api/auth.js'

export const useAuthStore = defineStore('auth', () => {
  const user = ref(null)
  const token = ref('')
  const isBusy = ref(false)
  const notice = ref('')
  const isLoggedIn = computed(() => Boolean(user.value && token.value))
  let generation = 0
  let expiryTimer

  function logout(message = '已退出登录。') {
    generation++
    clearTimeout(expiryTimer)
    token.value = ''
    user.value = null
    isBusy.value = false
    notice.value = message
  }

  async function login(username, password) {
    if (isBusy.value) return { error: '正在处理，请稍候。' }
    const requestGeneration = ++generation
    isBusy.value = true
    notice.value = ''
    try {
      const result = await loginUser(username, password)
      if (generation !== requestGeneration) return { error: '登录操作已取消。' }
      clearTimeout(expiryTimer)
      token.value = result.accessToken
      user.value = result.user
      expiryTimer = setTimeout(() => logout('登录已到期，请重新登录。'), result.expiresIn * 1000)
      expiryTimer.unref?.()
      return { success: true }
    } catch (error) {
      return { error: error.response?.status === 401
        ? '用户名或密码不正确。'
        : '暂时无法登录，请确认后端服务正常后重试。' }
    } finally {
      if (generation === requestGeneration) isBusy.value = false
    }
  }

  async function verifySession() {
    if (!token.value || isBusy.value) return
    const requestGeneration = generation
    isBusy.value = true
    notice.value = ''
    try {
      const current = await fetchCurrentUser(token.value)
      if (generation !== requestGeneration) return
      user.value = current
      notice.value = '后端已确认当前登录身份。'
    } catch (error) {
      if (generation !== requestGeneration) return
      if (error.response?.status === 401) logout('登录已失效，请重新登录。')
      else notice.value = '暂时无法核对登录状态，请稍后重试。'
    } finally {
      if (generation === requestGeneration) isBusy.value = false
    }
  }

  return { user, isLoggedIn, isBusy, notice, login, logout, verifySession }
})
