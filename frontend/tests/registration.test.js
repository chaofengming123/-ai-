import test from 'node:test'
import assert from 'node:assert/strict'
import { validateRegistration } from '../src/utils/registration.js'
import { registerUser } from '../src/api/auth.js'
import { http } from '../src/api/http.js'

test('registration validates username, Unicode byte limits and exact confirmation', () => {
  const password = 'test-only-password'
  assert.equal(validateRegistration(' Learner_13 ', password, password), '')
  assert.ok(validateRegistration('中文', password, password))
  assert.ok(validateRegistration('abc', 'a'.repeat(11), 'a'.repeat(11)))
  assert.ok(validateRegistration('abc', ' '.repeat(12), ' '.repeat(12)))
  assert.equal(validateRegistration('abc', '中'.repeat(24), '中'.repeat(24)), '')
  assert.ok(validateRegistration('abc', '中'.repeat(25), '中'.repeat(25)))
  assert.ok(validateRegistration('abc', password, password + ' '))
  assert.equal(validateRegistration('abc', ' ' + password + ' ', ' ' + password + ' '), '')
})

test('registration sends credentials in request body and propagates failure without retry', async () => {
  const original = http.defaults.adapter
  try {
    let calls = 0
    http.defaults.adapter = async config => {
      calls++
      assert.equal(config.method, 'post')
      assert.equal(config.url, '/auth/register')
      assert.deepEqual(JSON.parse(config.data), { username: 'learner', password: 'test-only-password' })
      return { config, data: { id: 1, username: 'learner' }, status: 201, headers: {} }
    }
    assert.deepEqual(await registerUser('learner', 'test-only-password'), { id: 1, username: 'learner' })
    http.defaults.adapter = async () => { calls++; throw new Error('offline') }
    await assert.rejects(registerUser('learner', 'test-only-password'), /offline/)
    assert.equal(calls, 2)
  } finally {
    http.defaults.adapter = original
  }
})
