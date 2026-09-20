import axios from 'axios'

export const http = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

export function apiErrorMessage(error, { creating = false } = {}) {
  const status = error.response?.status
  const message = error.response?.data?.message
  if (status >= 400 && status < 500 && typeof message === 'string') return message
  if (creating) return '未能确认创建结果。请保留输入，关闭弹窗并重新加载列表，确认是否已创建后再提交。'
  if (error.code === 'ECONNABORTED') return '请求超时，请稍后重试。'
  return '无法加载知识库，请确认后端服务已启动后重试。'
}
