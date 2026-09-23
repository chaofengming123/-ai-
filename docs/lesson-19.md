# 第 19 课：MinIO 对象存储与原文件下载

第十八课把原文件保存在后端磁盘。本课将新文件存入 MinIO，让 MySQL 继续管理文档信息，并增加“下载原文件”。旧文档可继续从本地读取，也可以经过校验后迁到 MinIO。

文件格式和上传大小沿用上一课：UTF-8 TXT / Markdown，单个文件不超过 5 MB。本课重点是文件存在哪里、怎样找到它，而非文档解析。

## 1. 先理解 bucket 与 object key

对象存储通过两个名字找到一个对象：bucket 和 object key。

| 名词 | 含义 | 本课示例 |
| --- | --- | --- |
| bucket（桶） | 一组对象的容器 | ai-knowledge-documents |
| object key（对象键） | 桶内某个对象的唯一标识 | 后端生成的 UUID |
| object（对象） | 保存的字节内容及相关元数据 | 一份 TXT 文件的原始字节 |
| endpoint | 程序连接对象存储的地址 | http://127.0.0.1:9000 |

可以把 bucket 理解为一个文件柜，把 key 理解为柜中某份文件的编号。key 不要求等于原文件名，也不一定表示真实磁盘目录。用户上传 notes.md，页面仍显示 notes.md，但内部使用随机 key，避免同名文件覆盖。

MinIO 提供兼容 S3 的对象接口。后端通过网络调用它，文件会保存在 MinIO 容器的数据卷中。MySQL 中不存原文件字节，Vue 也不保存 MinIO 密钥。

## 2. 启动与配置

在项目根目录执行：

```sh
python3 scripts/init-db-env.py
docker compose --env-file docker/.env -f docker/compose.yml up -d --build --wait
scripts/backend.sh spring-boot:run
```

初始化脚本会为 MinIO 补充随机的 MINIO_ROOT_USER 和 MINIO_ROOT_PASSWORD，并保留已存在的数据库密码、JWT 密钥以及 MinIO 凭据。它们保存在 Git 忽略的 docker/.env。不要把密钥写入前端或提交到仓库。

两个 MinIO 端口用途不同：9000 是 S3 API，供 Java SDK 连接；9001 是浏览器控制台。它们都只绑定本机。MySQL 仍使用 3307，业务后端仍使用 8080。

如果已经在 IDEA 中运行后端，先停止那份进程，再重新启动更新后的版本。IDEA 的环境变量需要包含新增的 MINIO_ROOT_USER 和 MINIO_ROOT_PASSWORD；使用 scripts/backend.sh 会自动加载。保持后端工作目录为 backend，才能继续按默认路径读取第十八课的本地文档。

### 本课为何提供 Dockerfile

MinIO 官方社区仓库已归档；官方最后一次安全发布 RELEASE.2025-10-15T17-29-55Z 要求容器用户从源码构建。因此本课固定该源码版本，通过 docker/minio/Dockerfile 构建本地镜像，而不是使用浮动 latest。依据：[官方仓库](https://github.com/minio/minio)、[该版本发布说明](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z)。

基础镜像来自 Public ECR 中的 Docker 官方 Golang / Alpine 镜像。首次构建要下载编译器及 Go 依赖，可能需要数分钟，之后可使用本地构建缓存。若构建下载超时，先检查 Docker Desktop 网络连接，再重新执行启动命令，不需要删除数据库卷。本课用于学习 S3 对象存储；正式部署前需重新选择持续维护的服务或发行版。

## 3. 两套持久化系统各存什么

```text
Vue 上传文件
    ↓
Spring Boot DocumentService
    ├── MySQL document 表：文件名、知识库、大小、状态、存储定位信息
    └── MinIO：原文件内容
```

打开 V6__document_storage_location.sql。本课新增两个字段：

```sql
storage_backend VARCHAR(16) NOT NULL DEFAULT 'LOCAL'
storage_bucket VARCHAR(63) NULL
```

storage_backend 表示 LOCAL 或 MINIO，storage_bucket 记录 MinIO 桶名；原来的 object_key 继续标识文件。

为什么默认 LOCAL？升级数据库时，旧文件还在磁盘。如果给旧记录直接填 MINIO，程序就会去一个没有文件的桶中查找。V6 只补定位字段，真正的文件迁移由单独工具完成。

LOCAL 记录的 bucket 必须为空；MINIO 记录必须有 bucket。数据库约束检查两者的组合。下载时读取每条记录保存的桶名，而不是假设所有文件永远位于当前配置的桶。

## 4. Service 为什么不直接绑定某种存储

打开 service/DocumentStorage.java。DocumentService 现在把“保存、读取、删除对象”的工作交给它：

```java
public StoredFile save(byte[] bytes) {
    if (backend.equals("local"))
        return new StoredFile("LOCAL", null, local.save(bytes));
    return saveToMinio(bytes);
}
```

作用：把业务处理与存储选择分开。DocumentService 仍负责文件校验、知识库检查和数据库事务；DocumentStorage 根据配置选择新文件的保存方式，并根据记录选择旧文件的读取方式。

StoredFile 是内部 Java record，包含 backend、bucket、key。保存成功后，DocumentMapper 将这些字段与文件名、大小一起写入 MySQL。它不直接返回给浏览器；对外仍使用 DocumentInfo，避免暴露内部位置。

默认 DOCUMENT_STORAGE_BACKEND=minio。local 模式保留给旧功能回归测试和本地开发，不会自动作为 MinIO 故障时的回退路径：MinIO 不可用时会提示错误，避免文件在不同存储间悄悄分散。

## 5. Java 如何调用 MinIO

打开 service/MinioDocumentStorage.java。

```java
client = MinioClient.builder()
    .endpoint(endpoint)
    .credentials(accessKey, secretKey)
    .build();
```

作用：建立一个会向指定 endpoint 发送 S3 请求的客户端。credentials 用于签署后端请求，不会传给 Vue。课程使用本机随机管理凭据；部署时应改成只允许访问所需桶的应用凭据。

上传关键代码：

```java
client.putObject(PutObjectArgs.builder()
    .bucket(bucket)
    .object(key)
    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
    .contentType("application/octet-stream")
    .headers(Map.of("If-None-Match", "*"))
    .build());
```

bucket 指定桶，object 指定 key，stream 指定内容及已知长度。If-None-Match 表示目标已存在时不覆盖。本课内容最多 5 MB，因此使用内存中的字节数组即可；以后支持大文件时再设计流式处理。

上传前检查桶是否存在，不存在就创建；处理并发创建时同一账号已拥有该桶的情况。新桶没有公开策略，未签名访问对象会被拒绝。第一个上传或正式迁移之前，开发桶可能尚未创建，这属于正常情况。

项目使用固定的 MinIO Java SDK 8.6.0。该版本使用 OkHttp 5，Maven 项目显式引入 okhttp-jvm 5.1.0，以提供实际 JVM 类库。版本依据见 [SDK 发布记录](https://github.com/minio/minio-java/releases/tag/8.6.0)。

## 6. 为什么仍然需要失败补偿

MinIO 与 MySQL 是两个独立系统。@Transactional 只能回滚 MySQL，不能自动删除 MinIO 对象。

本课仍然采用：先保存对象，再插入元数据；如果数据库明确回滚，则根据 StoredFile 删除本次新对象。测试会主动触发回滚，确认数据库记录和对象都没有留下。

如果 MinIO 请求超时，有可能服务器已经保存对象，只是响应丢失。因此失败时不盲目删除不确定的对象，也不自动换成本地存储或重试业务上传。进程崩溃、清理失败、事务结果不确定仍可能产生孤立对象，这些情况以后需要任务记录与定期对账处理。

## 7. 旧文件如何安全迁移

先预览：

```sh
scripts/migrate-documents.sh preview
```

预览会启动独立后端、应用尚未执行的数据库迁移、检查 LOCAL 文档及原文件大小；不会复制文件或切换文档定位信息。使用随机端口，完成后自动退出，不占用 IDEA 的 8080。

确认预览通过后执行：

```sh
scripts/migrate-documents.sh apply
```

DocumentMigrationService 对每条记录执行：

1. 锁定记录，确认它仍为 LOCAL。
2. 从原目录读取文件，检查字节数量与 MySQL 记录相符。
3. 上传到 MinIO 的一个新随机 key。
4. 从 MinIO 读回，并与原文件逐字节比较。
5. 校验通过后，只更新存储类型、桶名和 key，文档编号、名称、所属知识库等保持不变。
6. 提交事务，保留原本地文件作为备份。

关键判断是：

```java
if (!Arrays.equals(bytes, storage.read(target))) {
    throw new DocumentException(...);
}
```

它保证读取回来的实际内容一致，再切换数据库引用。缺文件、大小不符或读回内容不同都会失败并保留原记录。明确回滚时清理新对象。

每条文档独立提交。一部分成功、一部分失败时，命令会返回非零退出码；修复后可重跑，已经是 MINIO 的记录会跳过。工具不会删除本地备份，也不会自动停止你正在使用的后端。

如果旧后端曾在不同工作目录启动，请将 DOCUMENT_STORAGE_DIR 设置为旧文件目录的绝对路径，再运行迁移。迁移期间避免继续使用旧版后端上传；它仍会产生新的 LOCAL 文档，需要再次迁移。

## 8. 下载为何经过后端

新接口为：

```text
GET /api/documents/{id}/download
```

Security 首先验证 JWT 和 document:read 权限，然后由 DocumentService 查询存储位置。LOCAL 文档从原目录读取，MINIO 文档通过 SDK getObject 读取。不存在的文档返回 404；未登录返回 401；没有权限返回 403。

Controller 返回原始字节，并设置：

- Content-Disposition: attachment：浏览器将它作为附件下载，保留原文件名。
- Content-Type: application/octet-stream：按文件字节传输。
- Cache-Control: no-store：不让响应作为普通缓存保存。
- X-Content-Type-Options: nosniff：不根据内容猜测成可执行页面。

文件名使用 UTF-8 编码方式设置响应头。这里返回的是字节流，成功响应不是 JSON；发生业务错误时仍返回 JSON 错误信息。

## 9. Vue 怎样保存下载结果

打开 frontend/src/api/documents.js：

```js
http.get(`/documents/${id}/download`, { responseType: 'blob' })
```

Axios 将响应作为 Blob 交给页面，现有拦截器继续附带 JWT。页面使用 URL.createObjectURL 创建临时浏览器地址，再通过带 download 属性的链接保存文件；完成后调用 URL.revokeObjectURL 释放该地址。

为什么没有直接写一个 MinIO 链接？本课桶保持私有，而且浏览器里的普通链接不会自动经过 Axios 的鉴权拦截器。由后端鉴权后转发文件，能复用已有权限规则。后续大文件场景可以再研究短期签名地址。

responseType 为 blob 时，错误响应也是 Blob，页面需先读取文本、解析 JSON，才能显示后端给出的具体原因。下载结束前如果用户退出、切换知识库或会话变化，旧响应不会触发文件保存。

## 10. 本课练习与边界

重启新版后端后，进入“文档管理”，上传一份 TXT / Markdown 文件，再点击“下载原文件”，比较下载内容。旧文档迁移后也应保持相同编号和内容。

请试着回答：

1. file_name 和 object_key 为什么是两个字段？
2. 只把数据库 LOCAL 改成 MINIO，为什么不能算文件迁移？
3. 为什么迁移需要从 MinIO 读回来比较，而不仅是确认上传调用没有报错？
4. MySQL 回滚时，MinIO 为什么不会自动回滚？

当前仍只支持小型文本文件；没有在线预览、文档删除、PDF / Word 解析或 AI 问答。MinIO 文件由 Docker 命名卷 minio_data 保存，勿用 down -v 删除课程数据；备份时还应保留 MySQL 元数据。

本次验证：32 项后端测试、13 项前端测试及前端构建通过。真实 MinIO 测试使用随机私有桶，覆盖上传下载、匿名对象访问返回 403、重复对象键拒绝覆盖、事务回滚清理、迁移预览与重跑、缺文件与内容校验失败；结束后清理测试桶。开发库的 1 份旧文档已复制校验并切换到 MinIO，原本地副本保留。

下一课扩展 PDF / DOCX 上传与格式校验。随后按手册先实现基础 LLM Chat，再进入文档解析和 RAG 流程。
