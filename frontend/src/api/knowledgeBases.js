import { knowledgeBases as initialKnowledgeBases } from '../data/knowledgeBases.js'

// 仅用于教学：浏览器内存中的模拟数据源，不是服务器或数据库。
const mockRecords = initialKnowledgeBases.map(item => ({ ...item }))

export function fetchKnowledgeBases({ simulateFailure = false } = {}) {
  return new Promise((resolve, reject) => {
    setTimeout(() => {
      if (simulateFailure) {
        reject(new Error('模拟加载失败，请点击重试。'))
        return
      }
      resolve(mockRecords.map(item => ({ ...item })))
    }, 1000)
  })
}

// 本课仅让“读取”异步化，创建仍同步写入模拟数据源。
export function insertMockKnowledgeBase(record) {
  mockRecords.push({ ...record })
}
