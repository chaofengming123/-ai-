<script setup>
import { ref } from 'vue'
import { registerUser } from '../api/auth.js'
import { validateRegistration } from '../utils/registration.js'

const username = ref('')
const password = ref('')
const confirmation = ref('')
const isSubmitting = ref(false)
const error = ref('')
const createdUser = ref(null)

async function submit() {
  if (isSubmitting.value) return
  createdUser.value = null
  error.value = validateRegistration(username.value, password.value, confirmation.value)
  if (error.value) return
  isSubmitting.value = true
  try {
    createdUser.value = await registerUser(username.value, password.value)
    username.value = ''
  } catch (failure) {
    const status = failure.response?.status
    error.value = (status === 400 || status === 409) && typeof failure.response?.data?.message === 'string'
      ? failure.response.data.message
      : '未能确认账号是否创建成功，请稍后核对；不要连续重复提交。'
  } finally {
    // 密码只留在本页表单；请求结束后清空，不写入 Pinia 或本地存储。
    password.value = ''
    confirmation.value = ''
    isSubmitting.value = false
  }
}
</script>

<template>
  <section class="page" aria-labelledby="register-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">账号基础 · 第十三课</p>
        <h1 id="register-title">创建账号</h1>
        <p class="page-description">为后续登录建立你的学习账号。</p>
      </div>
    </div>
    <form class="registration-form" novalidate @submit.prevent="submit" :aria-busy="isSubmitting">
      <p class="form-hint">本课完成账号创建。登录功能将在下一课接入，创建成功后不会自动登录。</p>
      <label for="register-username">用户名</label>
      <input id="register-username" v-model="username" type="text" autocomplete="username"
        spellcheck="false" autocapitalize="none" :disabled="isSubmitting" aria-describedby="username-hint" />
      <p id="username-hint" class="form-hint">3–32 位英文字母、数字或下划线，统一保存为小写。</p>
      <label for="register-password">密码</label>
      <input id="register-password" v-model="password" type="password" autocomplete="new-password"
        :disabled="isSubmitting" aria-describedby="password-hint" />
      <p id="password-hint" class="form-hint">至少 12 个字符，最长 72 个英文字母或数字；中文等多字节字符可输入的数量更少。首尾空格会保留。</p>
      <label for="register-confirmation">确认密码</label>
      <input id="register-confirmation" v-model="confirmation" type="password" autocomplete="new-password"
        :disabled="isSubmitting" />
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <p v-if="createdUser" class="success-notice" role="status">
        已创建账号“{{ createdUser.username }}”（编号 {{ createdUser.id }}）。下一课将使用账号登录。
      </p>
      <button class="primary-button" type="submit" :disabled="isSubmitting">
        {{ isSubmitting ? '正在创建…' : '创建账号' }}
      </button>
    </form>
  </section>
</template>
