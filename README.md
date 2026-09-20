# 企业 AI 知识管理平台

从零逐步实现 Vue 3 + Spring Boot 企业知识管理与 RAG 问答系统。

当前已完成第 6 课：Vue 3 + JavaScript + Vite 前端，包含四个页面路由、Mock Data 知识库列表、新建表单和只读详情，卡片已拆为独立组件，支持按名称、描述和分类即时搜索。Pinia 共享知识库数据，工作台显示同步更新的总数。切换导航会保留知识库页面状态；文档管理和 AI 问答为说明页。新增数据仅在内存中保存，刷新后恢复初始数据。尚未接入后端或数据库。

## 启动前端

```bash
cd frontend
npm install
npm run dev
```

打开终端显示的本地地址。`npm run build` 检查并生成生产构建。

## 目录

- `frontend/`：Vue 前端代码。
- `backend/`：后续 Spring Boot 后端。
- `docker/`：后续容器与部署配置。
- `docs/`：学习手册、课程与进度记录。

## 学习资料

- [完整学习手册](docs/learning-manual.md)
- [学习进度与教学偏好](docs/learning-progress.md)
- [第 1 课：让数据变成知识库页面](docs/lesson-01.md)

- [第 2 课：新建知识库，让页面响应操作](docs/lesson-02.md)

- [第 3 课：Vue Router，让地址决定页面](docs/lesson-03.md)

- [第 4 课：用 Props 和 Emit 让组件协作](docs/lesson-04.md)

- [第 5 课：用 computed 计算搜索结果](docs/lesson-05.md)

- [第 6 课：Pinia，让多个页面共享状态](docs/lesson-06.md)

下一小节模拟异步加载，学习 Promise 与 async/await。后续按照手册推进路由、Spring Boot、MySQL、登录与权限、文档管理、LLM 问答与 RAG、测试及部署。

## Git 约定

每次完成一批改动后，验证、提交并推送一次。使用已有 Git 身份，不强制推送。

远程仓库：`git@github.com:chaofengming123/-ai-.git`。
