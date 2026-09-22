import test from 'node:test'
import assert from 'node:assert/strict'
import { createEmbeddingExperiment } from '../src/utils/embeddingExperiment.js'
test('experiment validates text, blocks duplicates, and ignores old-session results', async () => {
  let calls = 0, finish, version = 1
  const state = createEmbeddingExperiment(() => { calls++; return new Promise(resolve => { finish = resolve }) }, () => version)
  await state.run('', ['text']); assert.equal(calls, 0)
  const first = state.run('query', ['text']); await state.run('query', ['text']); assert.equal(calls, 1)
  version++; finish({ dimensions: 3 }); await first; assert.equal(state.result.value, null)
  const second = state.run('query', ['text']); state.reset(); finish({ dimensions: 3 }); await second
  assert.equal(state.result.value, null); assert.equal(state.busy.value, false)
})
test('failed experiment clears stale results and displays the server error', async () => {
  const state = createEmbeddingExperiment(async () => { throw { response: { data: { message: '模型未下载' } } } }, () => 1)
  state.result.value = { dimensions: 123 }
  await state.run('query', ['text'])
  assert.equal(state.result.value, null); assert.equal(state.error.value, '模型未下载'); assert.equal(state.busy.value, false)
})
