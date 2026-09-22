import test from 'node:test'
import assert from 'node:assert/strict'
import { createVectorStorage } from '../src/utils/vectorStorage.js'

test('saving and search remain separate and a failed save clears stale status without retry', async () => {
  let calls = 0
  const state = createVectorStorage({
    status: async () => ({ count: 3 }),
    search: async query => ({ query, matches: [{ text: 'stored', score: 1 }] }),
    save: async () => { calls++; throw new Error('offline') },
  }, () => 1)
  await state.run('status'); assert.equal(state.status.value.count, 3)
  await state.run('search', 'query'); assert.equal(state.result.value.query, 'query')
  await state.run('save', ['text'])
  assert.equal(calls, 1); assert.equal(state.result.value, null); assert.equal(state.status.value, null)
  assert.ok(state.error.value); assert.equal(state.busy.value, false)
})
test('pending save blocks double submission and cannot overwrite a newer session', async () => {
  let finish, version = 1, calls = 0
  const state = createVectorStorage({ save: () => { calls++; return new Promise(resolve => { finish = resolve }) } }, () => version)
  const pending = state.run('save', ['text']); await state.run('save', ['text'])
  assert.equal(calls, 1); version++; finish({ count: 3 }); await pending
  assert.equal(state.status.value, null); assert.equal(state.message.value, '')
  const next = state.run('save', ['text']); state.reset(); finish({ count: 3 }); await next
  assert.equal(state.status.value, null); assert.equal(state.busy.value, false)
})
