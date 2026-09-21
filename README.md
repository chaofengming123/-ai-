# 企业 AI 知识管理平台

从零逐步实现 Vue 3 + Spring Boot 企业知识管理与 RAG 问答系统。

当前第 18 课：已实现知识库 CRUD、登录、角色权限和 TXT / Markdown 文档上传。MySQL 保存账号、知识库和文件元数据，原文件暂存后端本地目录。尚未接入 MinIO、文档解析或 AI 问答。

## 启动前端

```bash
cd frontend
npm install
npm run dev
```

打开终端显示的本地地址。`npm run build` 检查并生成生产构建。

## 启动数据库和后端

需要 Docker Desktop 与 Java 17。在项目根目录运行：

```bash
python3 scripts/init-db-env.py
docker compose --env-file docker/.env -f docker/compose.yml up -d --wait
scripts/backend.sh spring-boot:run
```

密码保存在被 Git 忽略的 `docker/.env`，不要删除或提交它。MySQL 监听本机 3307，后端 8080；前端开发代理转发 `/api` 请求到后端。命名卷保存数据库，勿使用 `down -v` 删除数据。

`GET /api/health` 仍是基础服务存活检查，不检查数据库健康。

只启动一份后端，避免与 IDEA 抢占 8080。更新代码后需要重启后端，以应用新增迁移和接口。第十八课文档入口为 `/documents`，支持单个最多 1 MB 的 UTF-8 `.txt` / `.md` 文件。默认从 backend 目录启动时原文件保存在 `backend/uploads/documents`；可用 `DOCUMENT_STORAGE_DIR` 指定固定绝对路径。原文件不提交到 Git。含文档的知识库暂不允许删除。

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

- [第 14 课：登录验证与 JWT 登录状态](docs/lesson-14.md)

账号创建入口为 /register，登录入口为 /login。升级后先运行初始化脚本补充 JWT_SECRET，再启动后端；已有凭据保留。登录状态仅在内存保存，刷新需要重新登录。第十五课已保护知识库全部增删改查接口；未登录访问业务页面会跳转到登录页。知识库仍为共享数据；第十六课起普通用户只读，管理员可管理。第十七课迁移保留已有角色；新账号默认 USER，支持 EDITOR 和多角色，角色设置脚本的当前用法见第十七课。后续按照手册推进路由、Spring Boot、MySQL、登录与权限、文档管理、LLM 问答与 RAG、测试及部署。

- [第 15 课：保护知识库接口与前端路由](docs/lesson-15.md)

- [第 16 课：普通用户与管理员，开始角色授权](docs/lesson-16.md)

- [第 17 课：角色与权限关系表（RBAC）](docs/lesson-17.md)

- [第 18 课：第一个文档上传闭环](docs/lesson-18.md)

## Git 约定

每次完成一批改动后，验证、提交并推送一次。使用已有 Git 身份，不强制推送。

远程仓库：`git@github.com:chaofengming123/-ai-.git`。
