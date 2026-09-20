# 第 11 课：MySQL 持久化与 Mapper

## 本节目标

把知识库从 Java 内存 Map 迁到 MySQL。浏览器刷新、后端重启后，新建记录都能再次查询。接口地址和响应字段保持不变，前端继续使用第十课的 Axios 请求。

当前仍是本地学习环境，没有登录、权限或 AI。

## 1. 启动顺序

先确保 Docker Desktop 已运行。从项目根目录执行：

```bash
python3 scripts/init-db-env.py
docker compose --env-file docker/.env -f docker/compose.yml up -d --wait
scripts/backend.sh spring-boot:run
```

另一个终端启动前端：

```bash
cd frontend
npm install
npm run dev
```

初始化脚本仅在 `docker/.env` 不存在时生成随机开发密码，已有文件不覆盖。此文件被 Git 忽略，不提交密码。脚本还生成包含测试账户密码的 `docker/mysql/init/01-test-database.sql`，该文件同样被忽略，供容器首次初始化读取。`scripts/backend.sh` 读取这些环境变量后运行 Maven Wrapper。

端口：MySQL 本机 3307 → 容器 3306，后端 8080，前端通常 5173。若已有旧版后端，先保留其内存记录再停止，避免数据丢失。旧内存数据不会凭空出现在新数据库中。

## 2. Docker 数据卷为什么能保留记录

`docker/compose.yml` 的关键配置：

```yaml
volumes:
  - mysql_data:/var/lib/mysql
```

**作用：** 把 MySQL 数据目录放到命名数据卷。

**如何实现：** 容器运行 MySQL，数据库文件由数据卷保留。停止或重新创建容器，继续使用同一卷就能读取原数据。普通 `docker compose down` 不删除命名卷，但带 `-v` 会删除卷和数据库，平时不要使用。

这不是备份。磁盘损坏、误删数据卷仍会丢失数据，后续再学习备份恢复。本课使用官方 MySQL 8.4 镜像，端口只绑定本机。

## 3. 连接配置：Java 怎么找到 MySQL

`backend/src/main/resources/application.properties` 包含：

```properties
spring.datasource.url=${DB_URL:jdbc:mysql://127.0.0.1:3307/ai_knowledge?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC}
spring.datasource.username=${DB_USER:ai_knowledge}
spring.datasource.password=${DB_PASSWORD}
```

**作用：** 告诉 Spring 数据库地址、库名、用户名和密码。

`${变量:默认值}` 从环境变量读取，未设置时使用默认值。密码没有写死默认值，必须由脚本加载本地配置。Connector/J 是 Java 与 MySQL 通信的 JDBC 驱动；连接池负责复用连接。

URL 中的本地无 TLS 连接设置只用于当前回环地址上的开发环境，不应原样用于远程生产数据库。

## 4. 表、主键和字段

首次迁移脚本位于 `backend/src/main/resources/db/migration/V1__create_knowledge_base.sql`。

核心结构：

```sql
CREATE TABLE knowledge_base (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(60) NOT NULL,
    description VARCHAR(300) NOT NULL,
    document_count INT NOT NULL DEFAULT 0,
    category VARCHAR(60) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_knowledge_base_name (name)
);
```

以上省略字符集和排序规则，完整文件指定 utf8mb4 和二进制排序规则。

- 表：保存同一种业务记录，每一行是一条知识库。
- 主键 id：唯一标识一行，AUTO_INCREMENT 由数据库分配编号，前端不生成。
- VARCHAR：可变长度文字；INT/BIGINT：整数。
- NOT NULL：字段不能是数据库 NULL。
- UNIQUE：禁止出现同名记录。
- created_at：数据库自动记录插入时间，本课不增加到 API 响应。

名称使用区分大小写的二进制排序规则，与之前按精确文本检查重名的约定接近。Service 仍负责去两端空白与长度检查，数据库负责最终唯一性保障。

## 5. Flyway：表结构也有版本

**作用：** 首次启动时创建表并加入两条示例，以后启动不重复执行相同迁移。

**如何实现：** Flyway 查找 V1 开头的迁移文件，执行后在数据库自己的版本历史表中记录版本与校验信息。下次启动先检查历史，而不是重新建表或清空数据。

已经执行的迁移不要改写；后续表结构变更新增 V2、V3 文件。这样代码与数据库演进有可追踪的对应关系。

## 6. Entity：Java 对象对应数据库行

文件：`entity/KnowledgeBaseEntity.java`。

```java
@TableName("knowledge_base")
public class KnowledgeBaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private Integer documentCount;
    private String category;
    // 完整文件包含各字段 getter/setter。
}
```

**作用：** 描述持久化层使用的数据对象。

**如何实现：** `@TableName` 指定表名，`@TableId` 指定自增主键。驼峰映射配置将 `documentCount` 对应到 SQL 的 `document_count`。

本课不使用 Lombok，直接写 getter/setter，便于看见字段如何被读取和赋值。数据库生成 id 后，Mapper 会把它填回 Entity。

原来的 KnowledgeBase record 仍作为 API 响应对象。Entity 与响应分开，未来新增数据库字段时，不会无意中直接暴露给客户端。

## 7. Mapper：执行数据库读写

```java
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> {
}
```

**作用：** 提供查询、插入等访问表的方法。

**如何实现：** `@Mapper` 让 MyBatis 注册代理实现，`BaseMapper` 提供通用方法，因此无需手写实现类。MyBatis-Plus 依据 Entity 元信息生成 SQL，并通过 JDBC 执行。

Service 列表方法：

```java
return mapper.selectList(new LambdaQueryWrapper<KnowledgeBaseEntity>()
        .orderByAsc(KnowledgeBaseEntity::getId))
        .stream().map(this::toResponse).toList();
```

`selectList` 查询记录，`orderByAsc` 明确按 id 升序，`toResponse` 将实体转成 API record。不要依赖数据库在没有 ORDER BY 时恰好返回某种顺序。

详情使用 `mapper.selectById(id)`，查不到仍返回第九课的 404。

## 8. insert 与数据库唯一约束

创建方法保留输入校验，然后设置 Entity 字段：

```java
try {
    mapper.insert(entity);
} catch (DuplicateKeyException error) {
    throw new KnowledgeBaseException(CONFLICT, "这个名称已经存在，请换一个名称。");
}
return toResponse(entity);
```

**作用：** 插入记录，遇到重名时继续返回原来的 409 错误约定。

**如何实现：** 数据库分配 id，插入成功后回填 Entity。两个并发请求即使同时使用同名，唯一索引也只能允许一条写入。我们不再依赖 Java 的 synchronized 锁或先查询再判断重名。

本课每次创建只有一条 INSERT，数据库保障这条语句的原子性，尚未引入多步业务事务。以后一次操作要修改多张表时再学习事务边界。

## 9. 测试库与开发库隔离

- 开发库：ai_knowledge，使用 ai_knowledge 账户。
- 测试库：ai_knowledge_test，使用只授权测试库的 ai_knowledge_test 账户。

MySQL 首次初始化脚本创建独立测试库与账户。测试 profile 固定连接该测试库，测试后清理 id 大于 2 的测试记录，不碰开发库。

运行：

```bash
scripts/backend.sh test
scripts/backend.sh package
```

测试真正使用 MySQL，不是 H2 替身；首次需要 Docker 和数据库就绪。不要把测试配置改成开发库。

## 10. 现在的数据流

Vue → Axios → Controller → Service → Mapper → JDBC → MySQL → Entity → 响应 record → JSON → Vue。

Controller 仍处理 HTTP，Service 仍处理业务。新增 Mapper 后，Service 不再拥有存储用的内存 Map，数据库成为记录的保存位置。

## 小练习

创建一个知识库，记下编号。刷新浏览器、停止并重新启动后端，再查询这个编号，确认记录仍在。仅重启应用即可，不需要删除容器或卷。

## 本课实际验证

已将原内存服务的 3 条记录迁入 MySQL，编号和内容逐条核对一致。随后新建编号 4 的“MySQL 持久化验证”，停止并重新启动 Java 后端，再读回同一记录，内容和编号未变。

8 项真实 MySQL HTTP 测试通过，包括并发同名请求分别得到 201 和 409。测试库与开发库分开。前端回归测试、生产构建及页面读取也通过。

## 下一步

补上修改与删除接口及前端操作，完成知识库 CRUD，并学习更新校验与删除确认。

## 官方参考

- [MyBatis-Plus 安装说明](https://baomidou.com/getting-started/install/)
- [MySQL 官方 Docker 镜像](https://hub.docker.com/_/mysql)
