import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { http } from '../src/api/http.js'
import { useAuthStore } from '../src/stores/auth.js'

test('login, bearer identity check, offline recovery, 401 and logout', async () => {
  const original = http.defaults.adapter
  setActivePinia(createPinia())
  const auth = useAuthStore()
  const respond = (config, data) => ({ config, data, status: 200, headers: {} })
  try {
    http.defaults.adapter = async config => {
      assert.equal(config.url, '/auth/login')
      assert.deepEqual(JSON.parse(config.data), { username: 'learner', password: 'test-only-password' })
      return respond(config, { accessToken: 'test-token', expiresIn: 900, user: { id: 1, username: 'learner' } })
    }
    assert.equal((await auth.login('learner', 'test-only-password')).success, true)
    assert.equal(auth.isLoggedIn, true)
    http.defaults.adapter = async config => {
      assert.equal(config.url, '/auth/me')
      assert.equal(config.headers.get('Authorization'), 'Bearer test-token')
      return respond(config, { id: 1, username: 'learner' })
    }
    await auth.verifySession()
    assert.match(auth.notice, /后端已确认/)
    http.defaults.adapter = async () => { throw new Error('offline') }
    await auth.verifySession()
    assert.equal(auth.isLoggedIn, true)
    http.defaults.adapter = async () => { throw { response: { status: 401 } } }
    await auth.verifySession()
    assert.equal(auth.isLoggedIn, false)
    assert.equal(auth.user, null)
    assert.match((await auth.login('learner', 'wrong')).error, /用户名或密码/)
    assert.equal(auth.isBusy, false)
  } finally {
    auth.logout()
    http.defaults.adapter = original
  }
})

test('logout discards late login response and pending login blocks duplicates', async () => {
  const original = http.defaults.adapter
  setActivePinia(createPinia())
  const auth = useAuthStore()
  try {
    let finish
    let calls = 0
    http.defaults.adapter = config => {
      calls++
      return new Promise(resolve => { finish = () => resolve({
        config, data: { accessToken: 'stale', expiresIn: 900, user: { id: 1, username: 'old' } },
        status: 200, headers: {},
      }) })
    }
    const pending = auth.login('learner', 'password')
    assert.ok((await auth.login('learner', 'password')).error)
    assert.equal(calls, 1)
    auth.logout()
    finish()
    await pending
    assert.equal(auth.isLoggedIn, false)
    assert.equal(auth.user, null)
  } finally {
    auth.logout()
    http.defaults.adapter = original
  }
})
