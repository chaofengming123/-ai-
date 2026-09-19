# 第 4 课：用 Props 和 Emit 让组件协作

## 本节目标

将知识库卡片拆成独立组件，点击每张卡片的“查看详情”，由父页面显示对应记录。

本课只展示名称、描述、分类和文档数量，不接后端，不打开真实文档。切页缓存和刷新重置仍沿用第三课的行为。

开发服务未启动时，在项目根目录运行 `cd frontend`，再运行 `npm run dev`，打开终端显示的地址。无需安装新依赖。

## 为什么拆组件

上一课把卡片的 HTML 全部写在知识库页面里。现在将卡片放进 `frontend/src/components/KnowledgeBaseCard.vue`，以后修改卡片外观或在其他页面复用时，就可以使用同一个组件。

`KnowledgeBaseView.vue` 是父组件：管理列表、创建操作和详情弹窗。`KnowledgeBaseCard.vue` 是子组件：展示一条记录，并报告用户点击了哪张卡片。

原来的 `v-for` 已经避免重复写多份 HTML；本次拆分进一步明确职责，并让这块界面可以被其他页面复用。

## 1. defineProps：声明子组件需要的数据

卡片组件的 `<script setup>` 中：

```js
defineProps({
  knowledgeBase: {
    type: Object,
    required: true,
  },
})
```

**作用：** 声明卡片需要父组件传入一个名为 `knowledgeBase` 的对象。

**如何实现：** `defineProps` 是 Vue 在 `<script setup>` 中提供的编译宏，不需要单独导入。`type: Object` 声明预期为对象，`required: true` 声明必填。开发时传错类型或漏传，Vue 会发出警告；这不是安全校验，也不会自动检查对象里是否有 name 等所有字段。

模板可以直接读取声明的 prop：

```vue
<h3>{{ knowledgeBase.name }}</h3>
<p>{{ knowledgeBase.description }}</p>
```

同一个卡片组件收到不同对象，就显示不同名称和描述。卡片不需要自己导入整个模拟数据数组。

## 2. :knowledge-base：父组件把对象传下去

父页面中：

```vue
<KnowledgeBaseCard
  v-for="knowledgeBase in knowledgeBases"
  :key="knowledgeBase.id"
  :knowledge-base="knowledgeBase"
  @view-detail="openKnowledgeBaseDetail"
/>
```

**作用：** 数组中每一条记录对应一个卡片组件实例。

**如何实现：** `v-for` 依次取出记录，冒号绑定把右侧变量的对象传给左侧 prop。模板里的 `knowledge-base` 与 JavaScript 声明的 `knowledgeBase` 对应，这是常用的命名写法。

这里左侧是组件接收的属性名，右侧是当前循环变量。去掉冒号会传入字符串 `"knowledgeBase"`，不会传入对象。

`:key` 是 Vue 用来识别组件实例的特殊属性。它不代替 Props；卡片仍通过 `knowledgeBase.id` 获取业务编号。

Props 的方向是父组件传给子组件。子组件应把 Props 当作只读输入。Vue 会阻止直接重设 prop，但对象内部字段仍可能被修改，所以不能认为所有深层修改都由框架自动禁止。我们约定卡片不修改 `knowledgeBase.name`，需要修改时应通知拥有数据的父页面处理。

## 3. defineEmits：声明子组件发出的事件

卡片组件中：

```js
const emit = defineEmits(['view-detail'])
```

按钮的核心代码：

```vue
<button type="button" @click="emit('view-detail', knowledgeBase.id)">
  查看详情 →
</button>
```

**作用：** 把用户的“查看详情”操作通知父组件，并告诉它是哪条记录。

**如何实现：** `defineEmits` 同样是无需导入的编译宏，返回用于发事件的 `emit` 函数。第一个参数 `'view-detail'` 是我们自定义的事件名称；第二个参数是事件携带的数据，这里是知识库编号。

`@click` 监听按钮的原生点击事件；`emit('view-detail', ...)` 发出组件自定义事件。这是两个不同层次的事件。

Emit 不会自动打开弹窗、修改列表或发送网络请求。父组件必须监听并处理。组件事件也不会像某些 DOM 事件那样自动冒泡到所有祖先组件。

实际按钮还用 `:aria-label` 生成“查看公司制度知识库的详情”这类完整名称，便于辅助阅读工具区分各卡片按钮。

## 4. @view-detail：父组件接收事件参数

```vue
@view-detail="openKnowledgeBaseDetail"
```

**作用：** 指定收到 `view-detail` 事件后执行哪个函数。

**如何实现：** Vue 会把子组件 emit 携带的编号传给处理函数。点击第一张卡片就传第一条记录的 id，点击其他卡片就传相应 id。

父页面中的完整函数：

```js
const detailDialog = ref(null)
const selectedKnowledgeBase = ref(null)

function openKnowledgeBaseDetail(id) {
  const knowledgeBase = knowledgeBases.value.find(item => item.id === id)
  if (!knowledgeBase) return

  selectedKnowledgeBase.value = knowledgeBase
  detailDialog.value.showModal()
}
```

**作用：** 根据编号找到数据，记住当前选中的记录并打开详情弹窗。

**如何实现：** `find` 返回第一条符合条件的记录，找不到则返回 `undefined`；`if (!knowledgeBase) return` 避免继续操作不存在的记录。它与第二课的 `some` 不同：`some` 只回答是否存在，`find` 返回记录本身。

`selectedKnowledgeBase` 保存选中的对象供模板展示，不是持久保存，也没有复制一份独立记录。编号可能是初始数据里的数字，也可能是新建时生成的 UUID 字符串；卡片传回原始 id，因此严格比较 `===` 能匹配。

`detailDialog` 是原生 dialog 元素的模板引用。与第二课相同，调用 `showModal()` 打开弹窗。弹窗容器一直存在，只有内部详情按条件渲染，所以调用时能取得元素。

## 5. v-if：有选中记录时才显示详情

实际模板中的结构节选：

```vue
<dialog ref="detailDialog" aria-labelledby="detail-title">
  <h2 id="detail-title">知识库详情</h2>
  <dl v-if="selectedKnowledgeBase">
    <dt>名称</dt><dd>{{ selectedKnowledgeBase.name }}</dd>
    <dt>描述</dt><dd>{{ selectedKnowledgeBase.description }}</dd>
  </dl>
  <button type="button" @click="detailDialog.close()">关闭</button>
</dialog>
```

**作用：** 页面刚启动时还没有选择记录，避免读取 `null.name` 导致错误。

**如何实现：** `selectedKnowledgeBase` 初始为 `null`，`v-if` 不渲染详情。点击卡片后它变成对象，Vue 渲染对应内容。再次选择另一张卡片，会换成另一条记录。实际文件还展示分类和文档数量。

关闭按钮或 Esc 关闭弹窗，浏览器将焦点返回触发按钮。这里只读展示，不会改变列表条数或记录内容。

## 完整数据流

父页面数组 → Props 传入单条记录 → 卡片展示 → 用户点击 → Emit 发出 id → 父页面 find 查记录 → 设置选中状态 → 打开详情。

本课要能解释这句话：**数据通过 Props 向下传，操作通过 Emit 向上通知，父页面决定如何处理。**

## 小练习

1. 依次打开两个示例知识库，确认名称与 3 / 10 份文档分别对应正确。
2. 新建一个知识库，再点它的详情，确认显示你的输入和 0 份文档。
3. 找到卡片按钮的 `emit('view-detail', knowledgeBase.id)`，沿着父页面的 `@view-detail` 找到处理函数，跟读一次执行顺序。

## 本课验证

生产构建通过；两个初始卡片及新建卡片的详情数据正确；关闭按钮和 Esc 均可关闭，焦点回到触发按钮；切换页面后，新增记录和详情功能仍可使用。

## 下一步

为知识库列表加入关键词搜索，用 `computed` 根据搜索词生成筛选结果，理解“原始数据”和“计算得到的数据”的区别。
