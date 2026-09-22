import { http } from './http.js'
export async function fetchChatConfiguration(signal) {
  return (await http.get('/chat/config', { signal })).data
}
export async function sendChat(messages, signal) {
  return (await http.post('/chat', { messages }, { signal, timeout: 55000 })).data
}
