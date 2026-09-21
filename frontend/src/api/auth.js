import { http } from './http.js'

export async function registerUser(username, password) {
  const response = await http.post('/auth/register', { username, password })
  return response.data
}
