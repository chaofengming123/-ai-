# 第 7 课：异步加载、失败与重试

## 本节目标

打开知识库页面，先看到“正在加载”，约一秒后出现列表。点击“模拟加载失败”观察错误，再点击“重试”恢复。

本课使用浏览器内的模拟数据源，不请求服务器。首次加载、重新加载和重试都等待约一秒；创建仍同步执行。刷新整个页面后，数据源与 Pinia 一起重建，临时新增记录消失。

沿用开发服务，无新依赖。未启动时，在 frontend 目录运行 `npm run dev`。

## 为什么要学习异步

真实接口不会立即返回。等待期间，页面仍应能显示提示、响应导航；完成后再更新数据。如果请求失败，也要让用户知道并能重试。

本课将流程拆成三层：

- `src/api/knowledgeBases.js`：提供模拟读取和模拟数据源。
- `src/stores/knowledgeBases.js`：管理加载状态，接收结果或错误。
- 页面组件：根据状态显示内容和按钮。

## 1. Promise：表示一个未来才知道结果的操作

```js
export function fetchKnowledgeBases({ simulateFailure = false } = {}) {
  return new Promise((resolve, reject) => {
    setTimeout(() => {
      if (simulateFailure) {
        reject(new Error('模拟加载失败，请点击重试。'))
        return
      }
      resolve(mockRecords.map(item => ({ ...item })))
    }, 1000)
  })
}
```

**作用：** 返回一个 Promise，让调用方等待模拟读取的结果。

**如何实现：** Promise 初始处于 pending（等待）状态。约一秒后，`setTimeout` 安排的回调执行：正常时 `resolve(records)` 将结果置为 fulfilled（成功），模拟失败时 `reject(error)` 将结果置为 rejected（失败）。Promise 一旦成功或失败，就不会再改变最终状态。

`1000` 是毫秒。定时器表示至少等到相应时间再安排回调，不保证精确一秒，也不会让整个浏览器在这期间停止工作。

`{ simulateFailure = false } = {}` 是默认参数：不传参数也能调用；未指定是否失败时按正常情况处理。

`map` 返回数组副本，每条记录也复制一层，避免 Pinia 直接持有模拟数据源的记录对象。本课字段都是简单值。

## 2. async/await：按顺序处理未来结果

store 中新增：

```js
const knowledgeBases = ref([])
const isLoading = ref(false)
const loadError = ref('')
const hasLoaded = ref(false)
```

列表开始为空，但这时并不表示“数据库里没有记录”，而是“还没读到数据”。因此额外用状态区分：

| 状态 | 作用 |
| --- | --- |
| isLoading | 此刻是否有加载操作进行中 |
| loadError | 最近一次加载的错误文字 |
| hasLoaded | 是否曾成功取得数据 |
| knowledgeBases | 最近一次成功取得或本次创建的记录 |

完整 action：

```js
async function loadKnowledgeBases(options = {}) {
  if (isLoading.value) return
  isLoading.value = true
  loadError.value = ''
  try {
    const records = await fetchKnowledgeBases(options)
    knowledgeBases.value = records
    hasLoaded.value = true
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '加载失败，请重试。'
  } finally {
    isLoading.value = false
  }
}
```

**作用：** 开始加载，等待结果，处理成功或失败，最后结束加载状态。

**如何实现：** `async` 让函数返回 Promise；`await` 暂停这一次函数执行的后续部分，直到读取的 Promise 有结果。它不会暂停整个浏览器。

成功时，`await` 得到 records，继续执行数组赋值。失败时，`await` 会抛出拒绝原因，跳到 catch。不会先执行成功分支再执行失败分支。

`async` 不会把大量同步计算自动移到另一个线程。本课页面能继续响应，是因为等待的是定时器产生的异步结果。

## 3. try/catch/finally：三段各自负责什么

- `try`：尝试读取并更新数据。
- `catch`：捕获失败，生成用户可见的错误提示。
- `finally`：无论本次 try 成功还是失败，都把 isLoading 设回 false。

如果只在成功后关闭加载状态，失败时可能一直转圈、按钮一直禁用。finally 把收尾逻辑放在统一位置。

本例 catch 已处理错误，没有再次 throw，所以外部等待这个 action 时不会因为本次模拟失败而收到拒绝；页面读取 loadError 展示失败状态。不要误以为“await action 结束”就一定代表读取成功。

`if (isLoading.value) return` 在 try 之前，表示已有请求时本次调用直接退出，不发第二次读取；它也不会等待前一个请求完成。本课调用方不依赖这次重复调用的返回结果。

## 4. onMounted：页面首次显示后触发加载

知识库页面和工作台都使用：

```js
onMounted(() => {
  if (!hasLoaded.value) knowledgeBaseStore.loadKnowledgeBases()
})
```

**作用：** 页面首次挂载到界面后，必要时获取数据。

**如何实现：** `onMounted` 注册生命周期回调。进入任意一个需要数据的页面，都能启动首次读取。如果另一个页面已成功读取，hasLoaded 为 true，就复用共享数据。如果仍在加载，store 的 isLoading 检查会阻止重复请求。

KeepAlive 恢复已缓存的知识库页面时不会再次触发 onMounted，因此本课切页不会自动反复读取。需要主动读取时点击“重新加载”。

## 5. 页面按状态显示不同内容

```vue
<p v-if="isLoading" role="status">正在加载知识库…</p>
<div v-if="loadError" role="alert">
  <p>{{ loadError }}</p>
  <button @click="knowledgeBaseStore.loadKnowledgeBases()">重试</button>
</div>
```

这是核心结构节选，实际模板还包含样式、按钮类型和旧数据提示。

**作用：** 等待时有反馈，失败时有原因和重试入口。

重试调用正常加载，不携带 `simulateFailure: true`，所以本次模拟会成功。真实接口的重试仍可能失败；重试不是保证成功的机制。

搜索区域、总数和空结果提示都以 hasLoaded 为前提，避免初次等待时误显示“0 个”或“没有匹配结果”。工作台也先显示加载提示，成功后才显示数量。

已有数据时重新加载，旧卡片继续显示；失败也保留旧数组，并说明这是上次的数据。只有成功读取时才替换列表。

## 6. 为什么同时禁用按钮和检查 store

```vue
:disabled="isLoading || !hasLoaded"
```

创建按钮在首次数据未就绪或正在加载时禁用。重新加载和模拟失败按钮也在等待期间禁用，避免连点。

store 的创建 action 也检查同样条件。界面的禁用负责用户体验，store 检查保证其他调用入口也遵守规则。

加载期间不创建，避免“先新增，随后旧读取结果覆盖列表”这类冲突。本课场景较小，没有增加取消请求、请求编号或复杂并发机制。

## 7. 重新加载与刷新有什么不同

模拟数据源 `mockRecords` 放在 api 模块内，首次由初始示例复制而来。创建 action 先调用：

```js
insertMockKnowledgeBase(knowledgeBase)
knowledgeBases.value.push(knowledgeBase)
```

`insertMockKnowledgeBase` 同步向模拟数据源写入一份记录副本；第二行更新供页面显示的 Pinia 列表。这样点击“重新加载”时仍可读取本次新增的记录。

这是教学用模拟数据源与界面状态两层数据，没有真实服务器、网络请求或持久化。以后接后端时，服务端保存记录，前端通过接口取回。

- 点击“重新加载”：应用仍在运行，读取当前内存数据源，新增记录保留。
- 刷新整个浏览器页面：模块和 store 重新初始化，恢复原始示例。

本课创建仍同步执行，不要把它理解为真实异步 POST。异步写入及接口错误将在接后端时继续处理。

## 完整执行顺序

页面挂载 → 调用加载 action → isLoading 为 true → 页面显示等待 → await Promise → 成功更新数组，失败写入错误 → finally 结束加载 → 页面显示结果或重试入口。

点击模拟失败后，同样经历等待，再进入 catch；点击重试后开始新一轮读取。

## 小练习

1. 点击“重新加载”，观察等待提示和暂时禁用的按钮。
2. 点击“模拟加载失败”，确认旧卡片仍在，再点“重试”。
3. 新建知识库后重新加载，确认记录保留；刷新浏览器，观察差别。

## 下一步

开始 Spring Boot 后端基础，先建立一个可运行的健康检查接口，理解浏览器、HTTP 请求与 Controller 的关系，再逐步连接知识库 API。
