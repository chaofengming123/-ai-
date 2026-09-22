# 第 22 课：SSE 流式输出，让回复逐步出现

上一课的 `stream=false` 要等模型生成完整回答才显示。本课新增 `POST /api/chat/stream`，将智谱 GLM-4.7-Flash 的真实流式数据逐段转发给 Vue。旧的非流式接口仍保留，方便对照学习。

页面增加“停止接收”和不完整回复提示；只有正常完成的问答才进入后续上下文。目前仍不读取知识库文件，也不持久保存聊天。

## 一、运行与观察

沿用第二十一课的智谱配置，先确认本地 `docker/.env` 中有 `LLM_API_KEY`。默认地址为 `https://open.bigmodel.cn/api/paas/v4/chat/completions`，模型为 `glm-4.7-flash`，`LLM_THINKING=disabled`。如果使用 IDEA 启动，环境变量也必须配置在 IDEA 中。

重启一份新版后端：

```sh
scripts/backend.sh spring-boot:run
```

前端在项目根目录启动：

```sh
npm run dev --prefix frontend
```

使用终端显示的 Local 地址，当前本机为 `http://localhost:5173/`。如果启动又无报错卡住，先参考第二十一课的云端文件下载排查。登录后进入“AI 问答”，提问“用四个步骤解释一次修改知识库请求的执行过程”，观察回复逐步出现。

模型接口参考 [智谱官方对话补全说明](https://docs.bigmodel.cn/api-reference/模型-api/对话补全)：`stream=true` 返回 SSE 数据，并以 `data: [DONE]` 标记流结束。测试中的分段内容由本地测试服务器产生；没有配置 Key 时，产品不会用这些测试文本代替真实模型回复。

## 二、SSE 是什么

SSE（Server-Sent Events）是服务器在一条 HTTP 响应中持续发送文本事件的格式。连接不需要每收到一小段文字就重建。

```text
Vue 用一个 POST 请求提交问题
  → 后端向智谱请求 stream=true
  ← 智谱发来 delta.content 的第一段
  ← 后端马上转成自己的 delta 事件并刷新输出
  ← Vue 把这一段追加进气泡
  ← 继续接收后续片段
  ← 智谱发来结束标记，后端发送 done
```

这里没有把完整答案拿到后再用定时器逐字播放。模型真正还在生成后续内容时，前面的内容就已经传给浏览器。每段可能是一个字、多个字或一段文本，不保证每个 token 恰好对应一个事件。

### 本项目的三种事件

```text
event: delta
data: {"content":"Controller 接收请求。"}

event: delta
data: {"content":"Service 处理业务。"}

event: done
data: {"model":"glm-4.7-flash","truncated":false}

```

每个事件以空行结束。`event` 指定名字，`data` 是我们约定的 JSON。正常结束使用 `done`；途中失败使用 `error`，数据中包含可显示的错误消息。

为什么不直接把智谱的完整事件透传？前端只需要文字、完成状态和错误提示。后端负责理解服务商的响应格式，并统一成自己的接口，使 Vue 不必依赖上游的完整字段结构，也不会得到上游内部错误正文。

## 三、Controller 如何边处理边返回

关键文件：`backend/src/main/java/com/example/aiknowledge/controller/ChatController.java`。

本课方法接收 `HttpServletResponse`，先设置：

```java
response.setContentType("text/event-stream");
response.setCharacterEncoding("UTF-8");
response.setHeader("Cache-Control", "no-store");
response.setHeader("X-Accel-Buffering", "no");
```

Content-Type 告诉客户端这是事件流；UTF-8 支持中文；no-store 禁止 HTTP 缓存保存这份响应。`X-Accel-Buffering: no` 是给支持它的代理的提示，部署时仍需检查代理是否缓冲、是否允许足够的读取时间。

发送一段的核心代码是：

```java
response.getOutputStream().write(
    ("event: " + name + "\ndata: " + json.writeValueAsString(data) + "\n\n")
        .getBytes(StandardCharsets.UTF_8));
response.flushBuffer();
```

`write()` 写入事件文本，`flushBuffer()` 让容器尽快发送已写内容。如果只写缓冲而不刷新，浏览器可能长时间看不到新增文字。字符串中的换行由 JSON 序列化器转义，所以回答中的普通换行不会意外结束一条 SSE 事件。

这一课不再由 Controller 最后返回一个完整 Reply 让 Spring 一次序列化。它在每次回调时序列化一个小事件，写到同一个响应中。

### 为什么出错时有两种返回方式

在第一段正文发出前，服务器仍可以返回 JSON 400、401、403、502 等状态码。第一段已经发送后，HTTP 响应头就提交了，不能再把 200 改成 502，也不能重新发送一份普通 JSON 响应。

因此代码检查 `response.isCommitted()`：尚未提交则清理预设响应并交给统一异常处理；已经提交则发送 `event: error`。前端不能只看 HTTP 200 就认定回答完成，必须等到 `done`。

## 四、Service 仍然负责业务规则

关键文件：`backend/src/main/java/com/example/aiknowledge/service/ChatService.java`。

旧 `send()` 与新 `stream()` 共用 `execute()`：

```java
return execute(userId, messages, prompt -> model.stream(prompt, delta));
```

`execute()` 先检查角色顺序、问题长度和上下文总长度，取得并发名额，再拼接 system 指令。传入的函数决定最终调用完整回答还是流式回答。

两种请求共用同一组并发名额：同一用户只能进行一个模型请求，整个进程最多四个。不能通过同时请求两个不同端点绕过限制。无论正常结束、上游失败还是写出失败，`finally` 都释放名额。

权限配置也给 `POST /api/chat/stream` 加上 `chat:send` 检查。JWT 仍只在浏览器和本项目后端之间使用，后端访问智谱时使用自己的 API Key。

## 五、LlmClient 与流解析器怎样工作

关键文件：`LlmClient.java` 与新增的 `ModelStreamReader.java`，均位于后端 service 目录。

`request(messages, true)` 沿用已有地址、Key、模型、输出上限和 thinking 参数，只把 `stream` 改为 true。连接返回后，`BodyHandlers.ofInputStream()` 提供可继续读取的响应体，不需要先收集完整字节数组。

客户端会检查上游 HTTP 状态和 Content-Type。上游 401 仍转换成应用 502，避免把服务商密钥错误误认为用户登录过期。

`ModelStreamReader.read()` 的主要步骤是：

1. 通过 UTF-8 字符读取器解码字节，用 `readLine()` 读取行。
2. 收集以 `data:` 开头的内容，遇到空行才解析完整事件。
3. 从 `choices[0].delta.content` 读取这一段文字，而不是上一课的 `message.content`。
4. 检查长度后调用 `delta.accept(text)`，Controller 的回调立即发送该片段。
5. 收到合法的 finish_reason 和 `[DONE]` 后才返回最终完成信息。

只有角色、空内容或可选用量的帧，不一定有可显示文字。思考过程字段不会作为回答转发。工具调用等不支持的结束方式会视为错误。本课保持 thinking 关闭。

解析器保留累计文本，用来检查最多 6000 字符和是否为空；输入流最多读取 256 KiB，限制也覆盖没有换行的异常长帧。网络连接直接结束却没有完整结束协议，会报错，不能把“连接关闭”当作“回答成功”。

### 为什么需要单独的截止计时器

拿到 HTTP 响应头，只代表上游开始响应。它仍可能发一段后一直不再发送。

本课用一个守护线程调度截止任务，从发起请求开始最多等待 45 秒；到期主动关闭响应流，解除阻塞的读取。`try-with-resources` 负责关闭输入流，`finally` 取消不再需要的计时任务。应用关闭时也会停止调度器。

本课为方便理解，采用同步 Servlet 请求线程读取并转发，最多四个模型请求并行。这不是面向大量长连接的异步架构。后续若扩展并发，需要再设计异步线程、背压和代理超时，不能只扩大线程数量。

## 六、为什么前端改用 fetch

关键文件：`frontend/src/api/chatStream.js`。

原生 `EventSource` 适合 GET 事件订阅，但本项目需要 POST JSON 问题，并在请求头附带 Bearer JWT。本课使用 `fetch()` 发 POST，然后通过 `response.body.getReader()` 一段一段读取响应。

```js
const response = await fetch('/api/chat/stream', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
    Authorization: `Bearer ${auth.accessToken}`,
  },
  body: JSON.stringify({ messages }),
  signal,
})
```

实际实现还加入了 55 秒客户端截止时间。请求地址固定为本地 `/api/chat/stream`，浏览器没有智谱 API Key。

fetch 不会自动执行 Axios 的拦截器，因此这个模块显式处理初始响应的 401/403。401 退出当前会话，403 刷新权限；处理前比较 `sessionVersion`，防止旧请求使新登录的账号退出。HTTP 错误和 SSE error 都不自动重试。

### 一个网络块不是一个事件

`reader.read()` 得到的字节块可能只包含半条 JSON，也可能包含几条事件。一个汉字的 UTF-8 字节也可能被拆开。

```js
buffer += decoder.decode(chunk.value, { stream: true })
```

`TextDecoder` 的流模式保留尚未凑齐的字符字节，避免中文乱码。接着从 buffer 中取出完整行；未完成的行留到下一次读取。看到空行才解析当前事件中的 JSON，兼容 LF 和 CRLF 换行。

收到 delta 时调用页面回调；收到 done 才完成 Promise；收到 error 或提前 EOF 则抛错。无论哪条路径结束，都取消读取并释放 reader 锁。

## 七、页面显示状态与模型上下文分开

关键文件：`frontend/src/utils/streamConversation.js`。

发送后立即创建 user 气泡和空的 assistant 气泡。每到一段，执行 `answer.content += text`。Vue 的响应式对象触发页面更新，`nextTick()` 等页面更新后把对话区滚动到新内容。

本课把“展示的 messages”与“发送给模型的 history”分开：

| 情况 | 页面显示 | 后续上下文 |
| --- | --- | --- |
| 正常收到 done | 完整回答 | 加入本轮问答 |
| 上游断流或超时 | 保留已收到文字，并标记未完成 | 不加入本轮 |
| 用户停止接收 | 保留已收到文字，并标记未完成 | 不加入本轮 |
| 达到输出上限 | 标记未完成，提示缩小问题 | 不加入本轮 |

这样用户可以看到发生了什么，同时不会把残缺回答当成模型已经完整说过的话。失败或截断后保留草稿，便于手动调整后发送。

`generation` 隔离不同对话，`sessionVersion` 隔离不同登录会话。重置或离开页面时取消当前请求；迟到的 delta 和 done 都先检查是否仍属于当前会话，防止旧文字混入新对话。

## 八、“停止接收”具体停止了什么

按钮调用 `AbortController.abort()`，让浏览器停止读取当前请求。后端下一次写出发现连接失效时，会结束读取并关闭上游流；如果上游此时没有任何输出，最晚由整体截止时间结束读取。

因此按钮叫“停止接收”。它不承诺服务商立刻停止内部生成，也不承诺撤销已经产生的用量。不要因为点了停止就立即自动重发；本课没有自动重试或自动重连。

## 九、验证方式

本课自动验证包括：

- 用本地模拟上游先发第一段、暂停最后一段，确认客户端已经收到第一段，再允许上游结束。这能区分真流式与整段缓存后返回。
- 初始鉴权失败、非法消息和上游拒绝访问仍返回正确 JSON 状态。
- 第一段后断流或超时返回 error，不能返回 done；结束后并发名额可再次使用。
- 将中文和 emoji 拆成逐字节输入，验证解析后文本保持完整。
- 失败轮次不进入下一轮历史，旧对话迟到片段不污染新对话。

真实服务商还需要在本地配置智谱 Key 后观察一次流式回答。用浏览器 Network 查看 `/api/chat/stream`，可以看到连续 delta 和最后的 done；也可以生成中途点击“停止接收”，确认气泡标记和草稿保留。

## 十、理解检查与下一步

1. `flushBuffer()` 的作用是什么？只调用 write 可能发生什么？
2. 为什么读到一个网络块后不能直接 JSON.parse？
3. 为什么 HTTP 200 不等于流式回答成功？
4. 为什么“停止接收”和“模型已停止生成”不能等同？
5. 为什么本课把页面记录和有效上下文分开？

下一课按学习手册进入 RAG 文档处理：先从已上传文件提取正文，理解 Parser 与 Text，再逐步学习分块、Embedding 和检索。
