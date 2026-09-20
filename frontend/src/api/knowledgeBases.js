import { http } from './http.js'

export async function fetchKnowledgeBases() {
  const response = await http.get('/knowledge-bases')
  if (!Array.isArray(response.data)) throw new Error('知识库列表响应格式不正确。')
  return response.data
}

export async function createKnowledgeBase(data) {
  const response = await http.post('/knowledge-bases', data)
  return response.data
}
