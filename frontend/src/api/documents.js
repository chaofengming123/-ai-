import { http } from './http.js'

export async function fetchDocuments(knowledgeBaseId, signal) {
  return (await http.get('/documents', { params: { knowledgeBaseId }, signal })).data
}
export async function uploadDocument(knowledgeBaseId, file) {
  const form = new FormData()
  form.append('knowledgeBaseId', String(knowledgeBaseId))
  form.append('file', file)
  // 浏览器自动生成 multipart boundary，不手动填写 Content-Type。
  return (await http.post('/documents', form, { timeout: 30000 })).data
}

export async function downloadDocument(id) {
  return (await http.get(`/documents/${id}/download`, { responseType: 'blob', timeout: 30000 })).data
}
export async function fetchDocumentText(id, signal) {
  return (await http.get(`/documents/${id}/text`, { signal, timeout: 30000 })).data
}
