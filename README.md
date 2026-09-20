# 企业 AI 知识管理平台

从零逐步实现 Vue 3 + Spring Boot 企业知识管理与 RAG 问答系统。

当前已完成第 10 课：Vue 通过 Axios 和 Vite 开发代理读取、创建 Spring Boot 知识库记录。支持搜索、详情、新建与工作台统计。记录在后端内存中，刷新前端不会清空，重启后端后恢复示例；尚未连接数据库、登录或 AI。

## 启动前端

```bash
cd frontend
npm install
npm run dev
```

打开终端显示的本地地址。`npm run build` 检查并生成生产构建。

## 启动后端（第 9 课）

需要 Java 17，使用工程自带的 Maven Wrapper，无需全局 Maven。

```bash
cd backend
./mvnw spring-boot:run
```

访问 `http://127.0.0.1:8080/api/health`，返回服务状态 JSON。知识库 API 支持 GET `/api/knowledge-bases`、GET `/api/knowledge-bases/{id}` 与 POST `/api/knowledge-bases`，数据暂存后端内存，重启后重置。`./mvnw test` 运行接口测试。首次运行需联网下载构建工具和依赖。前后端需同时运行；开发代理将前端 `/api` 请求转发到本机 8080。

## 目录

- `frontend/`：Vue 前端代码。
- `backend/`：Spring Boot 后端，已提供健康检查和内存知识库接口。
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

- [第 7 课：异步加载、失败与重试](docs/lesson-07.md)

- [第 8 课：Spring Boot，第一个真实 HTTP 接口](docs/lesson-08.md)

- [第 9 课：知识库 API 与 Controller / Service 分工](docs/lesson-09.md)

- [第 10 课：Vue 通过 Axios 连接真实后端](docs/lesson-10.md)

下一小节接入 MySQL 持久化。后续按照手册推进路由、Spring Boot、MySQL、登录与权限、文档管理、LLM 问答与 RAG、测试及部署。

## Git 约定

每次完成一批改动后，验证、提交并推送一次。使用已有 Git 身份，不强制推送。

远程仓库：`git@github.com:chaofengming123/-ai-.git`。
