# 企业 AI 知识管理平台

从零逐步实现 Vue 3 + Spring Boot 企业知识管理与 RAG 问答系统。

当前第 20 课：已实现知识库 CRUD、登录、角色权限、TXT / Markdown / PDF / DOCX 上传和原文件下载。MySQL 保存元数据，新文件进入 MinIO；支持保留本地副本的旧文件迁移。PDF / DOCX 在保存前执行格式检查，尚未实现正文提取或 AI 问答。

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
docker compose --env-file docker/.env -f docker/compose.yml up -d --build --wait
scripts/backend.sh spring-boot:run
```

密码保存在被 Git 忽略的 `docker/.env`，不要删除或提交它。MySQL 监听本机 3307，后端 8080；前端开发代理转发 `/api` 请求到后端。命名卷保存数据库，勿使用 `down -v` 删除数据。

`GET /api/health` 仍是基础服务存活检查，不检查数据库健康。

只启动一份后端，避免与 IDEA 抢占 8080。更新代码后需要重启后端，以应用新增迁移和接口。文档入口为 `/documents`，支持单个最多 1 MB 的 TXT / Markdown / PDF / DOCX 文件及下载。文本需为 UTF-8，PDF 需未加密且不超过 500 页；DOCX 的结构及解压限制见第二十课。MinIO API 为本机 9000，控制台为 9001；首次从固定源码版本构建可能需要数分钟。IDEA 启动需补充 docker/.env 中新增的 MINIO_ROOT_USER / MINIO_ROOT_PASSWORD 环境变量。

旧文件默认位于 `backend/uploads/documents`，可用 `DOCUMENT_STORAGE_DIR` 指定原目录绝对路径。运行 `scripts/migrate-documents.sh preview` 预览，再用 `scripts/migrate-documents.sh apply` 迁移；复制校验后切换记录，保留本地备份。原文件与密钥不提交到 Git。含文档的知识库暂不允许删除。MinIO 社区发行状态、构建及详细配置见第十九课。

运行 `scripts/backend.sh test` 使用独立 MySQL 测试库，MinIO 测试使用随机私有桶并在结束后清理，需先启动两个容器。前端 `npm test` 验证共享状态和错误处理。

## 目录

- `frontend/`：Vue 前端代码。
- `backend/`：Spring Boot + MyBatis-Plus + MySQL 后端。
- `docker/`：MySQL 与 MinIO 开发容器、测试库初始化。
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

- [第 19 课：MinIO 对象存储与原文件下载](docs/lesson-19.md)

- [第 20 课：PDF、DOCX 上传与格式校验](docs/lesson-20.md)

- [第 21 课：基础 LLM Chat，接入 DeepSeek](docs/lesson-21.md)

AI 问答已接入后端非流式模型接口，默认使用 DeepSeek `deepseek-flash` 普通对话模式。将 `LLM_API_KEY` 加入本地 `docker/.env`，然后重启后端并重新登录即可试用；完整配置示例见 `docker/.env.llm.example` 与第二十一课。密钥不能写进前端或提交到 Git。未配置时页面显示提示；当前对话不读取知识库文件，离开页面后清空记录。模型自动测试使用本地模拟服务，真实账户需配置后验收。

## Git 约定

每次完成一批改动后，验证、提交并推送一次。使用已有 Git 身份，不强制推送。

远程仓库：`git@github.com:chaofengming123/-ai-.git`。
