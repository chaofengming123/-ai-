import test from 'node:test'
import assert from 'node:assert/strict'
import { canVisit } from '../src/utils/permissions.js'
import { validateDocument } from '../src/utils/documents.js'
import { createStreamConversation } from '../src/utils/streamConversation.js'

test('navigation uses explicit permissions and keeps indexing tools out of reader pages', () => {
  const reader = { roles: ['USER'], permissions: ['chat:send', 'document:read', 'knowledge-base:read'] }
  assert.equal(canVisit(reader, '/chat'), true)
  assert.equal(canVisit(reader, '/documents'), true)
  assert.equal(canVisit(reader, '/embeddings'), false)
  assert.equal(canVisit({ roles: ['ADMIN'], permissions: [] }, '/documents'), false)
  assert.equal(canVisit({ permissions: ['document:read'] }, '/documents'), false)
})
test('new format extensions match the upload contract', () => {
  for (const extension of ['docx', 'CSV', 'tsv', 'json', 'html', 'htm', 'rtf'])
    assert.equal(validateDocument({ name: `test.${extension}`, size: 100 }), '')
  assert.ok(validateDocument({ name: 'test.exe', size: 100 }))
})
test('source metadata is displayed but knowledge answers are not recycled as general history', async () => {
  let count = 0
  const conversation = createStreamConversation(async (messages, signal, delta) => {
    count++
    if (count === 2) assert.deepEqual(messages, [{ role: 'user', content: '另一个问题' }])
    delta('审批回答')
    return { truncated: false, mode: 'knowledge', notice: '依据资料', sources: [{ sourceId: 1, fileName: '流程.docx' }] }
  }, () => 0)
  conversation.draft.value = '审批流程？'
  await conversation.send()
  assert.equal(conversation.messages.value[1].sources[0].fileName, '流程.docx')
  conversation.draft.value = '另一个问题'
  await conversation.send()
})
