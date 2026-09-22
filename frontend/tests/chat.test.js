import test from 'node:test'
import assert from 'node:assert/strict'
import { buildChatMessages, createChatConversation } from '../src/utils/chat.js'

test('context preserves whole recent pairs within message and character budgets', () => {
  const history = Array.from({ length: 8 }, (_, i) => [
    { role: 'user', content: `question ${i}` }, { role: 'assistant', content: `answer ${i}` },
  ]).flat()
  const request = buildChatMessages(history, 'next')
  assert.equal(request.length, 11)
  assert.equal(request[0].content, 'question 3')
  assert.deepEqual(request.at(-1), { role: 'user', content: 'next' })
  const big = [{ role: 'user', content: 'a'.repeat(2000) }, { role: 'assistant', content: 'b'.repeat(6000) }]
  assert.equal(buildChatMessages([...big, ...big], 'next').length, 3)
})

test('pending request blocks duplicate sends and a failure preserves the draft for manual retry', async () => {
  let fail, calls = 0
  const chat = createChatConversation(() => { calls++; return new Promise((_, reject) => { fail = reject }) }, () => 1)
  chat.draft.value = '解释 Service'
  const pending = chat.send()
  await chat.send()
  assert.equal(calls, 1)
  fail({ response: { data: { message: '额度不足' } } })
  await pending
  assert.equal(chat.draft.value, '解释 Service')
  assert.equal(chat.messages.value.length, 0)
  assert.equal(chat.error.value, '额度不足')
  assert.equal(chat.isBusy.value, false)
})

test('reset aborts old requests and late responses cannot enter a new conversation', async () => {
  const requests = []
  const chat = createChatConversation((messages, signal) => new Promise(resolve => requests.push({ resolve, signal })), () => 1)
  chat.draft.value = 'old'
  const old = chat.send()
  chat.reset()
  assert.equal(requests[0].signal.aborted, true)
  chat.draft.value = 'new'
  const next = chat.send()
  requests[0].resolve({ content: 'old answer' })
  await old
  assert.equal(chat.isBusy.value, true)
  assert.equal(chat.messages.value.length, 1)
  requests[1].resolve({ content: '<script>plain text</script>', truncated: true })
  await next
  assert.equal(chat.messages.value[1].content, '<script>plain text</script>')
  assert.ok(chat.notice.value.includes('上限'))
})

test('a response belonging to a previous login session is discarded', async () => {
  let version = 1, finish
  const chat = createChatConversation(() => new Promise(resolve => { finish = resolve }), () => version)
  chat.draft.value = 'question'
  const pending = chat.send()
  version++
  finish({ content: 'private old response' })
  await pending
  assert.equal(chat.messages.value.some(message => message.role === 'assistant'), false)
})
