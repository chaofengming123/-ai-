# 第 10 课：Vue 通过 Axios 连接真实后端

## 本节目标

Vue 读取 Spring Boot 的知识库列表，创建时发送 POST。浏览器刷新后，记录仍从后端读回；重启后端才会恢复初始数据。

本课第一次打通前后端。后端仍为内存存储，没有 MySQL、登录或权限控制。

## 1. 同时启动两个服务

在两个终端分别运行：

```bash
# 终端一，从项目根目录进入后端
cd backend
./mvnw spring-boot:run
```

```bash
# 终端二，从项目根目录进入前端
cd frontend
npm install
npm run dev
```

后端默认在 8080，前端地址以 Vite 输出为准，通常是 5173。若已有对应服务，不必重复启动。更新代理配置或安装 Axios 后，如开发服务没有正确刷新，停止并重启前端服务。

打开前端知识库页面即可操作，不再使用模拟等待和模拟失败按钮。原始模拟数据文件保留作早期课程参考，当前应用不再导入它。

## 2. Axios：发送 HTTP 请求

文件：`frontend/src/api/http.js`。

```js
export const http = axios.create({
  baseURL: '/api',
  timeout: 10000,
})
```

**作用：** 建立统一 HTTP 客户端，集中设置路径前缀与超时。

**如何实现：** `axios.create` 返回配置好的客户端；请求 `/knowledge-bases` 时与 baseURL 合并为 `/api/knowledge-bases`。timeout 的单位是毫秒，超过配置时间会报告超时。Axios 返回 Promise，因此沿用第七课的 async/await。

Axios 不负责持久化、创建数据库或判断登录权限。它只是负责构造、发送请求并处理响应的客户端工具。

## 3. 开发代理：从前端转发到后端

文件：`frontend/vite.config.js`。

```js
server: {
  proxy: {
    '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
  },
},
```

**作用：** 让前端使用 `/api` 相对路径访问后端。

**如何实现：** 浏览器向前端服务发送 `/api/knowledge-bases`，Vite 看到路径以 `/api` 开头，就将请求转发到 `http://127.0.0.1:8080/api/knowledge-bases`，再把响应交回浏览器。这里没有 rewrite，因此保留 `/api` 前缀。

浏览器看到的是对前端同源地址的请求；Vite 到后端是服务器端转发。`changeOrigin` 调整转发请求的 Host 头，不是关闭浏览器安全检查。

同源由协议、主机和端口共同决定。直接从 5173 请求 8080 属于跨源，通常需要后端 CORS 配置；本课用开发代理处理本地联调，没有开放任意来源。

代理只适用于本课 `npm run dev` 的 Vite 开发服务；打包文件不会携带一个 Vite 代理服务器。生产部署需要 Nginx 等提供相应 API 路由，后续再实现。

## 4. GET：响应对象与响应数据

文件：`frontend/src/api/knowledgeBases.js`。

```js
export async function fetchKnowledgeBases() {
  const response = await http.get('/knowledge-bases')
  if (!Array.isArray(response.data)) throw new Error('知识库列表响应格式不正确。')
  return response.data
}
```

**作用：** 取得后端知识库数组，交给 store。

**如何实现：** `get` 发出 GET，`await` 等待响应。Axios response 包含 status、headers、data 等信息；`response.data` 才是 JSON 响应体解析得到的数据。数组检查能避免把 HTML 等错误响应当成列表，但不是完整字段结构验证。

store 的加载 action 保持第七课结构：请求成功后赋值给 knowledgeBases，失败设置 loadError，finally 关闭 isLoading。已有数据时读取失败仍保留旧列表。

## 5. POST：由后端生成完整记录

```js
export async function createKnowledgeBase(data) {
  const response = await http.post('/knowledge-bases', data)
  return response.data
}
```

**作用：** 把名称和描述发给后端，取得创建完成的记录。

**如何实现：** Axios 将普通对象序列化为 JSON，并设置相应内容类型。后端 DTO 接收 name、description，Service 校验后生成 id、category 和 documentCount，返回 201 与完整记录。

前端不再生成 UUID、不再调用模拟写入函数，也不先插入一张“可能创建失败”的卡片，而是在服务器确认成功后更新 store。

## 6. store action 也必须变成异步

核心代码：

```js
isSaving.value = true
try {
  const knowledgeBase = await createKnowledgeBase({
    name: trimmedName,
    description: trimmedDescription,
  })
  knowledgeBases.value.push(knowledgeBase)
  return { knowledgeBase }
} catch (error) {
  return { error: apiErrorMessage(error, { creating: true }) }
} finally {
  isSaving.value = false
}
```

**作用：** 管理整个提交过程，只追加后端确认的记录。

**如何实现：** action 已声明为 async。输入仍先做空白与长度检查，重名检查交给后端，避免依据过时的前端列表判断。`isSaving` 是提交状态，与读取列表的 `isLoading` 分开。

开始提交时阻止第二次提交；提交中也不重新读取列表，加载中不创建，避免旧 GET 结果覆盖刚创建的记录。前端按钮的禁用和 store 的检查共同防重复，但不等于服务器端幂等机制。

## 7. 页面调用 action 时同样要 await

```js
async function createKnowledgeBase() {
  error.value = ''
  const result = await knowledgeBaseStore.addKnowledgeBase({
    name: name.value,
    description: description.value,
  })
  if (result.error) {
    error.value = result.error
    return
  }
  createDialog.value.close()
  // 随后显示创建成功提示。
}
```

**作用：** 等后端返回结果后，决定显示错误还是关闭弹窗。

少了 await，result 就是 Promise，而不是包含 error 或 knowledgeBase 的结果对象。

提交中按钮显示“正在提交…”，禁用输入、取消和重复创建，并拦截 dialog 的 Esc 取消事件。请求结束后恢复操作，失败时保留用户输入。实际接口很快时，提交中文字可能只短暂出现。

## 8. HTTP 错误与网络错误

Axios 默认把非 2xx 响应视为失败。错误处理函数读取：

```js
const status = error.response?.status
const message = error.response?.data?.message
```

`?.` 是可选链。网络连接失败时可能没有 response，使用可选链避免读取不存在的对象。

400/409 等客户端错误带有后端 message 时，表单直接显示该中文提示。例如同名返回 409，显示“这个名称已经存在，请换一个名称”。

GET 超时显示超时提示；连接失败或代理/服务端错误提示检查后端并重试。

POST 的网络错误更特别：请求可能已写入后端，只是响应没有到达。因此本课显示“未能确认创建结果”，提示先重新加载列表确认，不自动重发 POST。超时不代表服务器一定没有执行。

本课没有实现幂等键、自动重试和请求取消，避免在还没理解请求语义时加入复杂机制。

## 9. 数据现在存在哪里

数据库尚未接入。当前流程是：

Vue 表单 → Axios POST → Vite 代理 → Controller → Service 内存 Map → JSON 响应 → Pinia → 卡片与工作台。

刷新时：

Vue 重新启动 → Axios GET → 后端内存 Map → 重新展示已有记录。

因此浏览器刷新不丢记录；重启后端进程会丢失。Pinia 仍只保存当前页面应用里的副本；KeepAlive 保留搜索词等页面状态；MySQL 才是后续跨后端重启持久保存的位置。

搜索仍在前端对已加载列表执行 computed；详情弹窗仍显示本次列表中的记录，没有额外调用详情 GET。其他客户端新建后，点击重新加载才能更新当前列表；本课没有实时推送。

## 验证方式

前端目录运行 `npm test` 和 `npm run build`。store 回归测试用可控 Axios 传输层模拟慢请求、409 和断网，验证提交等待、重复请求拦截、失败不新增、读取失败保留旧数据及重试恢复；不修改正在运行的后端。

真实联调验证了通过页面读取、后端重名提示、页面创建、刷新读回和工作台统计。后端已有第九课集成测试，代码本课未修改。

## 小练习

1. 新建一个知识库后刷新浏览器，确认还在。
2. 再次创建相同名称，观察后端返回的重名提示。
3. 对照 API 文件和 Controller，找到前端的 `/knowledge-bases` 如何最终对应后端的 `/api/knowledge-bases`。

不要为测试断网而随意停止存有需要保留记录的后端：本课内存数据会丢失。需要重启时先确认记录可丢弃。

## 下一步

接入 MySQL，把知识库从后端内存迁到数据库，学习表、主键、Mapper 与持久化。
