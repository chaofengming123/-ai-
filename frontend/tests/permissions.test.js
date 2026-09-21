import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { canManageKnowledgeBases } from '../src/utils/permissions.js'
import { useAuthStore } from '../src/stores/auth.js'
import { http } from '../src/api/http.js'

test('management defaults to denied unless role is exactly ADMIN', () => {
  for (const user of [null, {}, { role: 'USER' }, { role: 'admin' }, { role: 'UNKNOWN' }]) {
    assert.equal(canManageKnowledgeBases(user), false)
  }
  assert.equal(canManageKnowledgeBases({ role: 'ADMIN' }), true)
})

test('identity refresh updates role permissions without a new login', async () => {
  const original = http.defaults.adapter
  setActivePinia(createPinia())
  const auth = useAuthStore()
  const respond = (config, data) => ({ config, data, status: 200, headers: {} })
  try {
    http.defaults.adapter = async config => respond(config, {
      accessToken: 'test-only', expiresIn: 900, user: { id: 1, username: 'learner', role: 'ADMIN' },
    })
    await auth.login('learner', 'test-only-password')
    assert.equal(auth.canManageKnowledgeBases, true)
    assert.equal(auth.roleLabel, '管理员')
    http.defaults.adapter = async config => respond(config, { id: 1, username: 'learner', role: 'USER' })
    await auth.verifySession()
    assert.equal(auth.isLoggedIn, true)
    assert.equal(auth.canManageKnowledgeBases, false)
    assert.equal(auth.roleLabel, '普通用户')
    auth.logout()
    assert.equal(auth.canManageKnowledgeBases, false)
  } finally {
    auth.logout()
    http.defaults.adapter = original
  }
})
