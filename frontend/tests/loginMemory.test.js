import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { http } from '../src/api/http.js'
import { installAuthInterceptors } from '../src/api/authInterceptors.js'
import { useAuthStore } from '../src/stores/auth.js'
import { rememberLogin, readLoginMemory, clearLoginMemory, LOGIN_MEMORY_KEY } from '../src/utils/loginMemory.js'

function storage(t) {
  const data = new Map()
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: key => data.get(key) ?? null,
    setItem: (key, value) => data.set(key, value), removeItem: key => data.delete(key) } })
  t.after(() => { if (previous) Object.defineProperty(globalThis, 'localStorage', previous); else delete globalThis.localStorage })
  return data
}

test('five-minute return window is capped by token expiry and rejects stale or corrupt records', t => {
  const data = storage(t)
  rememberLogin('token', 1000000, 100)
  assert.equal(readLoginMemory(299999).accessToken, 'token')
  assert.equal(readLoginMemory(300100), null)
  rememberLogin('token', 200, 100)
  assert.equal(readLoginMemory(200), null)
  data.set(LOGIN_MEMORY_KEY, '{bad')
  assert.equal(readLoginMemory(100), null)
  assert.equal(data.size, 0)
})

test('refresh restores only after backend verification, keeps latest permissions and logout clears memory', async t => {
  storage(t)
  setActivePinia(createPinia())
  const auth = useAuthStore()
  const original = http.defaults.adapter
  const uninstall = installAuthInterceptors(http, auth)
  try {
    rememberLogin('saved-token', Date.now() + 900000)
    http.defaults.adapter = async config => {
      assert.equal(auth.isLoggedIn, false)
      assert.equal(config.headers.get('Authorization'), 'Bearer saved-token')
      return { config, status: 200, headers: {}, data: { id: 1, permissions: ['chat:send'] } }
    }
    await auth.restoreSession()
    assert.equal(auth.isLoggedIn, true)
    assert.deepEqual(auth.user.permissions, ['chat:send'])
    auth.logout()
    assert.equal(readLoginMemory(), null)
    await auth.restoreSession()
    assert.equal(auth.isLoggedIn, false)
  } finally { auth.logout(); uninstall(); http.defaults.adapter = original }
})

test('failed identity verification and logout during restoration never restore a session', async t => {
  storage(t)
  setActivePinia(createPinia())
  const auth = useAuthStore()
  const original = http.defaults.adapter
  try {
    rememberLogin('bad-token', Date.now() + 900000)
    http.defaults.adapter = async () => { throw { response: { status: 401 } } }
    await auth.restoreSession()
    assert.equal(auth.accessToken, '')
    assert.equal(readLoginMemory(), null)
    rememberLogin('old-token', Date.now() + 900000)
    let finish
    http.defaults.adapter = config => new Promise(resolve => { finish = () => resolve({ config, status: 200, headers: {}, data: { id: 1 } }) })
    const pending = auth.restoreSession()
    await Promise.resolve()
    auth.logout()
    finish()
    await pending
    assert.equal(auth.isLoggedIn, false)
    assert.equal(readLoginMemory(), null)
  } finally { auth.logout(); http.defaults.adapter = original; clearLoginMemory() }
})
