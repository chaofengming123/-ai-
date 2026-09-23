// POST + Bearer JWT：使用 fetch 读取 SSE，不能直接用只支持 GET 的 EventSource。
export async function readChatEvents(body, onDelta) {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let buffer = '', event = '', data = [], bytes = 0, characters = 0
  let completed = null
  function line(value) {
    if (value.endsWith('\r')) value = value.slice(0, -1)
    if (!value) {
      if (data.length) {
        const payload = JSON.parse(data.join('\n'))
        if (event === 'delta') {
          if (typeof payload.content !== 'string' || (characters += payload.content.length) > 6000) throw new Error('模型回复格式不正确或内容过长。')
          onDelta(payload.content)
        } else if (event === 'done') {
          if (characters === 0 || typeof payload.truncated !== 'boolean') throw new Error('模型回复不完整。')
          completed = payload
        } else if (event === 'error') {
          throw new Error(payload.message || '模型回复中断。')
        }
      }
      event = ''; data = []
    } else if (value.startsWith('event:')) event = value.slice(6).trim()
    else if (value.startsWith('data:')) data.push(value.slice(5).replace(/^ /, ''))
  }
  try {
    while (!completed) {
      const chunk = await reader.read()
      if (chunk.done) break
      bytes += chunk.value.byteLength
      if (bytes > 512 * 1024) throw new Error('流式响应过大。')
      buffer += decoder.decode(chunk.value, { stream: true })
      let end
      while (!completed && (end = buffer.indexOf('\n')) >= 0) {
        line(buffer.slice(0, end)); buffer = buffer.slice(end + 1)
      }
    }
    if (!completed) throw new Error('连接已中断，回复未完成，请手动重试。')
    return completed
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
}

export async function sendChatStream(messages, signal, onDelta, auth, fetcher = fetch, options = null) {
  const version = auth.sessionVersion
  const controller = new AbortController()
  const abort = () => controller.abort()
  signal.addEventListener('abort', abort, { once: true })
  if (signal.aborted) abort()
  const timer = setTimeout(abort, options ? 300000 : 55000)
  try {
    const response = await fetcher(options ? '/api/chat/assistant' : '/api/chat/stream', {
      method: 'POST', signal: controller.signal,
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', Authorization: `Bearer ${auth.accessToken}` },
      body: JSON.stringify({ messages, ...options }),
    })
    if (!response.ok) {
      if (version === auth.sessionVersion && auth.isLoggedIn) {
        if (response.status === 401) auth.logout('登录已失效，请重新登录。')
        if (response.status === 403) void auth.verifySession?.()
      }
      const payload = await response.json().catch(() => ({}))
      throw new Error(payload.message || '无法开始模型对话，请检查后端。')
    }
    if (!response.headers.get('content-type')?.startsWith('text/event-stream') || !response.body) throw new Error('后端没有返回流式数据，请重启新版后端。')
    return await readChatEvents(response.body, onDelta)
  } finally {
    clearTimeout(timer); signal.removeEventListener('abort', abort); controller.abort()
  }
}
