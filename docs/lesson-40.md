# 第 40 课：停机备份与恢复演练

部署成功后，下一个问题是：如果误删数据或电脑故障，能否找回账号、文件和索引？这一课实现一个有限范围的答案：正常停机后备份三个数据卷，校验归档，再恢复到全新的卷中验证。

工具不自动停止业务，不覆盖原卷，不自动切换到恢复后的数据。当前开发和部署服务继续运行；本课自动演练只使用随机命名的测试卷。

## 1. 为什么需要备份三个卷

| 数据 | 位置 | 作用 |
| --- | --- | --- |
| 账号、知识库、文件元数据、有效索引指针 | mysql_data | 记录业务关系以及该使用哪个向量集合 |
| 上传的原文件 | minio_data | 下载、重新解析和重新建立索引的来源 |
| 文档分块向量与来源载荷 | qdrant_data | 检索所需的数据 |
| 问题向量缓存 | Redis | 可以重新计算，本课不备份 |

只备份 MySQL，原文件仍可能丢失；只备份 Qdrant，数据库可能没有对应集合指针。三个归档应该来自同一次停机窗口，不能随意混搭不同日期的文件。

Docker 数据卷在容器删除后可以保留，但仍位于本机，并不自动成为异地备份。参见 [Docker 数据卷与备份说明](https://docs.docker.com/engine/storage/volumes/)。

## 2. 本课选择停机物理备份

物理备份复制数据目录中的文件，与 `mysqldump` 导出 SQL 不同。它便于观察三个卷如何整体保存，但依赖正确停机、兼容的服务版本和环境。跨版本升级或跨平台迁移不要直接假设可用，应另行设计逻辑导出或服务原生快照流程。

先停止接收写入的应用，等待索引结束，再正常停止数据服务。停机期间不要另起容器或开发后端访问数据。本工具会检查卷是否被活动容器占用，但没有分布式锁，不能防止另一个人在检查之后又启动服务，也不能判断之前是否被强制杀死。被强制关闭的数据库不能仅凭“已停止”当成干净备份，应先恢复正常运行并确认后正常停机。

## 3. 工具的关键代码

文件：`scripts/volume-backup.py`。使用 Python 标准库调用 Docker，没有额外 Python 依赖。

### 检查来源并只读挂载

`names(prefix)` 只生成三种固定卷名，例如 `ai-knowledge_mysql_data`。`stopped()` 先确认卷存在，再检查关联容器状态；运行中、暂停中、重启中都会被拒绝。

归档时使用：

```text
--mount type=volume,src=卷名,dst=/data,volume-nocopy,readonly
```

`readonly` 防止归档工具修改来源，`volume-nocopy` 防止镜像目录内容自动填入空卷。工具复用项目 MinIO 镜像中的 tar，覆盖入口为 tar，不会启动 MinIO 服务，也不开放网络。

```python
with path.open('xb') as stream:
    docker(..., '-czpf', '-', '-C', '/data', '.', stdout=stream)
```

`xb` 拒绝覆盖同名文件；tar 把压缩数据写到标准输出，Python 直接写入文件，避免把整个数据库放进内存。归档保留目录、文件模式和所有者等基本信息。本课没有承诺保存所有扩展属性或适配任意存储驱动。

### 最后才写清单

三个归档全部成功，并再次确认来源停止后，才写 `manifest.json`。清单记录时间、文件大小、SHA-256、归档工具镜像 ID，以及尚存在的源容器镜像信息。若源容器已删除，其镜像记录可能为空，应另外记录部署版本。

SHA-256 分块计算，恢复前检查全部文件。缺少清单或任意文件不匹配都会拒绝恢复，并且此时尚未创建目标卷。中途失败的目录可能保留，不能因为看见压缩文件就判断备份完成。

校验和不是加密或身份验证。备份含账号密码哈希和文档等敏感数据，必须限制访问、加密保管并另外保存一份到其他设备。只恢复你自己生成并可信保存的归档；工具不是不可信 tar 文件的安全解包器。

### 恢复到新卷

`restore` 要求显式提供新前缀，并在创建前检查所有目标名称。任一目标已存在就拒绝，不提供强制覆盖选项。归档解包失败会留下部分新卷，工具不会擅自删除它们；确认后可选择另一个新前缀重新演练。

这仍需管理员保证独占操作窗口，不要并发启动同名前缀的恢复命令。工具不修改原卷，不启动业务，也没有自动回滚业务数据。

## 4. 准备备份

从项目根目录执行。Docker Desktop 必须运行，且已有第 39 课依赖镜像。示例目录 `backups/lesson40` 已被 Git 忽略，不能推送真实备份。

先关闭本机通过 IDEA 或终端启动的开发后端，等待正在执行的索引结束。再停应用和数据服务：

```text
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml stop -t 120 web backend
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml stop -t 120 mysql minio qdrant redis
```

确认服务是正常退出，不要在停机超时、磁盘错误等情况下直接认为备份可靠。不要使用 `down -v`。

macOS：

```bash
python3 scripts/volume-backup.py backup backups/lesson40
python3 scripts/volume-backup.py verify backups/lesson40
```

Windows PowerShell：

```powershell
python scripts/volume-backup.py backup backups/lesson40
if ($LASTEXITCODE -ne 0) { throw '备份失败，请检查状态' }
python scripts/volume-backup.py verify backups/lesson40
```

每次使用新目录，例如 `backups/2026-09-24-evening`。不要为了重用目录而删除唯一的备份。备份完成后可重新启动原应用：

```text
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml up -d --wait
```

`docker/.env` 不在数据卷中，本工具不会复制它。请通过安全方式另行保存该文件以及对应代码版本、镜像版本；恢复原 MySQL 数据后不会因为生成新 `.env` 就重置数据库密码。配置丢失可能导致恢复的数据无法被应用访问。

## 5. 校验并恢复到新卷

macOS：

```bash
python3 scripts/volume-backup.py restore backups/lesson40 --prefix ai-recovery40
```

Windows 将 `python3` 改为 `python`。成功后得到：

```text
ai-recovery40_mysql_data
ai-recovery40_minio_data
ai-recovery40_qdrant_data
```

原来的 `ai-knowledge_*` 卷仍然存在。此时仅完成文件恢复，还没有证明业务可用。

## 6. 用独立工程检查恢复数据

新增 `docker/compose.restore.yml` 使用 `external: true` 指定恢复卷。这样卷不存在时会报错，而不是自动创建空卷让你误以为恢复成功。

本课仍沿用原来的端口，所以演练恢复应用前，先正常停止原工程和开发后端，避免端口冲突和连错数据。恢复环境使用备份时对应的镜像和配置，不要同时升级数据库或引入新迁移。

macOS 设置：

```bash
export RESTORE_PREFIX=ai-recovery40
```

Windows PowerShell 设置：

```powershell
$env:RESTORE_PREFIX = 'ai-recovery40'
```

然后执行相同的 Docker 命令：

```text
docker compose -p ai-recovery40 --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml -f docker/compose.restore.yml config --quiet
docker compose -p ai-recovery40 --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml -f docker/compose.restore.yml up -d --wait
```

这里 `-p ai-recovery40` 创建独立的容器工程，`RESTORE_PREFIX` 决定实际挂载哪个恢复卷，两者都需要写对。

使用原账号登录 `http://127.0.0.1:8088`，确认知识库记录存在、文件能够下载、索引能检索。查询真实模型会使用配置中的服务。恢复副本上的新修改不会自动合并回原数据。

演练结束先停止恢复工程，再启动原工程：

```text
docker compose -p ai-recovery40 --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml -f docker/compose.restore.yml stop -t 120
docker compose --env-file docker/.env -f docker/compose.yml -f docker/compose.app.yml up -d --wait
```

恢复卷暂时保留用于核对，不提供清空原卷的快捷命令。

## 7. 本次验证及边界

自动化测试使用随机命名的独立卷，验证：三卷归档与文件权限恢复、拒绝活动来源、损坏归档在创建目标前拒绝、已有目标不覆盖，以及真实 MySQL 正常停机备份后从新卷启动并读回记录。

MinIO 和 Qdrant 卷本次验证的是归档往返，不是完整的 MinIO/Qdrant 服务恢复。当前真实项目没有停机或执行备份，没有调用真实模型。Windows 命令未在 Windows 实机验证。

备份可用分三个层次：文件校验通过、服务启动并读到数据、业务下载与检索通过。不能把第一层当成全部完成。

备份频率决定最多可能丢失多久的数据（RPO），恢复演练耗时帮助估计恢复服务需要多久（RTO）。工具没有自动计划或异地复制，不能因为执行过一次演练就认为后续数据都有备份。

下一课优先处理知识库的数据访问权限：登录成功并有某项操作权限，不一定意味着应该能访问所有知识库。
