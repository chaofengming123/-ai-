# 第 39 课：用 Docker 运行完整应用

这一课先在本机部署，不要求购买服务器。完成后访问 `http://127.0.0.1:8088`：网页由 Nginx 提供，API 由容器中的 Spring Boot 提供，数据继续使用现有 MySQL、MinIO、Qdrant 和 Redis。

本课新增应用容器，保留原来的 5173 前端和 8080 后端开发进程。两套应用共用数据，因此任何一边的业务修改都会作用于同一套数据。日常使用请选择一个入口，避免同时反复提交索引任务。新网页端口属于不同浏览器源，需要重新登录，但使用原来的账号。

## 1. 部署后的请求路线

```text
浏览器 → 本机 8088 → web 容器的 Nginx :80
                     ├─ / 和页面路由 → Vue 构建产物
                     └─ /api/... → backend:8080
                                      ├─ mysql:3306
                                      ├─ minio:9000
                                      ├─ qdrant:6333
                                      ├─ redis:6379
                                      └─ 外部模型服务
```

宿主机是你的电脑，容器有自己的网络空间。容器内的 `127.0.0.1` 指向它自己，不能用原来的 `127.0.0.1:3307` 连接 MySQL。Compose 中的服务名负责容器间定位，因此改为 `mysql:3306`。

只有网页新增了宿主机端口映射。容器后端没有占用宿主机 8080；现有数据服务仍保持本机回环端口。本课没有配置公网访问、域名或 HTTPS，不把它当成已完成的公网部署。

## 2. 构建：先生成产物，再装入镜像

本课使用容易观察的两步方式：先在本机运行 Maven 和前端构建，再将 JAR 和 dist 装入运行镜像。它不是多阶段源码镜像；第一次构建仍需要本机 Java 17、项目支持的 Node.js、Python 和 Docker Desktop。

`docker/backend/Dockerfile`：

```dockerfile
FROM public.ecr.aws/docker/library/eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY backend/target/ai-knowledge-backend-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]
```

`FROM` 提供 Java 运行环境；`COPY` 放入已经打包的应用；`USER` 让业务程序以非 root 用户运行；`ENTRYPOINT` 决定容器启动时执行的程序。`MaxRAMPercentage` 限制堆的相对比例，不等于为整个容器设置内存硬上限。

前端镜像使用 Nginx，复制 `frontend/dist` 和站点配置。Vue 构建产物是静态文件，运行时不需要 Node 或 Vite。

根目录 `.dockerignore` 采用允许列表：仅发送 JAR、dist 和部署文件给构建器。`docker/.env`、源码工作目录中的其他文件不会进入构建上下文。前端也不能包含模型密钥。

镜像标签当前跟随基础镜像系列，不能保证不同日期构建得到相同字节；后续正式发布应记录基础镜像摘要和应用版本。不要把 `lesson39` 标签理解成自动支持回滚。

## 3. Compose：把应用接到已有数据服务

同时使用两个配置文件：

```text
docker/compose.yml      已有数据服务和数据卷
docker/compose.app.yml  新增 backend 和 web
```

工程名继续是 `ai-knowledge`，所以在当前电脑上使用已有命名卷。不要随意添加另一个 `-p` 工程名，否则可能出现一套新的空数据卷。在另一台电脑启动不会自动拥有这台电脑的数据，迁移必须另行备份恢复。

后端环境变量覆盖本机默认配置，例如：

```yaml
SERVER_ADDRESS: 0.0.0.0
DB_URL: jdbc:mysql://mysql:3306/ai_knowledge?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC
MINIO_ENDPOINT: http://minio:9000
REDIS_HOST: redis
REDIS_PORT: 6379
```

`SERVER_ADDRESS` 让后端接受容器网络的请求；它本身不会发布宿主机端口。密钥由 Compose 从 `docker/.env` 读取，显式注入后端。没有把全部数据库管理密码注入应用。

`depends_on` 等待 MySQL、MinIO 和 Redis 的健康状态；Qdrant 这里只等待容器启动。后端健康检查只检查 TCP 监听，网页健康检查只检查 Nginx，自身健康不代表所有业务或模型服务可用。还需要本课的 HTTP 检查和手动业务验收。

应用配置 `restart: unless-stopped`，但这不是任务续跑或数据库高可用。停止的应用不会因为重启策略自行恢复后台索引；第 38 课的租约规则仍然有效。

Compose 多文件的路径相对第一个文件解析，具体规则见 [Docker 官方说明](https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/)。本课命令必须保留两个 `-f` 的顺序。

## 4. Nginx 的关键配置

文件：`docker/web/default.conf`。

- `try_files $uri $uri/ /index.html`：页面地址不存在对应静态文件时交给 Vue Router，所以直接刷新 `/knowledge-bases` 不会返回 404。
- `/assets/` 单独配置真实文件检查：不存在的 JavaScript 返回 404，不返回 HTML 假装成功。
- `proxy_pass http://$api$request_uri`：保留 `/api` 路径和查询参数，转发给后端。
- `resolver 127.0.0.11 valid=10s`：使用 Docker DNS，在后端重建、IP 变化后重新解析。更新期间可能短暂返回 502，这不是零停机发布。
- `proxy_buffering off`：让流式响应及时传给浏览器，不等待缓冲区攒满。
- `proxy_read_timeout 360s`：允许后端较长时间处理；这是相邻读取之间的等待限制，不是完整请求的统一总时限。
- `client_max_body_size 6m`：给上传表单留空间；单文件仍由后端限制为 5 MB。

这些代理行为可查阅 [Nginx 官方代理模块文档](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)。当前 Vue 使用相对 `/api` 地址，部署无需把后端地址写死在前端，也不需要为这个同源入口新增跨域放行。

## 5. 从项目根目录构建和启动

首次下载依赖或基础镜像可能较慢。已有项目保留 `docker/.env`；初始化脚本不会覆盖已有密码。不要把该文件提交到 Git。

### macOS / Linux

```bash
python3 scripts/init-db-env.py
npm ci --prefix frontend
npm run build --prefix frontend
./scripts/backend.sh -DskipTests package
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml config --quiet
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml up -d --build --wait --wait-timeout 180
python3 scripts/check-deployment.py
```

### Windows PowerShell

```powershell
python scripts/init-db-env.py
npm ci --prefix frontend
if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }
npm run build --prefix frontend
if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
.\scripts\backend.ps1 -DskipTests package
if ($LASTEXITCODE -ne 0) { throw '后端打包失败' }
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml config --quiet
if ($LASTEXITCODE -ne 0) { throw '部署配置无效' }
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml up -d --build --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) { throw '容器启动未通过检查' }
python scripts/check-deployment.py
```

每一步成功后再执行下一步。打包命令的 `-DskipTests` 只跳过本次测试运行，因为完整集成测试需要独立测试存储；它不代表已经通过测试，更不能在构建失败后使用旧产物继续发布。本课没有修改业务代码，部署验收单独执行。

打开 `http://127.0.0.1:8088`。端口冲突时可在本地 `docker/.env` 添加 `APP_PORT=8089`，重新启动 web，并将检查脚本参数改为 `http://127.0.0.1:8089`。

Windows 如果仍希望共用 Mac 上的数据，继续使用既有的 [共用数据方案](windows-shared-data.md)，不要照抄命令另外创建一套数据库并期待自动同步。当前新增 8088 只绑定本机，跨电脑访问入口需要单独配置。

## 6. 日常启动、关闭、更新

以下 Docker 命令在 macOS 和 Windows PowerShell 相同，均从项目根目录执行。

查看状态与最近日志：

```text
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml ps
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml logs --tail 80 backend web
```

只关闭部署的网页和后端，数据服务继续运行：

```text
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml stop web backend
```

重新启动应用（需要时也启动依赖）：

```text
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml up -d --wait
```

停止整套服务、保留容器和数据：

```text
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml stop
```

整套停止也会影响共用这些数据服务的开发应用。不要使用 `down -v`，它会删除命名数据卷。普通容器重建保留数据卷不等于做了备份。

更新代码后重新执行构建步骤，再执行 `up -d --build --wait`。只执行 `restart` 不会重新构建镜像，也不会加载新的容器环境变量。更新期间应等待索引工作结束；否则中断的任务可能需要租约过期后恢复。数据库迁移回滚也不能靠换回旧镜像解决。

## 7. 验收

`check-deployment.py` 是只读检查：API 健康、首页、深层路由刷新、JavaScript 静态文件、缺失资源 404，以及未登录接口返回 JSON 401。它不会改账号、上传文档或调用模型。

手动业务验收：登录原账号，查看已有知识库；上传一份测试文件，建立索引并检索；发送聊天请求确认流式显示。模型操作会使用你配置的服务。完成后可停止并重新启动应用容器，确认原数据仍可见。

本课不是公网发布：HTTPS、公开注册策略、数据权限隔离、备份恢复和资源限制仍需后续完善。下一步优先做备份恢复，让部署后的数据能够在故障时找回。
