import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { hasPermission, canManageKnowledgeBases } from '../src/utils/permissions.js'
import { useAuthStore } from '../src/stores/auth.js'
import { http } from '../src/api/http.js'

test('permissions are explicit; role names alone cannot grant access', () => {
  for (const user of [null, {}, { role: 'USER' }, { role: 'admin' }, { role: 'UNKNOWN' }]) {
    assert.equal(canManageKnowledgeBases(user), false)
  }
  assert.equal(canManageKnowledgeBases({ roles: ['ADMIN'] }), false)
  const editor = { roles: ['EDITOR'], permissions: ['knowledge-base:read', 'knowledge-base:create', 'knowledge-base:update'] }
  assert.equal(canManageKnowledgeBases(editor), true)
  assert.equal(hasPermission(editor, 'update'), true)
  assert.equal(hasPermission(editor, 'delete'), false)
  assert.equal(hasPermission({ permissions: 'knowledge-base:delete' }, 'delete'), false)
})

test('identity refresh updates role permissions without a new login', async () => {
  const original = http.defaults.adapter
  setActivePinia(createPinia())
  const auth = useAuthStore()
  const respond = (config, data) => ({ config, data, status: 200, headers: {} })
  try {
    http.defaults.adapter = async config => respond(config, {
      accessToken: 'test-only', expiresIn: 900, user: { id: 1, username: 'learner', roles: ['ADMIN'], permissions: ['knowledge-base:create', 'knowledge-base:delete'] },
    })
    await auth.login('learner', 'test-only-password')
    assert.equal(auth.canManageKnowledgeBases, true)
    assert.equal(auth.roleLabel, '管理员')
    http.defaults.adapter = async config => respond(config, { id: 1, username: 'learner', roles: ['USER'], permissions: ['knowledge-base:read'] })
    const version = auth.sessionVersion
    await auth.verifySession()
    assert.ok(auth.sessionVersion > version)
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
