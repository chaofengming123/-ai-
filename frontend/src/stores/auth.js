import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { useKnowledgeBaseStore } from './knowledgeBases.js'
import { hasPermission, canManageKnowledgeBases as canManage, roleLabel as labelRole } from '../utils/permissions.js'
import { loginUser, fetchCurrentUser } from '../api/auth.js'
import { rememberLogin, readLoginMemory, clearLoginMemory } from '../utils/loginMemory.js'

export const useAuthStore = defineStore('auth', () => {
  const user = ref(null)
  const token = ref('')
  const isBusy = ref(false)
  const notice = ref('')
  const canManageKnowledgeBases = computed(() => isLoggedIn.value && canManage(user.value))
  const canReadKnowledgeBases = computed(() => isLoggedIn.value && hasPermission(user.value, 'read'))
  const canCreateKnowledgeBases = computed(() => isLoggedIn.value && hasPermission(user.value, 'create'))
  const canEditKnowledgeBases = computed(() => isLoggedIn.value && hasPermission(user.value, 'update'))
  const canDeleteKnowledgeBases = computed(() => isLoggedIn.value && hasPermission(user.value, 'delete'))
  const roleLabel = computed(() => labelRole(user.value))
  const isLoggedIn = computed(() => Boolean(user.value && token.value))
  const sessionVersion = ref(0)
  const accessToken = computed(() => token.value)
  let generation = 0
  let expiryTimer
  let tokenExpiresAt = 0
  function rememberSession() {
    if (isLoggedIn.value) rememberLogin(token.value, tokenExpiresAt)
  }
  function scheduleExpiry() {
    clearTimeout(expiryTimer)
    expiryTimer = setTimeout(() => logout('登录已到期，请重新登录。'), Math.max(0, tokenExpiresAt - Date.now()))
    expiryTimer.unref?.()
  }
  async function restoreSession() {
    if (isBusy.value || isLoggedIn.value) return
    const saved = readLoginMemory()
    if (!saved) return
    const current = ++generation
    isBusy.value = true
    token.value = saved.accessToken // 供身份验证请求使用；user 仍为空，不授予页面访问权限。
    try {
      const identity = await fetchCurrentUser(saved.accessToken)
      if (current !== generation) return
      if (Date.now() >= Math.min(saved.tokenExpiresAt, saved.restoreUntil)) { token.value = ''; clearLoginMemory(); return }
      token.value = saved.accessToken
      tokenExpiresAt = saved.tokenExpiresAt
      user.value = identity
      sessionVersion.value++
      scheduleExpiry()
      rememberSession()
    } catch {
      if (current === generation) {
        token.value = ''
        clearLoginMemory()
        notice.value = '无法恢复登录，请重新登录。'
      }
    } finally { if (current === generation) isBusy.value = false }
  }

  function logout(message = '已退出登录。') {
    clearLoginMemory()
    tokenExpiresAt = 0
    generation++
    sessionVersion.value++
    useKnowledgeBaseStore().reset()
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
      sessionVersion.value++
      useKnowledgeBaseStore().reset()
      token.value = result.accessToken
      user.value = result.user
      tokenExpiresAt = Date.now() + result.expiresIn * 1000
      scheduleExpiry()
      rememberSession()
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
      if (JSON.stringify(user.value?.permissions) !== JSON.stringify(current.permissions)) {
        useKnowledgeBaseStore().reset()
        sessionVersion.value++ // 权限变化后销毁旧页面及其缓存。
      }
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

  return { restoreSession, rememberSession, canReadKnowledgeBases, canCreateKnowledgeBases, canEditKnowledgeBases, canDeleteKnowledgeBases, canManageKnowledgeBases, roleLabel, accessToken, sessionVersion, user, isLoggedIn, isBusy, notice, login, logout, verifySession }
})
