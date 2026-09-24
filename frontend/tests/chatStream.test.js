import test from 'node:test'
import assert from 'node:assert/strict'
import { readChatEvents, sendChatStream } from '../src/api/chatStream.js'
import { createStreamConversation } from '../src/utils/streamConversation.js'

const event = (name, value) => `event: ${name}\r\ndata: ${JSON.stringify(value)}\r\n\r\n`
test('assistant accepts JSON failures alongside SSE and preserves upstream error without retry', async () => {
  let calls = 0
  const auth = { sessionVersion: 1, accessToken: 'test', isLoggedIn: true }
  await assert.rejects(sendChatStream([], new AbortController().signal, () => {}, auth, async (url, options) => {
    calls++
    assert.equal(url, '/api/chat/assistant')
    assert.equal(options.headers.Accept, 'text/event-stream, application/json')
    return new Response(JSON.stringify({ message: '模型服务暂时限流或额度不足' }), { status: 503 })
  }, { mode: 'general' }), /限流或额度不足/)
  assert.equal(calls, 1)
  assert.equal(auth.isLoggedIn, true)
})
function body(text) {
  const bytes = new TextEncoder().encode(text)
  return new ReadableStream({ start(controller) {
    for (const byte of bytes) controller.enqueue(Uint8Array.of(byte))
    controller.close()
  } })
}
test('SSE survives split UTF-8 characters and CRLF frames', async () => {
  const chunks = []
  const result = await readChatEvents(body(event('delta', { content: '中文🙂\n' }) + event('delta', { content: '第二段' }) + event('done', { truncated: false, model: 'glm' })), text => chunks.push(text))
  assert.deepEqual(chunks, ['中文🙂\n', '第二段'])
  assert.equal(result.truncated, false)
})
test('EOF and explicit error never count as successful completion', async () => {
  for (const ending of ['', event('error', { message: '模型中断' })]) {
    const chunks = []
    await assert.rejects(readChatEvents(body(event('delta', { content: '部分' }) + ending), text => chunks.push(text)))
    assert.deepEqual(chunks, ['部分'])
  }
})
test('fetch sends JWT only to fixed local endpoint and handles current-session 401', async () => {
  const auth = { sessionVersion: 1, accessToken: 'local-jwt', isLoggedIn: true, logout() { this.isLoggedIn = false } }
  await assert.rejects(sendChatStream([], new AbortController().signal, () => {}, auth, async (url, options) => {
    assert.equal(url, '/api/chat/stream'); assert.equal(options.headers.Authorization, 'Bearer local-jwt')
    assert.deepEqual(JSON.parse(options.body), { messages: [] })
    return new Response(JSON.stringify({ message: 'expired' }), { status: 401 })
  }), /expired/)
  assert.equal(auth.isLoggedIn, false)
})
test('late 401 cannot log out a newer session', async () => {
  const auth = { sessionVersion: 1, accessToken: 'old', isLoggedIn: true, logout() { throw new Error('wrong logout') } }
  await assert.rejects(sendChatStream([], new AbortController().signal, () => {}, auth, async () => {
    auth.sessionVersion++
    return new Response('{}', { status: 401 })
  }))
  assert.equal(auth.isLoggedIn, true)
})
test('partial answer is visible immediately but excluded from the next request after failure', async () => {
  let call = 0, fail
  const chat = createStreamConversation((messages, signal, delta) => {
    call++
    if (call === 1) { delta('部分回答'); return new Promise((_, reject) => { fail = reject }) }
    assert.deepEqual(messages, [{ role: 'user', content: '新问题' }])
    delta('完整回答'); return Promise.resolve({ truncated: false })
  }, () => 1)
  chat.draft.value = '旧问题'
  const pending = chat.send()
  assert.equal(chat.messages.value[1].content, '部分回答')
  await chat.send(); assert.equal(call, 1)
  fail(new Error('断开')); await pending
  assert.equal(chat.messages.value[1].incomplete, true)
  assert.equal(chat.draft.value, '旧问题')
  chat.draft.value = '新问题'; await chat.send()
  assert.equal(chat.messages.value.at(-1).content, '完整回答')
})
test('reset aborts old stream and late deltas cannot alter the new conversation', async () => {
  const requests = []
  const chat = createStreamConversation((messages, signal, delta) => new Promise(resolve => requests.push({ signal, delta, resolve })), () => 1)
  chat.draft.value = 'old'; const old = chat.send(); chat.reset()
  assert.equal(requests[0].signal.aborted, true)
  chat.draft.value = 'new'; const current = chat.send()
  requests[0].delta('旧内容'); requests[0].resolve({ truncated: false }); await old
  assert.equal(chat.messages.value.at(-1).content, ''); assert.equal(chat.isBusy.value, true)
  requests[1].delta('新内容'); requests[1].resolve({ truncated: false }); await current
  assert.equal(chat.messages.value.at(-1).content, '新内容')
})

test('stop and output truncation keep drafts and never commit incomplete history', async () => {
  let call = 0
  const chat = createStreamConversation((messages, signal, delta) => {
    call++
    assert.equal(messages.length, 1)
    delta('部分内容')
    if (call === 1) return new Promise((_, reject) => signal.addEventListener('abort', () => reject(new Error('aborted'))))
    return Promise.resolve({ truncated: call === 2 })
  }, () => 1)
  chat.draft.value = '停止测试'
  const pending = chat.send(); chat.stop(); await pending
  assert.equal(chat.isBusy.value, false)
  assert.equal(chat.draft.value, '停止测试')
  assert.equal(chat.messages.value.at(-1).incomplete, true)
  chat.draft.value = '截断测试'; await chat.send()
  assert.equal(chat.messages.value.at(-1).incomplete, true)
  assert.equal(chat.draft.value, '截断测试')
  chat.draft.value = '完整回答'; await chat.send()
  assert.equal(chat.draft.value, '')
})
