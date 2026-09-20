# 第 5 课：用 computed 计算搜索结果

## 本节目标

在搜索框输入关键词，按名称、描述或分类筛选知识库。结果即时显示，不需要点击搜索按钮。找不到时显示空结果提示，清空后恢复完整列表。

沿用已有开发服务；若未运行，在项目根目录执行 `cd frontend`，再执行 `npm run dev`。本课不安装新依赖。

关键代码位于 `frontend/src/views/KnowledgeBaseView.vue`。继续使用本地模拟数据，没有后端搜索、AI 搜索或持久化。

## 1. ref 保存原始状态

```js
const searchQuery = ref('')
```

**作用：** 保存用户输入的关键词。

模板中的核心代码：

```vue
<input id="knowledge-search" v-model="searchQuery" type="search"
  placeholder="输入名称、描述或分类" />
```

**如何实现：** 输入通过第二课学过的 `v-model` 更新 `searchQuery.value`。`type="search"` 声明这是搜索输入框，本身不会筛选数据，也不会向服务器发送请求。

页面现在有两份独立的原始状态：`knowledgeBases` 保存完整记录，`searchQuery` 保存输入。搜索结果可以从这两份状态推导，因此不用再创建一个手动维护的结果 ref。

## 2. computed 根据状态计算结果

```js
import { computed, ref } from 'vue'

const filteredKnowledgeBases = computed(() => {
  const keyword = searchQuery.value.trim().toLowerCase()
  if (!keyword) return knowledgeBases.value

  return knowledgeBases.value.filter(item =>
    [item.name, item.description, item.category].some(text =>
      text.toLowerCase().includes(keyword),
    ),
  )
})
```

**作用：** 得到“当前应该显示哪些知识库”。

**如何实现：** `computed` 接收一个返回计算结果的函数，Vue 跟踪函数执行时读取的响应式数据。相关依赖变化后，结果失效，下次读取时重新计算；依赖未变时可复用缓存结果。

这里关键词会变化，完整列表也可能因新增记录而变化。模板使用计算结果，因此这两类变化都会反映在页面上。无需在输入事件和创建函数里分别再写一次筛选逻辑。

脚本中读取计算值使用 `filteredKnowledgeBases.value`，模板中自动解包，直接用 `filteredKnowledgeBases`。

这个 computed 只有读取函数，不能直接给它的 `.value` 赋值。计算函数应只计算并返回结果，不在其中修改原始数组、打开弹窗或发送请求。

## 3. 把筛选表达式拆开理解

### 规范化关键词

```js
const keyword = searchQuery.value.trim().toLowerCase()
```

`trim()` 去掉两端空白，`toLowerCase()` 转成小写。因此输入 ` Vue ` 可以匹配名称中的 `Vue`。这些方法返回新字符串，不会把输入框里的文字自动改写。

```js
if (!keyword) return knowledgeBases.value
```

关键词为空，或者全是空白时，显示完整列表。

### filter 筛记录，some 查字段，includes 查文字

```js
knowledgeBases.value.filter(item =>
  [item.name, item.description, item.category].some(text =>
    text.toLowerCase().includes(keyword),
  ),
)
```

- `filter` 遍历完整列表，保留判断为 true 的记录，返回新数组。
- 三个字段放进临时数组，`some` 检查至少一个字段是否匹配。
- 每个字段也转成小写，`includes` 检查其中是否包含关键词。

例如输入“报销”：第一条记录的名称不包含它，但描述包含“差旅报销政策”，因此保留这条记录。

这是连续字符串包含匹配，不是分词、模糊纠错或语义搜索；内部空格不会被自动拆成多个关键词。名称、描述和分类由当前数据模型保证为字符串，后续接 API 时需要确保返回格式符合约定。

`filter` 不删除原始数组的内容，但结果数组里的记录对象仍是原对象，不是深拷贝。关键词为空时直接返回原数组。因此不要通过计算结果修改业务数据；创建等操作仍修改完整列表。

## 4. v-for 改为展示计算结果

```vue
<KnowledgeBaseCard
  v-for="knowledgeBase in filteredKnowledgeBases"
  :key="knowledgeBase.id"
  :knowledge-base="knowledgeBase"
  @view-detail="openKnowledgeBaseDetail"
/>
```

**作用：** 保留第四课的卡片组件，只改变传给它们的记录范围。

**如何实现：** 原先遍历 `knowledgeBases`，现在遍历 `filteredKnowledgeBases`。符合条件的记录继续通过 Props 传给卡片，详情事件仍由父页面处理。

```vue
显示 {{ filteredKnowledgeBases.length }} / {{ knowledgeBases.length }} 个知识库
```

前一个数字是筛选后的数量，后一个是完整列表数量。例如“显示 1 / 3”表示有三条记录，其中一条匹配。原来的“全部知识库”标题继续显示总数。

## 5. 空结果与清空

```vue
<div v-if="filteredKnowledgeBases.length" class="knowledge-grid">
  <!-- 卡片列表 -->
</div>
<div v-else class="empty-panel">
  <h2>没有匹配的知识库</h2>
  <p>试试其他关键词，或清空搜索查看全部知识库。</p>
</div>
```

**作用：** 结果为零时明确说明原因，避免用户以为页面坏了。

**如何实现：** 数组长度为 0 时条件不成立，Vue 渲染 `v-else` 分支。记录只是没有被展示，没有被删除。

```vue
<button type="button" :disabled="!searchQuery" @click="searchQuery = ''">清空</button>
```

点击清空只修改关键词，computed 随之返回完整列表。关键词原本为空时按钮禁用。全空格输入仍可以清空，虽然它已经按空关键词显示全部记录。

## 6. 为什么不用函数或 watch 手动同步

| 方式 | 适合做什么 | 本课中的含义 |
| --- | --- | --- |
| ref | 保存独立状态 | 输入词、完整列表 |
| computed | 从状态推导展示结果 | 筛选后的列表 |
| 普通函数 | 执行某次操作或按调用计算 | 打开详情、新建记录 |
| watch | 观察变化并执行副作用 | 后续再结合具体需求学习 |

普通函数也能计算结果，但 computed 会跟踪依赖并缓存结果，更清楚地表达“这是从已有状态得到的数据”。没有必要用 watch 把筛选结果复制到另一个 ref，再手动保持两者一致。

## 7. 本课里的缓存与第三课的缓存不同

- computed 缓存的是一次计算结果，相关依赖变化后会失效。
- KeepAlive 缓存的是页面组件实例，所以切页回来时搜索词与新增记录都能保留。
- 两者都在内存中，都不是持久化。刷新页面后搜索词和临时新增记录会重置。

## 创建与搜索一起使用时

搜索词不会因创建操作被清空。新记录匹配当前词时立即出现；不匹配时仍已加入完整列表，但被筛选隐藏。成功提示会说明“当前列表仍按搜索词筛选”，清空即可看到全部记录。

这体现了 computed 同时依赖关键词与列表：即使输入没有变化，新增记录也会让计算结果更新。

## 执行顺序

输入关键词 → v-model 更新 searchQuery → computed 的相关依赖变化 → 模板读取筛选结果 → filter 选择匹配记录 → v-for 更新卡片与数量。

## 小练习

1. 搜索“报销”，确认通过描述找到公司制度知识库；清空后恢复全部。
2. 输入“vue”，再创建“Vue 学习知识库”，观察没有改搜索词也会自动出现结果。
3. 输入不存在的关键词，确认显示空结果，再清空，理解搜索没有删除数据。

## 下一步

引入 Pinia，让知识库页面和工作台共用一份状态，工作台显示随新建操作更新的知识库数量。届时解释页面缓存和共享状态分别解决什么问题。
