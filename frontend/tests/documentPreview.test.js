import test from 'node:test'
import assert from 'node:assert/strict'
import { createDocumentPreview } from '../src/utils/documentPreview.js'

test('switching documents aborts and ignores the previous preview', async () => {
  const requests = []
  const state = createDocumentPreview((id, signal) => new Promise(resolve => requests.push({ resolve, signal })), () => 1)
  const first = state.showPreview({ id: 1 })
  const second = state.showPreview({ id: 2 })
  assert.equal(requests[0].signal.aborted, true)
  requests[0].resolve({ content: 'old' }); await first
  assert.equal(state.preview.value, null); assert.equal(state.previewingId.value, 2)
  requests[1].resolve({ content: 'new' }); await second
  assert.equal(state.preview.value.content, 'new')
  state.closePreview(); assert.equal(state.preview.value, null)
})
test('session changes discard text and failed extraction displays the server message', async () => {
  let version = 1, finish
  const state = createDocumentPreview(() => new Promise(resolve => { finish = resolve }), () => version)
  const pending = state.showPreview({ id: 1 }); version++
  finish({ content: 'old private content' }); await pending
  assert.equal(state.preview.value, null)
  const failed = createDocumentPreview(async () => { throw { response: { data: { message: '文档不存在。' } } } }, () => 1)
  await failed.showPreview({ id: 2 })
  assert.equal(failed.previewError.value, '文档不存在。')
  assert.equal(failed.previewingId.value, null)
})
