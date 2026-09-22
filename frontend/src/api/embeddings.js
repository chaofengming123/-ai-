import { http } from './http.js'
export async function fetchEmbeddingConfiguration(signal) {
  return (await http.get('/embeddings/config', { signal })).data
}
export async function compareEmbeddings(query, candidates, signal) {
  return (await http.post('/embeddings/compare', { query, candidates }, { signal, timeout: 100000 })).data
}
