import test from 'node:test'
import assert from 'node:assert/strict'
import { validateChunkOptions } from '../src/utils/chunking.js'
import { fetchDocumentChunks } from '../src/api/documents.js'
import { http } from '../src/api/http.js'
import { createDocumentPreview } from '../src/utils/documentPreview.js'

test('chunk options require bounded integers and overlap smaller than half a chunk', () => {
  for (const pair of [[500, 50], [200, 0], [800, 200]]) assert.equal(validateChunkOptions(...pair), '')
  for (const pair of [[199, 0], [2001, 0], [500, -1], [500, 201], [200, 100], [500.5, 50], ['', 0]])
    assert.ok(validateChunkOptions(...pair))
})
test('chunk API carries options and signal; an older calculation cannot replace a new one', async () => {
  const original = http.defaults.adapter, requests = []
  let size = 500
  http.defaults.adapter = config => new Promise(resolve => requests.push({ config, resolve }))
  try {
    const state = createDocumentPreview((id, signal) => fetchDocumentChunks(id, size, 50, signal), () => 1)
    const first = state.showPreview({ id: 7 }); await new Promise(resolve => setImmediate(resolve))
    size = 800
    const second = state.showPreview({ id: 7 }); await new Promise(resolve => setImmediate(resolve))
    assert.equal(requests[0].config.signal.aborted, true)
    assert.equal(requests[1].config.url, '/documents/7/chunks')
    assert.deepEqual(requests[1].config.params, { size: 800, overlap: 50 })
    requests[0].resolve({ config: requests[0].config, data: { chunkSize: 500 }, status: 200, headers: {} }); await first
    assert.equal(state.preview.value, null)
    requests[1].resolve({ config: requests[1].config, data: { chunkSize: 800 }, status: 200, headers: {} }); await second
    assert.equal(state.preview.value.chunkSize, 800)
  } finally { http.defaults.adapter = original }
})
