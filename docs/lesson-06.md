# 第 6 课：Pinia，让多个页面共享状态

## 本节目标

工作台显示知识库总数。在知识库页面创建一条记录，再切到工作台，总数同步增加。

本课继续使用模拟数据，不连接后端，不持久化。安装了 Pinia；获取本次更新后，在 frontend 目录运行 `npm install`，然后 `npm run dev`。若正在运行的 Vite 在安装新依赖后仍提示无法解析 pinia，停止开发服务后重新启动。

## 为什么需要共享状态

以前知识库数组在 `KnowledgeBaseView.vue` 的 ref 中，属于这个页面。工作台若也复制一份初始数组，两份数据不会自动同步。

现在把知识库数组交给 Pinia 的 store 管理。store 可以理解为应用里管理某类共享数据和操作的地方。同一个应用中，两个页面调用同一个 store 定义，会取得同一个实例。

并非所有数据都需要共享。搜索词、表单输入、错误提示和弹窗引用仍由知识库页面管理。卡片继续通过 Props 接收数据、Emit 报告操作，不直接依赖 store。

## 1. createPinia：在应用中安装 Pinia

文件：`frontend/src/main.js`。

```js
import { createPinia } from 'pinia'

createApp(App).use(createPinia()).use(router).mount('#app')
```

**作用：** 创建 Pinia 容器并安装到 Vue 应用，让页面可以取得 store。

**如何实现：** `createPinia()` 创建管理各 store 的容器，`.use(...)` 将它注册到应用。随后安装 Router，最后挂载页面。本课整个应用共用这一个 Pinia 容器。

## 2. defineStore：定义共享列表和总数

文件：`frontend/src/stores/knowledgeBases.js`。

以下为结构摘录，新增操作在后面说明：

```js
export const useKnowledgeBaseStore = defineStore('knowledge-bases', () => {
  const knowledgeBases = ref(initialKnowledgeBases.map(item => ({ ...item })))
  const knowledgeBaseCount = computed(() => knowledgeBases.value.length)

  // 这里定义 addKnowledgeBase 函数。

  return { knowledgeBases, knowledgeBaseCount, addKnowledgeBase }
})
```

**作用：** 把知识库的数据、计算结果和操作集中放在一个 store 中。

**如何实现：** `defineStore` 返回一个获取 store 的函数，我们命名为 `useKnowledgeBaseStore`。第一个参数 `'knowledge-bases'` 是 store 的唯一标识，不是页面路径，也不是数据库表名。

第二个参数是 setup 函数。首次取得实例时，Pinia 执行它初始化状态；同一个 Pinia 容器中，后续页面获取这个 store 时复用已有实例。

本课使用 setup store 写法：

| 写法 | Pinia 中的角色 | 本课内容 |
| --- | --- | --- |
| ref | state，原始状态 | 完整知识库数组 |
| computed | getter，派生数据 | 数组长度，即总数 |
| function | action，业务操作 | 校验并创建知识库 |

`return` 把页面需要使用的成员公开。总数没有另存一份可手工修改的数字，而是通过 computed 从列表推导，避免列表和数字不一致。

## 3. storeToRefs：取出状态时保留响应式连接

知识库页面中：

```js
const knowledgeBaseStore = useKnowledgeBaseStore()
const { knowledgeBases } = storeToRefs(knowledgeBaseStore)
```

工作台中：

```js
const knowledgeBaseStore = useKnowledgeBaseStore()
const { knowledgeBaseCount } = storeToRefs(knowledgeBaseStore)
```

**作用：** 两个页面分别取得所需数据，仍然连接着同一个 store。

**如何实现：** `storeToRefs` 把 store 的响应式状态和 getter 转成 ref，方便解构使用。它不是复制数组，也不创建第二份业务数据。

直接从 store 解构一个数字，例如 `const { knowledgeBaseCount } = store`，会得到当时的数字值，之后不会跟着更新。数组对象有时仍能响应内部变化，但替换整个数组后也可能失去正确连接。因此从 store 解构状态时统一用 `storeToRefs`。

action 不需要用 storeToRefs 提取。本课直接调用 `knowledgeBaseStore.addKnowledgeBase(...)`。

注意 `.value` 的位置：

- store 的 setup 函数内部，ref 用 `.value`。
- 页面通过 storeToRefs 取得的 ref，在 JavaScript 中用 `.value`。
- 直接读取 store 对象的属性时，Pinia 自动解包，例如 `store.knowledgeBaseCount`。
- 模板也自动解包，所以写 `{{ knowledgeBaseCount }}`。

## 4. action：共享的不只是数据，还有创建规则

原来页面中的校验与 push 已移入 store 的 `addKnowledgeBase`。下面摘录其关键逻辑：

```js
function addKnowledgeBase({ name, description }) {
  const trimmedName = name.trim()
  const trimmedDescription = description.trim()

  if (!trimmedName) {
    return { error: '请输入知识库名称，不能只填写空格。' }
  }
  // 实际代码还检查长度和重复名称。

  const knowledgeBase = {
    id: crypto.randomUUID(),
    name: trimmedName,
    description: trimmedDescription || '暂无描述',
    documentCount: 0,
    category: '自建知识库',
  }
  knowledgeBases.value.push(knowledgeBase)
  return { knowledgeBase }
}
```

**作用：** 不管以后从哪个页面创建，都使用同一套校验和新增规则。

**如何实现：** 参数中的 `{ name, description }` 是对象解构，从传入对象取出字段。当前调用者保证它们为字符串。失败时返回 `{ error: ... }` 并提前结束；成功时 push 新记录，返回 `{ knowledgeBase }`，这是 `{ knowledgeBase: knowledgeBase }` 的简写。

Pinia 不强制所有修改都必须经过 action，但本项目把业务写操作集中在 action 中，便于查找、复用和验证。

action 不操作弹窗元素，也不直接写页面错误提示。这样 store 不依赖某个页面结构。以后真正接收外部 API 数据时仍需要服务端校验，前端规则不能替代它。

## 5. 页面处理结果与界面反馈

知识库页面中：

```js
const result = knowledgeBaseStore.addKnowledgeBase({
  name: name.value,
  description: description.value,
})
if (result.error) {
  error.value = result.error
  return
}
createDialog.value.close()
```

**作用：** 将输入交给 action，失败就显示错误，成功就关闭弹窗并显示创建提示。

**如何实现：** action 返回普通对象，页面读取 error 决定是否继续。成功提示中的名称来自 `result.knowledgeBase.name`，因此显示的是已经去掉两端空格的名称。

本课操作仍然同步执行，没有网络请求，不需要 async/await。后续连接 API 时再学习异步 action。

第五课的搜索仍读取 `knowledgeBases.value`。只是这个 ref 现在连接 store，而非页面独有的数组，因此筛选和卡片详情不需要重写。

## 6. 工作台为什么会同步

```vue
<strong>{{ knowledgeBaseCount }}</strong>
```

工作台读取 store 的 getter。新建操作改变共享列表长度后，computed 更新，总数随之变化。即使工作台离开后被销毁，下次打开仍从同一 store 读取最新值，不必依靠 KeepAlive 缓存工作台。

这里总数始终统计完整列表，不受知识库页面的搜索词影响。搜索只是某个页面自己的显示条件。

## 7. Pinia、KeepAlive 和持久化

| 机制 | 解决的问题 | 本项目现状 |
| --- | --- | --- |
| Pinia | 多个页面共用业务数据和操作 | 列表与工作台共用知识库状态 |
| KeepAlive | 切页时保留组件实例及页面状态 | 保留搜索词、表单等页面状态 |
| 持久化 | 刷新或重开后恢复数据 | 尚未实现，后续接后端数据库 |

知识库数组现在由 Pinia 管理，即使不缓存知识库页面，数组也可在同一应用生命周期中保留。但页面自己的搜索词等状态会随着组件销毁而消失，因此仍保留 KeepAlive。

Pinia 默认不会写入 localStorage 或数据库。刷新会重新创建应用和 Pinia 容器，重新载入两条初始示例；新标签页也是独立的应用实例，不会自动共用当前标签页内存。

## 执行顺序

填写表单 → 页面调用 store action → 校验通过 → 修改共享列表 → getter 计算新总数 → 列表与工作台读取同一份最新状态。

## 小练习

1. 到工作台记住总数，再新建一个知识库，返回工作台确认加一。
2. 在知识库页面搜索某条记录，再看工作台，总数仍统计全部记录。
3. 刷新工作台，观察总数回到初始值，解释为什么共享状态不等于持久化。

## 下一步

加入模拟异步加载，学习 Promise、async/await、加载提示和错误重试，为后续接 Spring Boot API 做准备。先理解请求等待过程，再引入真实网络接口。
