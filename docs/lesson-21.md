# 第 21 课：基础 LLM Chat，让后端调用模型并显示回复

本课开始 Phase 6。你会把问题发给后端，由后端请求模型服务，再把完整回复显示在 Vue 页面上。先完成非流式对话，下一课再逐步加入流式输出。

这次关键变化是：Service 除了调用 Mapper 操作数据库，也可以调用一个 HTTP 客户端访问外部服务。对话内容暂不保存到 MySQL，也不读取上几课上传的文件。知识库检索要等后面的 RAG 课程。

## 一、先看完整执行路线

```text
Vue 输入问题
  → Axios POST /api/chat，带本系统 JWT
  → Spring Security 校验身份与 chat:send 权限
  → ChatController 把请求 JSON 转成 ChatRequest / ChatMessage
  → ChatService 检查消息、限制并发、加入 system 指令
  → LlmClient 把 Java 对象序列化为模型服务要求的 JSON
  → Java HttpClient 使用服务商 API Key 请求模型
  ← 模型返回 choices[0].message.content
  ← LlmClient 提取成 Reply Java 对象
  ← Spring MVC 的 JSON 消息转换器把 Reply 写入 HTTP 响应
  ← Axios 得到 JavaScript 对象，Vue 更新页面
```

这里有两次 HTTP 通信，也有两种凭据：

| 通信 | 凭据 | 作用 |
| --- | --- | --- |
| 浏览器 → 本项目后端 | 登录获得的 JWT | 证明你是本系统的哪个用户 |
| 本项目后端 → 模型服务 | 服务商 API Key | 允许后端使用模型账户，可能产生服务商用量 |

模型 API Key 只放后端。前端既不需要知道它，也不能把本项目 JWT 当作模型 API Key。`LlmClient` 明确使用后端配置的 Key，没有转发浏览器的 Authorization。

## 二、本地配置与启动

本课按你的最新选择，使用智谱开放平台的免费模型 `glm-4.7-flash`，关闭思考模式，先学习普通文本对话。[智谱官方公告](https://www.zhipuai.cn/zh/news/148)说明该模型供免费调用。免费模型仍需要智谱 API 账户和可用的 API Key，调用限制以平台账户为准；仅启动 MySQL 或 MinIO 不会得到模型能力。接口格式见 [智谱对话补全文档](https://docs.bigmodel.cn/api-reference/模型-api/对话补全)。

打开被 Git 忽略的 `docker/.env`，在原有内容后追加以下配置，并把空值填好。参考文件为 `docker/.env.llm.example`，不要在这个会提交的示例文件中写真实密钥。

```sh
LLM_ENDPOINT='https://open.bigmodel.cn/api/paas/v4/chat/completions'
LLM_API_KEY=''
LLM_MODEL='glm-4.7-flash'
LLM_TOKEN_PARAMETER='max_tokens'
LLM_THINKING='disabled'
```

- `LLM_ENDPOINT`：完整模型接口 URL，需要包括 `/chat/completions`，不能只写网站首页。远程服务要求 HTTPS，本机测试允许 localhost 的 HTTP。
- `LLM_API_KEY`：填智谱开放平台的密钥本身，不加 `Bearer `；Java 代码负责加前缀。其他服务商的密钥不能混用。
- `LLM_MODEL`：服务商提供的模型标识，不是自己起的显示名称。
- `LLM_TOKEN_PARAMETER`：本客户端允许 `max_tokens` 或 `max_completion_tokens`，按所选服务商、模型文档配置。
- `LLM_THINKING`：本课为 `disabled`，发送 `thinking: {type: "disabled"}`，关闭模型思考模式。不要把它和控制流式输出的 `stream` 混淆。

后端已将上述地址、模型和参数作为默认值。如果本地没有旧模型配置，只需追加 `LLM_API_KEY='你的智谱密钥'`。如果此前填过 DeepSeek 配置，必须将旧 `LLM_ENDPOINT`、`LLM_MODEL`、`LLM_API_KEY` 替换为智谱对应值，不要保留重复配置；环境变量会覆盖代码默认值。IDEA 运行配置中的旧值也要同步替换。目前没有填入密钥或验证真实账户。若将来换服务商，需要同时核对地址、模型和参数；不支持 thinking 参数的接口应将 `LLM_THINKING` 设为空字符串。

切换模型时，`application.properties` 的默认值和环境变量决定 `LlmClient` 构造器拿到的 `endpoint`、`model`、`key`：endpoint 决定 HTTP 请求发往哪里，model 写进请求 JSON，key 写进 Authorization 请求头。智谱使用当前客户端支持的消息与返回格式，因此这次无需改 Controller、Service 或 Vue 的对话流程。

在项目根目录运行：

```sh
scripts/backend.sh spring-boot:run
```

这个脚本会加载 `docker/.env`，然后进入 backend 启动。直接在 IDEA 点击 Java 主类运行，不会自动读取这个文件；需要在运行配置的环境变量中加入相同的 `LLM_...` 配置，并保留原来的数据库、JWT、MinIO 环境变量。

只启动一份后端。如果 8080 已被你之前的 IDEA 后端占用，先在 IDEA 停止原进程，再启动新版。配置改动后必须重启后端；页面的“检查配置”只重新查询后端，不会读取磁盘上的 `.env`。

启动时 Flyway V7 会添加 `chat:send` 权限，并授权给 USER、EDITOR、ADMIN。重新登录，进入侧栏“AI 问答”。没有配置时，页面会提示“模型尚未配置”，不会提供模拟答案。显示模型名只表示本地配置满足基本格式，真实 Key、额度和接口兼容性需要发送请求才能验证。

不要把密钥写入 `frontend/.env` 的 `VITE_` 变量、源码、讲义或 Git；Vite 的前端变量可以进入浏览器构建产物。

## 三、消息角色：为什么发送的是数组

单轮请求的 JSON 是：

```json
{"messages":[{"role":"user","content":"请解释 Controller 和 Service 的区别。"}]}
```

追问“再举一个例子”需要上下文，所以前端会带上最近的完整对话：

```json
{"messages":[
  {"role":"user","content":"请解释 Controller 和 Service 的区别。"},
  {"role":"assistant","content":"Controller 接收请求，Service 处理业务。"},
  {"role":"user","content":"再举一个例子。"}
]}
```

| 角色 | 本课由谁生成 | 作用 |
| --- | --- | --- |
| system | 后端 | 规定学习助手的回答方式，说明目前无法检索文件 |
| user | 用户 | 提出问题或补充条件 |
| assistant | 模型回复，由前端保存再发送 | 给下一轮提供此前回答的上下文 |

模型不会因为我们登录了，就自动记住上一轮。当前客户端通过每次重新发送历史来提供上下文。前端历史属于可修改的输入，后端会检查角色和长度，但不会把 assistant 内容当成可信业务事实。system 指令也不能代替程序权限检查。

## 四、Controller 与 Service：关键代码怎样工作

`backend/src/main/java/com/example/aiknowledge/controller/ChatController.java`：

```java
public record ChatRequest(List<ChatMessage> messages) {}

@PostMapping
public ResponseEntity<LlmClient.Reply> send(
        @RequestBody ChatRequest request,
        @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(chat.send(Long.parseLong(jwt.getSubject()), request.messages()));
}
```

`@RequestBody` 让 Spring MVC 将 JSON 反序列化为 Java 对象。`@AuthenticationPrincipal` 取得已经通过验证的 JWT，用户 ID 来自 token 的 subject；请求体不能自己指定另一个用户 ID。`noStore()` 告诉 HTTP 缓存不要保存这份响应。

`ChatService.send()` 按顺序完成：

1. 检查消息不能为空、最多 11 条，按 user / assistant 交替，最后必须是 user。
2. 检查单个问题最多 2000 字符、历史回复最多 6000 字符，整次历史与新问题合计最多 12000 字符。
3. 检查同一个用户在本进程内只能有一条进行中的请求，整个进程最多同时处理四条。
4. 在数组开头加入服务端 system 消息，再调用 `model.complete(prompt)`。
5. 在 `finally` 中释放并发名额，无论正常返回还是异常都执行。

```java
prompt.add(new ChatMessage("system", "后端规定的学习助手指令……"));
prompt.addAll(messages);
return model.complete(prompt);
```

这段展示拼接顺序，完整指令在 `ChatService.java`。先放 system，再放历史和新问题。本课的 MySQL 参与用户与权限查询；聊天业务本身没有 ChatMapper，也没有聊天记录表。

并发控制使用 `ConcurrentHashMap.newKeySet()` 记录正在请求的用户，使用 `Semaphore(4)` 管理总名额。这是单进程并发保护，不是每日额度或多实例限流。生产环境还需要单独设计账户用量和配额。

## 五、LlmClient：如何实际请求模型

`backend/src/main/java/com/example/aiknowledge/service/LlmClient.java` 负责模型接口格式、HTTP 调用、响应解析与错误转换。

```java
body.put("model", model);
body.put("messages", messages);
body.put("stream", false);
body.put(tokenParameter, 800);
if (!thinking.isBlank()) body.put("thinking", Map.of("type", thinking));
```

- `model` 决定调用哪个模型，由后端环境变量提供。
- `messages` 是刚拼好的角色与内容数组。
- `stream=false` 表示等完整响应后一次返回；页面因此显示“正在等待模型回复”。
- 输出 token 上限设为 800，参数名称可配置。它不等于 800 个汉字，也不保证一定生成 800 token；某些模型会把推理 token 也纳入上限，具体以模型文档为准。

Token 是模型处理文本的片段单位，字符数量和 token 数量没有固定的一一对应关系。本课的 12000 字符是应用层输入限制，不是精确的模型 token 计数，也不保证适合所有模型的上下文窗口。

常见参数 `temperature` 用于调节采样随机性，但支持范围、作用随模型而异。本课没有发送它，保留服务商默认值。智谱接口还提供 `do_sample` 控制是否采样，关闭采样时会忽略 temperature；具体见 [官方接口说明](https://docs.bigmodel.cn/api-reference/模型-api/对话补全)。不要把 temperature 当作“准确率开关”。

```java
.header("Authorization", "Bearer " + key)
.header("Content-Type", "application/json")
.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
```

这里 Jackson 把 Java Map / record 转成 JSON 字符串；HttpClient 构建并发送请求。上游地址、Key 和模型名都不接受浏览器覆盖。客户端禁止自动跟随重定向，避免带密钥的请求转去另一个地址。

拿到响应后读取：

```java
var choice = data.path("choices").path(0);
var answer = choice.path("message").path("content");
```

也就是从模型服务的返回结构中取第一条回答。最终后端返回简化后的结构：

```json
{"content":"这里是模型回复。","model":"配置的模型名","truncated":false}
```

如果 `finish_reason` 是 `length`，`truncated` 为 true，页面提醒回复可能不完整。没有有效文本、只返回工具调用或其他不兼容结构时，返回错误，不虚构回答。本客户端目前只支持文本 Chat Completions。

客户端设置 5 秒连接超时、45 秒整体等待上限，并用 `BoundedBody` 限制响应体最多 256 KiB。`BodySubscriber` 一批一批接收字节，超限取消订阅；整个响应体完成才结束 future，因此不能只返回响应头后无限等待。合法回复文本另外限制为 6000 字符。

## 六、Vue 怎样保存与显示回复

`frontend/src/api/chat.js` 用同一个 Axios 实例访问 `/api/chat`。原有拦截器已扩展为给本地 Chat 接口添加 JWT；模型服务 URL 和 API Key 不进入前端。

`frontend/src/utils/chat.js` 中：

- `buildChatMessages()` 从历史末尾倒着取最多五对问答，达到字符预算时停止，保留完整问答对，再追加当前问题。
- `createChatConversation()` 用 Vue `ref` 管理记录、草稿、等待状态和错误。
- 发送时置 `isBusy=true`，防止重复提交；成功后追加 assistant 回复并清空草稿。
- 失败时撤回本次临时 user 气泡，保留草稿供手动重试，避免把失败轮次拼进下一次上下文。
- `generation` 区分新旧对话，`sessionVersion` 区分登录会话，防止迟到的旧回复混入新会话。

页面使用：

```vue
<p>{{ message.content }}</p>
```

Vue 插值把内容作为文本显示，CSS `white-space: pre-wrap` 保留换行。模型返回 HTML 标签也只显示为文字，不通过 `v-html` 执行。暂不渲染 Markdown。

聊天组件没有列入 KeepAlive。离开页面、刷新、退出登录或点击“新对话”会清空本页记录；后端不持久保存聊天内容，服务商的数据保留规则则由服务商决定。页面可以显示本次会话全部记录，但发送给模型的历史最多五轮，两者不是一回事。

组件卸载时使用 AbortController 取消浏览器等待，同时废弃旧响应。浏览器取消不保证模型服务立即停止生成，也不保证免除已产生的用量。本课遇到失败和超时不会自动重发。

## 七、如何理解报错

| 本项目状态码 | 含义和处理 |
| --- | --- |
| 400 | 消息结构或长度不符合要求，检查问题或开启新对话 |
| 401 | 本项目未登录或登录过期，重新登录 |
| 403 | 本项目账号没有 chat:send 权限 |
| 429 | 同一用户上一条请求仍在执行，稍候再试 |
| 503 | 本地模型未配置、并发已满，或服务商限流／额度问题，查看具体提示 |
| 502 | 模型服务拒绝访问、返回错误或接口格式不兼容，检查后端 Key、地址、模型 |
| 504 | 等待模型超时；可能已有用量，确认后手动重试 |

模型服务返回的 401/403 会转换为本项目的 502。否则 Axios 会误以为你的本系统登录失效而退出。错误提示不会原样回显上游响应正文，避免泄露上游内部信息。

## 八、动手验证

1. 不填模型配置启动新版后端，登录后打开 AI 问答，应看到配置提示，发送按钮不可用。
2. 填好本地配置并重启，重新登录；发送“请用一个例子解释 Controller 和 Service 的区别”。
3. 继续问“那这个例子中的 Mapper 负责什么”，观察上下文是否被利用。
4. 在浏览器 Network 查看 `/api/chat`：请求只有消息和本项目 JWT；响应只包含 content、model、truncated，没有服务商 API Key。
5. 点击“新对话”，或离开页面再进入，观察记录清空。模型等待期间不能重复点击发送。

代码验证使用本地模拟模型服务，覆盖真实 HTTP 的鉴权、消息拼接、凭据隔离、上游错误、响应体超限、超时与不自动重试。另有前端上下文裁剪、重复提交和迟到响应测试。模拟服务只存在于测试代码，运行中的产品不会把模拟回答当作真实模型回复。

本课自动测试不证明某个真实服务商的账户、余额和模型可用。完成本地配置后的真实对话需要按以上步骤验收。

## 九、理解检查

1. 为什么本次 ChatService 可以没有 ChatMapper？
2. 为什么浏览器的 JWT 和模型的 API Key 不能混用？
3. 为什么第二轮需要带第一轮的 user 和 assistant 消息？
4. 为什么模型返回 401，本项目却返回 502？
5. `stream=false` 时，用户为什么需要等完整回复？

下一课将围绕最后一个问题学习流式输出，让回复逐步出现在页面上。
