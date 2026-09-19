# 第 3 课：Vue Router，让地址决定显示哪个页面

## 本节目标

点击侧边栏切换工作台、知识库、文档管理和 AI 问答，观察地址、标题和导航高亮一起变化。文档管理和 AI 问答目前是说明页，不提供上传或问答功能。

沿用上一课的开发服务。若未启动，在项目根目录运行 `cd frontend`，然后运行 `npm run dev`，打开终端给出的地址。

## 为什么需要 Router

前两课的 `App.vue` 直接写着 `<KnowledgeBaseView />`，所以主内容区始终显示知识库。现在每个页面需要自己的地址，也需要支持浏览器前进和后退。

Vue Router 负责把地址映射到组件。例如 `/documents` 对应 `DocumentView.vue`。这里仍是一个 Vue 应用：点击内部导航时不重新加载整份 HTML，而是更新地址并切换主内容区的组件，这就是单页应用（SPA）的常见工作方式。

## 1. 路由表：声明地址与组件的关系

文件：`frontend/src/router/index.js`。

下面是实际路由表的简化摘录，其余页面采用相同写法：

```js
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/knowledge-bases' },
    { path: '/dashboard', component: DashboardView, meta: { title: '工作台' } },
    { path: '/knowledge-bases', component: KnowledgeBaseView, meta: { title: '知识库' } },
  ],
})
```

**作用：** 告诉路由器，访问某个地址时应该使用哪个页面组件。

**如何实现：** `createRouter` 创建路由器；`routes` 是规则数组；`path` 是地址的路径部分；`component` 是已导入的 Vue 组件。访问 `/knowledge-bases` 时，路由器匹配对应规则，选中 `KnowledgeBaseView`。

`redirect` 表示转向另一个地址，因此访问 `/` 会转到 `/knowledge-bases`。`meta` 保存我们自定义的路由附加信息，这里用于页面标题。

`createWebHistory()` 使用浏览器 History API，让地址呈现为 `/documents`，并支持历史记录。它不会创建名为 documents 的实体文件夹。

| 地址 | 页面组件 | 当前用途 |
| --- | --- | --- |
| `/dashboard` | `DashboardView.vue` | 工作台与知识库入口 |
| `/knowledge-bases` | `KnowledgeBaseView.vue` | 列表和模拟创建 |
| `/documents` | `DocumentView.vue` | 文档功能说明 |
| `/chat` | `ChatView.vue` | AI 问答功能说明 |

## 2. use(router)：把路由器接入 Vue

文件：`frontend/src/main.js`。

```js
import router from './router/index.js'

createApp(App).use(router).mount('#app')
```

**作用：** 让整个 Vue 应用可以使用这个路由器。

**如何实现：** `createApp(App)` 创建应用，`.use(router)` 安装路由插件，`.mount('#app')` 把应用放到 HTML 的容器里。安装发生在挂载之前，路由组件和路由信息才可以在页面运行时使用。

本课通过 `npm install vue-router@4` 安装依赖，版本记录在 `package.json` 和锁文件中。获取更新后的项目时，先在前端目录运行 `npm install`。

## 3. RouterLink：点击导航并更新地址

文件：`frontend/src/components/AppSidebar.vue`。

```vue
<RouterLink v-for="item in navigation" :key="item.path" :to="item.path"
  class="nav-item" exact-active-class="active">
  <span aria-hidden="true">{{ item.icon }}</span> {{ item.label }}
</RouterLink>
```

**作用：** 为导航数组的每一项生成一个可以切换页面的链接，并高亮当前页面。

**如何实现：** `v-for` 遍历导航数据，`:to="item.path"` 把目标地址传给 RouterLink。普通点击时，它阻止链接默认的整页加载行为，通过路由器改变地址和当前路由。RouterLink 仍生成真正的链接，因此也保留在新标签页打开等链接能力。

当目标路由与当前路由精确匹配时，`exact-active-class="active"` 自动添加 `active` 样式类，已有 CSS 会将背景变亮。不需要自己维护一个“当前选中第几个菜单”的变量。它还会为当前链接设置 `aria-current="page"`，帮助辅助阅读工具识别位置。

注意 `:to` 前面的冒号：它表示读取 JavaScript 表达式。若写成 `to="item.path"`，目标就成了字面文字，而不是数组里保存的地址。

## 4. RouterView：给当前页面提供显示位置

文件：`frontend/src/App.vue`。

最基本的用法是：

```vue
<RouterView />
```

**作用：** 在这里显示当前地址匹配到的组件。侧边栏和顶部栏仍由 App 保留，因此切换时只更换主内容。

**如何实现：** RouterView 读取当前路由的匹配结果。例如地址变成 `/chat`，它就渲染 `ChatView`。这一变化由 RouterLink 和路由器协作触发。

本项目实际写法多了一层缓存，下一段解释原因。

## 5. KeepAlive：切页时保留知识库记录

```vue
<RouterView v-slot="{ Component }">
  <KeepAlive include="KnowledgeBaseView">
    <component :is="Component" />
  </KeepAlive>
</RouterView>
```

**作用：** 防止离开知识库页面时销毁组件，导致上一课用 `ref` 保存的新增记录丢失。

**如何实现：** RouterView 通过插槽提供当前匹配的 `Component`；`v-slot="{ Component }"` 取出它；`<component :is="Component" />` 动态渲染这个组件。Vue 内置的 KeepAlive 缓存被包裹的组件实例。

`include="KnowledgeBaseView"` 只缓存这个名称的组件。`<script setup>` 组件名称可由文件名 `KnowledgeBaseView.vue` 推断，因此这里能对应上。其他页面没有需要保留的输入状态，不缓存。

可以暂时把插槽理解为“RouterView 把选中的组件交给我们，我们加一层缓存再显示”。无需本课掌握插槽的所有用法。

这只是内存缓存：通过导航离开再回来，记录保留；按刷新、重新打开页面或直接进行整页导航，应用重新初始化，缓存和新增记录都会消失。它不等于数据库保存，也不等于多个页面共享同一份业务数据。

## 6. useRoute 和 meta：同步顶部栏与浏览器标题

在 App 中：

```js
const route = useRoute()
```

```vue
<span class="breadcrumb">/ {{ route.meta.title }}</span>
```

**作用：** 显示当前页面名称。

**如何实现：** `useRoute()` 返回响应式的当前路由信息。路由变化时，模板重新读取 `route.meta.title`，顶部标题跟着更新。

在路由配置文件中：

```js
router.afterEach(to => {
  document.title = `${to.meta.title} · AI Knowledge`
})
```

`afterEach` 在导航结束后执行，`to` 表示目标路由。它把标题写入浏览器标签页。例如进入文档管理后，标签名变成“文档管理 · AI Knowledge”。

路由配置里的 `scrollBehavior()` 返回 `{ top: 0 }`，让切页后回到顶部；本课未实现逐页面的历史滚动位置恢复。

## 7. 兜底路由：地址输错时仍有返回入口

```js
{ path: '/:pathMatch(.*)*', component: NotFoundView, meta: { title: '页面不存在' } }
```

**作用：** 捕获没有对应业务页面的地址，展示“页面不存在”和返回知识库的链接。

**如何实现：** `pathMatch` 是参数名，`(.*)` 可匹配任意内容，最后的 `*` 允许重复匹配多个路径片段。前面的固定页面路由会优先匹配，其余路径交给这个兜底规则。

这是前端展示的 404 页面，不等同于服务器返回 HTTP 404 状态码。

## 直接刷新为什么需要服务器配合

点击 RouterLink 时，Vue 在已加载的应用内部切换。直接打开或刷新 `/documents` 时，浏览器会先向服务器请求这个地址。Vite 开发服务会回退提供 `index.html`，随后 Vue Router 接管匹配。

以后部署到 Nginx，需要配置前端路径回退到 `index.html`。否则点击导航正常，刷新却可能报 404。API 和静态资源要有各自的处理规则，不能一律当作前端页面。本课只验证本地开发服务，部署配置在后续课程完成。

## 执行顺序

点击“文档管理” → RouterLink 请求导航 → Router 匹配 `/documents` → RouterView 显示 DocumentView → 导航高亮和顶部标题更新 → afterEach 更新浏览器标题。

浏览器后退时，路由器收到历史记录变化，也会重新匹配并显示对应页面。

## 小练习

1. 新建一个知识库，切到工作台再回来，确认记录仍在。
2. 点击文档管理和 AI 问答，观察地址与标题；再试浏览器后退。
3. 刷新知识库页面，观察新增记录消失，区分“切页缓存”和“持久保存”。

本课应能解释：RouterLink 决定去哪里，路由表决定用哪个组件，RouterView 决定组件显示在哪里。

## 下一步

把知识库卡片拆成可复用组件，用 Props 把数据传进去，再用 Emit 把用户操作通知父组件，学习组件之间如何协作。
