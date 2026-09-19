# 第 1 课：让数据变成知识库页面

## 本节目标

运行 Vue 3 + JavaScript + Vite 前端，看到侧边导航和两张知识库卡片。本节不接数据库，卡片展示的是模拟数据。工作台、文档管理和 AI 问答会在后续课程开放。

## 运行

在项目根目录打开终端：

```bash
cd frontend
npm install
npm run dev
```

第一次或依赖有更新时才需要 `npm install`。打开终端显示的本地地址即可。保持终端运行；按 Ctrl+C 停止服务。

## 先理解这条流程

`index.html` 提供页面容器 → `main.js` 启动 Vue → `App.vue` 组合侧边栏和知识库页面 → 页面读取数组并显示卡片。

- Vue：把 JavaScript 数据显示成页面的工具。
- Vite：启动开发服务，保存代码后自动更新预览；也负责生成部署文件。
- 组件：一个可以独立维护的页面部分。这里侧边栏是 `AppSidebar.vue`，知识库页面是 `KnowledgeBaseView.vue`。
- Mock Data：暂时写在本地的示例数据。这里位于 `src/data/knowledgeBases.js`，以后会改成从后端读取。

## 只看一段关键代码

知识库页面里有：

```vue
<article v-for="knowledgeBase in knowledgeBases" :key="knowledgeBase.id">
```

`knowledgeBases` 是整个数组，`knowledgeBase` 是当前这一条知识库。`v-for` 表示“每条数据显示一次”。`:key` 用唯一编号帮助 Vue 识别每张卡片。

```vue
<h3>{{ knowledgeBase.name }}</h3>
```

双大括号把数据里的名称显示到页面上。因此，修改数据中的 `name` 就能改变卡片标题。

当前数据是普通数组。本节先学习读取与展示；按钮操作、响应式状态和用户输入在后续小节加入。

## 一个小练习

打开 `frontend/src/data/knowledgeBases.js`，把第一条数据的名称改成“公司制度知识库”，保存后观察浏览器。可以改回原名，或保留自己的名称，后续一并提交。

无需逐标签背代码，先确认：卡片的文字来自数据文件。

## 本节范围与下一步

卡片只展示信息，不支持打开文档或新增知识库。下一节加入“新建知识库”，通过按钮和表单学习响应式状态，让数组增加时页面也随之增加一张卡片。

## Git 约定

每次完成一批有意义的修改后，先验证，再提交并推送一次。本项目远程地址为 `git@github.com:chaofengming123/-ai-.git`，当前使用 `codex/initialize` 分支，不强制覆盖远程历史。
