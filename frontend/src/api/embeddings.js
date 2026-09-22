import { http } from './http.js'
export async function fetchEmbeddingConfiguration(signal) {
  return (await http.get('/embeddings/config', { signal })).data
}
export async function compareEmbeddings(query, candidates, signal) {
  return (await http.post('/embeddings/compare', { query, candidates }, { signal, timeout: 100000 })).data
}
export async function storedVectorStatus(signal) {
  return (await http.get('/embeddings/stored', { signal, timeout: 15000 })).data
}
export async function saveVectors(texts, signal) {
  return (await http.post('/embeddings/stored', { texts }, { signal, timeout: 150000 })).data
}
export async function searchVectors(query, signal) {
  return (await http.post('/embeddings/stored/search', { query }, { signal, timeout: 120000 })).data
}
