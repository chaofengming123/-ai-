import test from 'node:test'
import assert from 'node:assert/strict'
import { validateDocument, MAX_DOCUMENT_BYTES } from '../src/utils/documents.js'
import { uploadDocument, fetchDocuments, downloadDocument } from '../src/api/documents.js'
import { http } from '../src/api/http.js'

test('upload validation handles missing, empty, wrong-extension and oversized files', () => {
  assert.equal(MAX_DOCUMENT_BYTES, 5 * 1024 * 1024)
  assert.ok(validateDocument(null))
  assert.ok(validateDocument({ name: 'a.txt', size: 0 }))
  assert.ok(validateDocument({ name: 'a.docm', size: 2 }))
  assert.ok(validateDocument({ name: 'a.doc', size: 2 }))
  assert.equal(validateDocument({ name: 'a.PDF', size: 2 }), '')
  assert.equal(validateDocument({ name: 'a.docx', size: 2 }), '')
  assert.ok(validateDocument({ name: 'a.md', size: MAX_DOCUMENT_BYTES + 1 }))
  assert.equal(validateDocument({ name: 'a.MD', size: MAX_DOCUMENT_BYTES }), '')
})

test('document API sends the selected base and file as multipart data and never retries a failed upload', async () => {
  const original = http.defaults.adapter
  let calls = 0
  const file = new File(['课程笔记'], 'lesson.md', { type: 'text/markdown' })
  try {
    http.defaults.adapter = async config => {
      calls++
      assert.equal(config.url, '/documents')
      assert.equal(config.data.get('knowledgeBaseId'), '7')
      assert.equal(config.data.get('file').name, 'lesson.md')
      assert.equal(await config.data.get('file').text(), '课程笔记')
      throw new Error('connection lost after sending')
    }
    await assert.rejects(uploadDocument(7, file))
    assert.equal(calls, 1)
    http.defaults.adapter = async config => {
      assert.equal(config.params.knowledgeBaseId, 9)
      return { config, data: [], status: 200, headers: {} }
    }
    assert.deepEqual(await fetchDocuments(9), [])
    const bytes = new Blob(['原文件'])
    http.defaults.adapter = async config => {
      assert.equal(config.url, '/documents/12/download')
      assert.equal(config.responseType, 'blob')
      return { config, data: bytes, status: 200, headers: {} }
    }
    assert.equal(await (await downloadDocument(12)).text(), '原文件')
  } finally { http.defaults.adapter = original }
})
