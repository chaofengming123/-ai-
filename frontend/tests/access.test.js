import test from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { installAuthInterceptors } from '../src/api/authInterceptors.js'
import { authGuard, safeDestination } from '../src/router/authGuard.js'
import { useKnowledgeBaseStore } from '../src/stores/knowledgeBases.js'
import { http } from '../src/api/http.js'

test('guard protects routes and return destinations stay inside known pages', () => {
  const to = { path: '/knowledge-bases', meta: { requiresAuth: true } }
  assert.deepEqual(authGuard(to, false), { path: '/login', query: { redirect: '/knowledge-bases' }, replace: true })
  assert.equal(authGuard(to, true), true)
  assert.equal(authGuard({ path: '/login', meta: {} }, false), true)
  for (const value of ['https://example.com', '//example.com', '/login', ['/dashboard'], undefined]) {
    assert.equal(safeDestination(value), '/knowledge-bases')
  }
  assert.equal(safeDestination('/dashboard'), '/dashboard')
})

test('interceptors attach only local protected tokens and ignore old 401 responses', async () => {
  const client = axios.create({ baseURL: '/api' })
  let logouts = 0
  let refreshes = 0
  const auth = { sessionVersion: 1, accessToken: 'first', isLoggedIn: true,
    verifySession() { refreshes++ },
    logout() { logouts++; this.sessionVersion++; this.isLoggedIn = false; this.accessToken = '' } }
  const dispose = installAuthInterceptors(client, auth)
  try {
    client.defaults.adapter = async config => ({ config, data: config.headers.get('Authorization'), status: 200, headers: {} })
    assert.equal((await client.get('/knowledge-bases')).data, 'Bearer first')
    assert.equal((await client.get('/documents?knowledgeBaseId=1')).data, 'Bearer first')
    assert.equal((await client.post('/documents', new FormData())).data, 'Bearer first')
    assert.equal((await client.get('/documents/12/download')).data, 'Bearer first')
    assert.equal((await client.post('/auth/login', {})).data, undefined)
    assert.equal((await client.get('https://example.com/knowledge-bases')).data, undefined)
    let rejectOld
    client.defaults.adapter = config => new Promise((resolve, reject) => {
      rejectOld = () => reject({ config, response: { status: 401 } })
    })
    const pending = client.get('/knowledge-bases')
    await new Promise(resolve => setImmediate(resolve))
    auth.sessionVersion++
    auth.accessToken = 'second'
    rejectOld()
    await assert.rejects(pending)
    assert.equal(logouts, 0)
    client.defaults.adapter = async config => { throw { config, response: { status: 403 } } }
    await assert.rejects(client.get('/knowledge-bases'))
    assert.equal(logouts, 0)
    assert.equal(refreshes, 1)
    client.defaults.adapter = async config => { throw { config, response: { status: 401 } } }
    await assert.rejects(client.get('/knowledge-bases'))
    assert.equal(logouts, 1)
  } finally { dispose() }
})

test('reset discards late reads and writes and does not clear a newer request busy state', async () => {
  setActivePinia(createPinia())
  const store = useKnowledgeBaseStore()
  const original = http.defaults.adapter
  const respond = (config, data) => ({ config, data, status: 200, headers: {} })
  try {
    let finish
    http.defaults.adapter = config => new Promise(resolve => { finish = data => resolve(respond(config, data)) })
    const oldLoad = store.loadKnowledgeBases()
    const finishOld = finish
    store.reset()
    const newLoad = store.loadKnowledgeBases()
    finishOld([{ id: 1, name: '旧账号数据' }])
    await oldLoad
    assert.equal(store.knowledgeBaseCount, 0)
    assert.equal(store.isLoading, true)
    finish([{ id: 2, name: '新账号数据' }])
    await newLoad
    assert.equal(store.knowledgeBases[0].id, 2)
    const pendingWrite = store.addKnowledgeBase({ name: '迟到写入', description: '' })
    store.reset()
    finish({ id: 3, name: '迟到写入' })
    assert.ok((await pendingWrite).error)
    assert.equal(store.knowledgeBaseCount, 0)
    assert.equal(store.hasLoaded, false)
    assert.equal(store.isSaving, false)
  } finally { http.defaults.adapter = original }
})
