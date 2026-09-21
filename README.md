# 企业 AI 知识管理平台

从零逐步实现 Vue 3 + Spring Boot 企业知识管理与 RAG 问答系统。

当前第 11 课将知识库迁入 MySQL：Vue → Controller → Service → Mapper → MySQL。支持列表、详情、创建和搜索；数据库数据卷保留记录。尚未实现登录、权限、文档处理或 AI。

## 启动前端

```bash
cd frontend
npm install
npm run dev
```

打开终端显示的本地地址。`npm run build` 检查并生成生产构建。

## 启动数据库和后端（第 11 课）

需要 Docker Desktop 与 Java 17。在项目根目录运行：

```bash
python3 scripts/init-db-env.py
docker compose --env-file docker/.env -f docker/compose.yml up -d --wait
scripts/backend.sh spring-boot:run
```

密码保存在被 Git 忽略的 `docker/.env`，不要删除或提交它。MySQL 监听本机 3307，后端 8080；前端开发代理转发 `/api` 请求到后端。命名卷保存数据库，勿使用 `down -v` 删除数据。

`GET /api/health` 仍是基础服务存活检查，不检查数据库健康。

运行 `scripts/backend.sh test` 使用独立 MySQL 测试库。前端 `npm test` 验证共享状态和错误处理。

## 目录

- `frontend/`：Vue 前端代码。
- `backend/`：Spring Boot + MyBatis-Plus + MySQL 后端。
- `docker/`：MySQL 开发容器与测试库初始化。
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

- [第 11 课：MySQL 持久化与 Mapper](docs/lesson-11.md)

- [第 12 课：修改与删除，完成知识库 CRUD](docs/lesson-12.md)

- [第 13 课：创建用户与密码哈希](docs/lesson-13.md)

账号创建入口为 /register。下一小节实现登录验证与登录状态。后续按照手册推进路由、Spring Boot、MySQL、登录与权限、文档管理、LLM 问答与 RAG、测试及部署。

## Git 约定

每次完成一批改动后，验证、提交并推送一次。使用已有 Git 身份，不强制推送。

远程仓库：`git@github.com:chaofengming123/-ai-.git`。
