# 第 2 课：新建知识库，让页面响应操作

## 本节目标

点击“新建知识库”，填写名称和描述，创建后立即看到新卡片，列表总数随之增加。

本节仍使用 Mock Data，没有发送后端请求。新增数据只存在当前页面的内存里，刷新会恢复初始示例。用户上一课修改的“公司制度知识库”保留在初始数据文件中。

## 先操作一次

沿用第 1 课的启动方式，在项目根目录运行：

```bash
cd frontend
npm run dev
```

打开终端显示的地址。如果已有开发服务运行，直接打开原来的页面即可。

点击“新建知识库”，填写“产品设计知识库”，描述填“整理产品需求与设计规范。”，点击“创建”。你会看到新卡片、0 份文档和更新后的知识库总数。

本节关键逻辑集中在 `frontend/src/views/KnowledgeBaseView.vue`，样式位于 `frontend/src/style.css`。

## 1. ref：让 Vue 跟踪数据变化

```js
import { ref } from 'vue'
import { knowledgeBases as initialKnowledgeBases } from '../data/knowledgeBases.js'

const knowledgeBases = ref(initialKnowledgeBases.map(item => ({ ...item })))
const name = ref('')
const description = ref('')
const error = ref('')
const notice = ref('')
```

**作用：** 保存页面会变化的数据，并让 Vue 在数据变化后更新相关显示。这类数据叫“响应式状态”。

**如何实现：** `ref(...)` 返回一个装着数据的对象。在 JavaScript 中用 `.value` 读写它，例如 `name.value = '产品设计知识库'`。模板通常会自动取出值，因此直接写 `{{ name }}`，不需要 `.value`。

`as initialKnowledgeBases` 给导入的数组换一个本地名称，避免与响应式列表重名。`map` 创建新数组，`{ ...item }` 复制每个对象的第一层字段；这里字段都是简单值，因此不会因编辑当前列表而修改导入的示例对象。它不是通用的深拷贝。

`const` 表示变量绑定不重新赋值，并不禁止修改其中的 `.value` 或数组内容。

## 2. @click 和模板 ref：点击后打开弹窗

```vue
<button type="button" @click="openCreateDialog">+ 新建知识库</button>
<dialog ref="createDialog" ...>
```

上面的 `...` 省略了标题关联等属性，仅用于讲解，实际文件中没有省略符。

```js
const createDialog = ref(null)

function openCreateDialog() {
  name.value = ''
  description.value = ''
  error.value = ''
  notice.value = ''
  createDialog.value.showModal()
}
```

**作用：** 将按钮点击连接到 JavaScript 函数，每次打开时清空旧输入和提示。

**如何实现：** `@click` 是 Vue 的点击事件绑定。页面挂载后，模板中的 `ref="createDialog"` 会把真实的 `<dialog>` 元素放进 `createDialog.value`。调用该元素的 `showModal()` 即可打开浏览器原生模态弹窗；浏览器负责背景不可操作和弹窗内的键盘焦点管理。名称输入框上的 `autofocus` 使打开后可以直接输入。

注意两种用法：`ref('')` 保存输入数据，绑定到 `<dialog>` 的模板 ref 保存页面元素。两者都使用 Vue 的 `ref`，保存的东西不同。

## 3. v-model：输入框和变量保持同步

```vue
<input id="kb-name" v-model="name" type="text" maxlength="60" required autofocus />
<textarea id="kb-description" v-model="description" rows="3" maxlength="300" />
```

**作用：** 获取用户输入，同时允许代码清空输入框。

**如何实现：** 在普通文本输入框中，`v-model` 将变量绑定到输入框的值，并监听输入事件。用户输入时变量更新；代码改变变量时输入框也更新。这叫“双向绑定”。

名称限制 60 个字符，描述限制 300 个字符。名称必填、描述选填。长度限制采用浏览器和 JavaScript 的字符串计数规则，部分 emoji 可能占多个计数单位。

## 4. @submit.prevent：接管表单提交

```vue
<form novalidate @submit.prevent="createKnowledgeBase">
  <!-- 输入框和按钮 -->
  <button type="submit">创建</button>
</form>
```

**作用：** 点击“创建”或在名称输入框按 Enter 时运行创建函数，保持当前页面不刷新。

**如何实现：** `submit` 是表单提交事件，`.prevent` 阻止浏览器默认提交行为。`novalidate` 关闭浏览器默认校验弹泡，让我们用下面的代码统一显示中文错误；它不表示可以省略校验。

描述是多行文本框，在里面按 Enter 会换行。

## 5. trim、if、return 和 some：先检查，再创建

```js
const trimmedName = name.value.trim()
const trimmedDescription = description.value.trim()

if (!trimmedName) {
  error.value = '请输入知识库名称，不能只填写空格。'
  return
}
if (trimmedName.length > 60 || trimmedDescription.length > 300) {
  error.value = '名称最多 60 个字符，描述最多 300 个字符。'
  return
}
if (knowledgeBases.value.some(item => item.name === trimmedName)) {
  error.value = '这个名称已经存在，请换一个名称。'
  return
}
```

**作用：** 拦截空名称、超长内容和重复名称。

**如何实现：** `trim()` 去掉文字两端的空白；全是空格的输入会变成空字符串。`if` 判断条件，`!trimmedName` 检查是否为空。`||` 表示两项任一成立就进入错误处理。`some` 检查数组中是否至少有一项名称相同，`===` 表示严格相等。每个 `return` 都立即结束函数，后面的新增代码不会执行。

这里名称按去掉两端空白后的完整文本比较，英文大小写不同仍视作不同名称。前端校验改善使用体验；以后接后端时，服务器也要自行校验。

## 6. push 和 v-for：新增数据，更新卡片

```js
knowledgeBases.value.push({
  id: crypto.randomUUID(),
  name: trimmedName,
  description: trimmedDescription || '暂无描述',
  documentCount: 0,
  category: '自建知识库',
})
createDialog.value.close()
notice.value = `已创建“${trimmedName}”。刷新页面后将恢复示例数据。`
```

**作用：** 添加一条知识库记录，关闭弹窗，显示成功提示。

**如何实现：** `push` 把对象追加到数组末尾。`crypto.randomUUID()` 生成一个字符串编号，用于识别新卡片；本地 localhost 开发环境可使用它，正式部署需要安全上下文，例如 HTTPS。描述为空时，`|| '暂无描述'` 提供默认文字。新知识库还没有文档，因此数量为 0。

数组在 `ref` 中，Vue 能跟踪这次新增，并安排页面更新：

```vue
<h2>全部知识库 <span>{{ knowledgeBases.length }}</span></h2>
<article v-for="knowledgeBase in knowledgeBases" :key="knowledgeBase.id">
  <h3>{{ knowledgeBase.name }}</h3>
</article>
```

`.length` 得到数组条数；`v-for` 为每条数据生成卡片；`:key` 用稳定且唯一的编号帮助 Vue 识别条目。我们只修改数据，不需要手动插入 HTML。

## 7. v-if、取消和 Esc：控制提示与关闭

```vue
<p v-if="error" role="alert">{{ error }}</p>
<p v-if="notice" role="status">{{ notice }}</p>
<button type="button" @click="createDialog.close()">取消</button>
```

**作用：** 只在有内容时显示提示，并允许放弃创建。

**如何实现：** `v-if` 根据条件决定是否创建对应页面元素。错误文字使用 `role="alert"`，成功提示使用 `role="status"`，让辅助阅读工具能感知提示。

取消按钮使用 `type="button"`，避免触发表单提交；模板中 ref 自动解包，所以可以直接调用 `createDialog.close()`。按 Esc 也可以利用原生 dialog 行为关闭。关闭本身不修改数组，下次打开时输入会被清空。

## 完整执行顺序

点击新建 → 清空输入并打开弹窗 → 输入通过 v-model 写入状态 → 提交事件调用函数 → 去除两端空白并校验 → push 新记录 → Vue 更新列表和数量 → 关闭弹窗并显示提示。

刷新后会重新运行组件初始化，从初始示例数组创建列表，因此新增数据消失。后端保存或浏览器存储才可以让数据跨刷新保留，本节尚未实现。

## 小练习

先提交一个全是空格的名称，观察错误。再输入一个新名称，创建后确认卡片总数增加。尝试用相同名称再次创建，观察为什么代码会在 `return` 处停止。

重点能够解释：为什么修改数组就能更新页面？因为数组是 Vue 跟踪的响应式状态，模板又使用了它。

## 下一节

加入 Vue Router，让侧边导航真正切换页面，理解页面地址与组件的对应关系。
