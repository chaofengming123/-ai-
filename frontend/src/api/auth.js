import { http } from './http.js'

export async function registerUser(username, password) {
  const response = await http.post('/auth/register', { username, password })
  return response.data
}

export async function loginUser(username, password) {
  return (await http.post('/auth/login', { username, password })).data
}

export async function fetchCurrentUser(token) {
  return (await http.get('/auth/me', {
    headers: { Authorization: 'Bearer ' + token },
  })).data
}
