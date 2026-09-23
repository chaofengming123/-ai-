# 企业 AI 知识管理平台

从零逐步实现 Vue 3 + Spring Boot 企业知识管理与 RAG 问答系统。

当前第 29 课：已实现知识库 CRUD、登录权限、文档上传与解析、分块、流式聊天、向量实验、文档索引与单文档 RAG 问答。MySQL 保存元数据与索引状态，MinIO 保存原文件，Qdrant 保存向量；文档回答使用 GLM 并展示引用原文。

## 启动前端

如果 Windows 要与 Mac 使用同一批账号、文档和向量，参见 [两台电脑共用数据](docs/windows-shared-data.md)。该方式让 Windows 访问 Mac 运行的网页，无需在 Windows 另起一套数据库。

```bash
cd frontend
npm install
npm run dev
```

打开终端显示的本地地址。`npm run build` 检查并生成生产构建。

## 启动数据库和后端

需要 Docker Desktop 与 Java 17。先运行 `java -version`，确认当前终端使用的是 Java 17；修改过 `JAVA_HOME` 或 `PATH` 后需要关闭并重新打开终端。

### Windows PowerShell（推荐）

在项目根目录运行：

```powershell
python scripts/init-db-env.py
docker compose --env-file docker/.env -f docker/compose.yml up -d --build --wait
.\scripts\backend.ps1 spring-boot:run
```

`backend.ps1` 会读取 `docker/.env`、检查 Java 版本并调用 Windows Maven Wrapper。不要在 PowerShell 中直接运行 `scripts/backend.sh`；它是给 Unix Shell 使用的脚本。首次运行 Maven Wrapper 会下载 Maven，可能需要几分钟。

### macOS、Linux 或 Git Bash

在项目根目录运行：

```bash
python3 scripts/init-db-env.py
docker compose --env-file docker/.env -f docker/compose.yml up -d --build --wait
./scripts/backend.sh spring-boot:run
```

如果 Git Bash 首次运行出现 `CRYPT_E_REVOCATION_OFFLINE` 或 `curl: Failed to fetch`，请改用上面的 Windows PowerShell 方式，避免为 Git 或 curl 全局关闭证书吊销检查。

### Windows IDEA 启动

命令行和 IDEA 是两种独立的启动方式，不必同时运行，也不需要只能点击运行按钮。

1. 先在项目根目录运行上面的初始化和 Docker Compose 命令，确保 Docker Desktop 已启动。
2. 在 IDEA 打开项目，将 `backend/pom.xml` 添加为 Maven 项目并重新加载。项目 SDK、Maven 导入器 JDK、运行配置 JRE 均选择 Java 17。本机 JDK 路径为 `D:\Java JDK\17`。
3. 在“运行 → 编辑配置”中选择或新建 Application 配置 `AiKnowledgeApplication`。主类填写 `com.example.aiknowledge.AiKnowledgeApplication`，模块选择 `ai-knowledge-backend`。
4. 工作目录设为本项目的 `backend` 目录。本机为 `D:\知识库系统\-ai-\backend`；不要使用旧路径 `D:\知识库系统-ai-`。
5. 在“修改选项”中显示 VM options，填入下面这一行，让 IDEA 读取本地配置文件（不是 Program arguments）：

```text
-Dspring.config.import=file:../docker/.env[.properties]
```

6. 移除运行配置中重复且过期的 `LLM_*` 环境变量，避免它们覆盖文件里的新值。点击运行，看到 `Started AiKnowledgeApplication` 后访问 `http://localhost:8080/api/health`。返回正常只代表后端存活，不代表数据库和模型都已验证。

本机此前打开的是父目录 `D:\知识库系统`，已在其 `.idea` 中配置 Maven 项目和上述运行项；如 IDEA 未显示，请重新打开项目或重新加载 Maven。更换电脑后按实际路径重新设置。

### Windows 常见启动问题

- `java.exe : openjdk version ... NativeCommandError`：这是 Windows PowerShell 5.1 把 Java 正常输出到错误流的版本信息当成错误。项目脚本已兼容此情况，使用更新后的 `scripts/backend.ps1`，不需要重装 Java。
- 禁止运行脚本：可仅对这次启动执行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\backend.ps1 spring-boot:run`，不修改全局执行策略。
- Java 版本不对：关闭并重开终端和 IDEA。需要临时指定本机 JDK 时，在当前 PowerShell 中执行下面两行，然后再启动后端。

```powershell
$env:JAVA_HOME = 'D:\Java JDK\17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\scripts\backend.ps1 spring-boot:run
```

- `8080 already in use`：先停止另一份后端。命令行使用 `Ctrl+C`，IDEA 使用停止按钮，再选择一种方式启动。
- 仅在出现 `Unable to establish loopback connection` 等本地套接字错误时，可尝试给 IDEA VM options 加上 `-Djdk.net.unixdomain.tmpdir=C:/codex-no-unix-sockets`。该路径须不存在，让 JDK 回退到 TCP；不是普通启动的必填项。
- 修改 `docker/.env` 后必须停止并重新启动后端，仅刷新网页不会重新加载配置。

### GLM 配置与排查

编辑已有 `docker/.env`，添加或更新下面这些键；不要覆盖原来的数据库、JWT、MinIO 配置，也不要重复添加同名键：

```dotenv
LLM_ENDPOINT=https://open.bigmodel.cn/api/paas/v4/chat/completions
LLM_API_KEY=替换为自己的真实密钥
LLM_MODEL=glm-4.7-flash
LLM_TOKEN_PARAMETER=max_tokens
LLM_THINKING=disabled
```

PowerShell 启动脚本会载入此文件；IDEA 需按上文设置 `spring.config.import`。模型配置读取现已兼容成对的单引号或双引号。旧版 Windows 脚本和 IDEA properties 导入会保留引号，导致明明填了密钥仍显示“模型尚未配置”；更新代码并重启即可应用修复，无须因此重新申请密钥。

登录后打开 AI 对话页。在浏览器开发者工具的网络面板检查 `/api/chat/config`：`configured: true` 表示本地配置格式通过检查，并不证明密钥有效。发送一次简单问题，收到模型回答才是完整验收。

- “模型尚未配置”：检查启动方式是否读取了 `.env`、密钥是否为空、是否已重启以及是否连接到了旧后端。
- “模型服务拒绝访问”：检查密钥和模型访问权限。
- “限流或额度不足”：检查服务商账户用量。
- “无法取得模型回复”或超时：检查网络、接口地址和服务状态。

不要把密钥放在前端、提交到 Git 或粘贴到报错截图中。

### Windows 与 Mac 的账号

两台电脑各自运行本地 MySQL 时，数据库和账号不会自动同步，因此 Mac 注册的账号默认不能在 Windows 登录。可在 Windows 重新注册测试账号；若要共用数据，需要另行配置同一个受保护的数据库服务，或安全迁移数据库备份。不要直接向公网开放本机 MySQL。

密码保存在被 Git 忽略的 `docker/.env`，不要删除或提交它。MySQL 监听本机 3307，后端 8080；前端开发代理转发 `/api` 请求到后端。命名卷保存数据库，勿使用 `down -v` 删除数据。

`GET /api/health` 仍是基础服务存活检查，不检查数据库健康。

只启动一份后端，避免与 IDEA 抢占 8080。更新代码后需要重启后端，以应用新增迁移和接口。文档入口为 `/documents`，支持单个最多 1 MB 的 TXT / Markdown / PDF / DOCX 文件及下载。文本需为 UTF-8，PDF 需未加密且不超过 500 页；DOCX 的结构及解压限制见第二十课。MinIO API 为本机 9000，控制台为 9001；首次从固定源码版本构建可能需要数分钟。IDEA 按上文导入配置文件后即可读取其中的 MinIO 凭据。

旧文件默认位于 `backend/uploads/documents`，可用 `DOCUMENT_STORAGE_DIR` 指定原目录绝对路径。运行 `scripts/migrate-documents.sh preview` 预览，再用 `scripts/migrate-documents.sh apply` 迁移；复制校验后切换记录，保留本地备份。原文件与密钥不提交到 Git。含文档的知识库暂不允许删除。MinIO 社区发行状态、构建及详细配置见第十九课。

Windows PowerShell 运行 `.\scripts\backend.ps1 test`，macOS、Linux 或 Git Bash 运行 `./scripts/backend.sh test`，使用独立 MySQL 测试库。MinIO 测试使用随机私有桶并在结束后清理，需先启动两个容器。前端 `npm test` 验证共享状态和错误处理。

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

- [第 21 课：基础 LLM Chat，接入 GLM-4.7-Flash](docs/lesson-21.md)

AI 问答已接入后端非流式模型接口，默认使用智谱免费模型 `glm-4.7-flash` 普通对话模式。将智谱 API Key 填入本地 `docker/.env` 的 `LLM_API_KEY`，然后重启后端并重新登录即可试用；完整配置示例见 `docker/.env.llm.example` 与第二十一课。如果之前配置过其他服务商，还需替换旧的 `LLM_ENDPOINT`、`LLM_MODEL` 和密钥。密钥不能写进前端或提交到 Git。未配置时页面显示提示；当前对话不读取知识库文件，离开页面后清空记录。模型自动测试使用本地模拟服务，真实账户需配置后验收。

- [第 22 课：SSE 流式输出，让回复逐步出现](docs/lesson-22.md)

第 22 课起，AI 问答默认使用 `/api/chat/stream` 流式接口，支持停止接收和未完成回复标记。完整回答才进入下一轮上下文；保留原非流式接口用于学习对照。更新后需重启后端，智谱配置继续沿用。

- [第 23 课：提取文档正文，开始 RAG 文档处理](docs/lesson-23.md)

文档管理新增“查看正文”：从原文件提取只读文字预览，最多 40000 字符，PDF 限前 20 页文本层。扫描图片不做 OCR，DOCX 仅提取主文档段落和表格文字；预览不会保存索引或进入 AI 问答。更新后重启后端即可使用，无新增数据库迁移。

- [第 24 课：文本分块，观察块大小与重叠](docs/lesson-24.md)

文档管理新增“分块预览”：可调整块上限与目标重叠，高亮相邻块重复的文字。单位为 UTF-16 字符，不是 token；继续沿用正文预览范围并显示截断标记。可上传 [练习材料](docs/samples/chunking-demo.md) 比较参数效果。本课不保存分块或向量，更新后需重启后端。

- [第 25 课：Embedding，用硅基流动生成向量并比较文字](docs/lesson-25.md)

侧栏新增“向量实验”，默认使用硅基流动免费模型 `BAAI/bge-m3`。按 `docker/.env.embedding.example` 填写独立的 Embedding 密钥并重启后端；无需下载本地模型。页面比较问题与三段文字的余弦相似度，尚不保存向量。后续可以通过独立的地址、模型和密钥配置接入格式兼容的收费服务。

- [第 26 课：Qdrant，让向量保存下来并支持检索](docs/lesson-26.md)

向量实验新增“保存后检索”，每个账号有独立的练习集合。运行 `docker compose -f docker/compose.yml up -d qdrant` 并重启后端，沿用硅基流动 Embedding 配置；保存候选文字后，刷新并重新登录仍能检索。文字与向量存入 Docker 命名卷，尚未自动索引上传文件。

- [第 27 课：为文档建立索引](docs/lesson-27.md)

文档管理新增“文档索引”：编辑者和管理员可手动建立正文索引，普通用户查看状态。重启后端应用 V8 并重新登录；沿用硅基流动配置与 Qdrant。限正文 4000 字符、PDF 50 页，超限明确拒绝，重建失败保留旧成功版本。练习文件见 `docs/samples/indexing-demo.md`。

- [第 28 课：检索已发布的文档片段](docs/lesson-28.md)

文档索引面板新增问题检索，返回最多三个原文片段、来源和相似度。只查询该文档已发布且模型兼容的集合；需要同时拥有文档读取与 AI 对话权限。无新增迁移，重启后端即可使用，已有成功索引可继续沿用。

- [第 29 课：RAG 文档问答](docs/lesson-29.md)

文档索引面板新增“基于文档回答”，重新检索最多三个片段后交给 GLM，并校验引用编号。需要同时配置硅基流动 Embedding 与智谱 LLM 的独立密钥；无新增迁移，重启后端即可沿用已有索引。资料不足时明确提示，答案附原文供核对。

## Git 约定

每次完成一批改动后，验证、提交并推送一次。使用已有 Git 身份，不强制推送。

远程仓库：`git@github.com:chaofengming123/-ai-.git`。
