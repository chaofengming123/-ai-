# 企业 AI 知识管理平台

Vue 3 + Spring Boot + MySQL 的团队知识空间，支持权限管理、文档上传与索引、知识库检索及统一 AI 问答。MinIO 保存原文件，Qdrant 保存向量，Redis 缓存问题向量。

## 功能与权限

| 功能 | 普通用户 USER | 编辑者 EDITOR | 管理员 ADMIN |
| --- | --- | --- | --- |
| 浏览知识库、预览和下载资料、AI 问答 | ✓ | ✓ | ✓ |
| 创建和编辑知识库、上传文档、建立索引 | — | ✓ | ✓ |
| 删除空知识库 | — | — | ✓ |
| 向量实验与索引调试入口 | — | ✓ | ✓ |

导航、页面和按钮按实际权限显示；后端独立校验业务权限。当前知识库仍为团队共享数据，没有按用户或部门划分私有知识库。向量实验是编辑者的辅助界面，不是新增的后端权限种类。

**统一 AI 问答**位于 `/chat`，文档管理不再提供另一套提问表单：

- 自动判断：检索已索引资料，用相似度和模型资料充分性判断选择知识库回答或普通 AI 回答；来源可展开核对。
- 仅知识库：资料不足时明确提示，不切换普通回答。
- 普通对话：不检索文档，支持最近五轮完整上下文。

自动判断可能误判，必须依据原文的问题请选择“仅知识库”。检索故障会报错，不会静默当成无资料。没有读取权限的账号只使用普通对话。知识库问题须独立完整，不承接上一轮的省略指代；切换范围会清空对话。提问不会修改知识库或自动上传、删除、重建文档。

当前最多同时检索 5 个知识库，每库最多 5 份兼容成功索引；超过时请缩小范围。自动/知识库问题最多 1000 字符，普通问题最多 2000 字符。问题、相关片段或普通对话上下文会发送给模型服务。

## 文档格式

支持 `.txt`、`.md`、`.pdf`、`.doc`、`.docx`、`.csv`、`.tsv`、`.json`、`.html`、`.htm`、`.rtf`，均可上传、下载、预览和建立索引。

- 每份最多 **5 MB**。文本、表格文本、JSON、HTML 使用 UTF-8；JSON 校验语法。
- DOC 支持 Word 97–2003 二进制格式，使用 [Apache POI HWPF](https://poi.apache.org/components/document/) 提取主文档文字；加密、含宏、损坏文件或仅改后缀的文件会被拒绝。Word 6/95 文件请先另存为 Word 97–2003 DOC 或 DOCX。
- DOCX 提取主文档段落和表格，RTF 提取文字；HTML 不执行脚本或加载外部资源。
- PDF 须未加密、最多 500 页；只读取文本层，不支持扫描件 OCR。
- 预览最多 40000 字符，PDF 预览前 20 页。索引最多 4000 字符、PDF 最多 50 页，超限需拆分；上传成功不代表可以完整索引。
- 不支持带宏 Office 文件、`.xlsx`、`.pptx`；请导出 DOCX、CSV 或 PDF，不能只改后缀。

## 环境和配置

需要 Docker Desktop（Windows 使用 Linux 容器）、Java 17、Node.js 22.12+ 或兼容的 24 LTS、Python 3。构建在宿主机执行，部署后不需要 IDEA 或 Vite 常驻。

在项目根目录初始化配置：Windows 使用 `python scripts/init-db-env.py`，macOS 使用 `python3 scripts/init-db-env.py`。已有密码会保留。

在 `docker/.env` 添加或更新以下值，不要覆盖原来的数据库、JWT、MinIO 配置，也不要提交此文件：

```dotenv
LLM_ENDPOINT=https://open.bigmodel.cn/api/paas/v4/chat/completions
LLM_MODEL=glm-4.7-flash
LLM_API_KEY=你的智谱密钥
LLM_TOKEN_PARAMETER=max_tokens
LLM_THINKING=disabled
EMBEDDING_ENDPOINT=https://api.siliconflow.cn/v1/embeddings
EMBEDDING_MODEL=BAAI/bge-m3
EMBEDDING_API_KEY=你的硅基流动密钥
RERANK_ENDPOINT=https://api.siliconflow.cn/v1/rerank
RERANK_MODEL=BAAI/bge-reranker-v2-m3
RERANK_API_KEY=你的硅基流动密钥
```

普通聊天需要 LLM；索引和知识库问答还需要 Embedding。Rerank 是现有高级检索接口的可选配置，统一问答默认使用向量检索。两家服务密钥不能互换；配置示例也见 `docker/.env.*.example`。

## Docker 完整部署

### Windows PowerShell

打开 Docker Desktop，在项目根目录执行，每一步成功再继续：

```powershell
cd "D:\知识库系统\-ai-"
java -version
docker version
python scripts/init-db-env.py
if ($LASTEXITCODE -ne 0) { throw '配置初始化失败' }
npm ci --prefix frontend
if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }
npm run build --prefix frontend
if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
.\scripts\backend.ps1 -DskipTests package
if ($LASTEXITCODE -ne 0) { throw '后端打包失败' }
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml config --quiet
if ($LASTEXITCODE -ne 0) { throw '部署配置无效' }
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml up -d --build --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) { throw '容器启动失败，请查看日志' }
python scripts/check-deployment.py
```

### macOS

打开 Docker Desktop，在项目根目录执行：

```bash
java -version
docker version
python3 scripts/init-db-env.py && \
npm ci --prefix frontend && \
npm run build --prefix frontend && \
./scripts/backend.sh -DskipTests package && \
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml config --quiet && \
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml up -d --build --wait --wait-timeout 180 && \
python3 scripts/check-deployment.py
```

成功后访问 **http://127.0.0.1:8088**。部署复用本机 `ai-knowledge` 工程的数据卷，不要随意更改工程名。入口只监听本机，没有配置公网 HTTPS。

`-DskipTests` 跳过测试运行，不代表测试通过。检查脚本只读检查页面、静态资源、路由与 API，不调用模型。首次使用请登录，上传小文档并建立索引，分别验证知识库问题与普通问题。

### 日常启停、更新与日志（两端相同）

```bash
# 日常启动
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml up -d --wait
# 查看状态
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml ps
# 查看日志
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml logs --tail 80 backend web
# 停止，保留数据
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml stop
```

更新代码后重复对应系统的构建部署步骤。更新前等待索引任务完成。只执行 `restart` 不会更新镜像或环境变量。不要执行 `down -v`，它会删除数据卷。端口冲突可在 `.env` 设置 `APP_PORT=8089`，重新创建容器，并用新地址执行检查脚本。

## 本地开发

开发入口为前端终端显示的地址（通常 5173），后端为 8080；与 Docker 部署的 8088 入口分开。日常推荐选择一种入口。

Windows PowerShell，项目根目录：

```powershell
docker compose --env-file docker/.env -f docker/compose.yml up -d --build --wait
.\scripts\backend.ps1 spring-boot:run
# 另开终端
npm ci --prefix frontend
npm run dev --prefix frontend
```

macOS，项目根目录：

```bash
docker compose --env-file docker/.env -f docker/compose.yml up -d --build --wait
./scripts/backend.sh spring-boot:run
# 另开终端
npm ci --prefix frontend
npm run dev --prefix frontend
```

IDEA：将 `backend/pom.xml` 导入 Maven，项目 SDK、Maven JDK 和运行 JRE 都选 Java 17。主类为 `com.example.aiknowledge.AiKnowledgeApplication`，模块为 `ai-knowledge-backend`，工作目录为 `backend`，VM options 添加：

```text
-Dspring.config.import=file:../docker/.env[.properties]
```

两端均需移除 IDEA 中覆盖文件的旧模型环境变量。修改 `.env` 后重启后端。Windows 使用 `backend.ps1`，macOS 使用 `backend.sh`。

## 账号和管理员

在 `/register` 注册，再登录。新账号为 USER。把 `learner` 替换为已注册用户名：

```powershell
# Windows：预览，再应用
python scripts/set-user-role.py --username learner --role ADMIN
python scripts/set-user-role.py --username learner --role ADMIN --apply
```

```bash
# macOS：预览，再应用
python3 scripts/set-user-role.py --username learner --role ADMIN
python3 scripts/set-user-role.py --username learner --role ADMIN --apply
```

编辑者使用 `EDITOR`，恢复普通账号使用 `USER`。脚本替换全部角色、不修改密码，需要 MySQL 容器运行。变更后重新登录刷新前端；后端按最新权限授权。

Windows 与 Mac 独立部署不会自动同步账号、原文件或索引。共用 Mac 数据见 [跨电脑访问说明](docs/windows-shared-data.md)。

## 常见问题与测试

- 找不到 `docker`：确认 Docker Desktop 已安装且 CLI 加入 PATH，重开终端；连接失败时确认引擎已启动。
- Java 版本错误：配置 `JAVA_HOME` 和 PATH。Windows 本机 JDK 为 `D:\Java JDK\17`；macOS 可用 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`。
- PowerShell 禁止脚本：可单次运行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\backend.ps1 spring-boot:run`。脚本兼容 PowerShell 5.1 Java 版本输出。
- 模型未配置：确认读取正确 `.env` 并重启。Docker 用 `up -d` 重新创建环境有变化的容器；密钥权限、账户额度和网络错误分别排查。
- 知识库没有回答：确认有成功索引、模型空间兼容、检索范围正确。自动模式会标记普通回答；需要原文依据时选择“仅知识库”。
- 超长文档无法索引：拆分后重新上传；扫描 PDF 需先 OCR。

前端测试：`npm test --prefix frontend`。后端完整测试需要数据服务，Windows 使用 `.\scripts\backend.ps1 test`，macOS 使用 `./scripts/backend.sh test`；使用独立测试库，模型测试使用模拟服务。

更多原理见 [学习手册](docs/learning-manual.md) 和 [第 39 课部署讲义](docs/lesson-39.md)。历史课程中的旧入口以本 README 为准。

## 数据备份与恢复

第 40 课新增停机备份工具，备份 MySQL、MinIO 和 Qdrant 三个数据卷，校验后仅恢复到新卷。操作前等待索引结束并正常停止应用和数据服务；不要在运行中直接复制数据库目录。`docker/.env` 需要另外安全保存，`backups/` 不提交 Git。

具体停机、Mac/Windows 命令及隔离恢复步骤见 [第 40 课：停机备份与恢复演练](docs/lesson-40.md)。恢复过程不会自动覆盖原数据或切换当前应用。

## Git

默认分支 `codex/initialize`，完成改动并验证后提交、推送到 `origin/codex/initialize`。远程仓库为 `git@github.com:chaofengming123/-ai-.git`，不提交密钥、原文件、数据卷或生成产物。
