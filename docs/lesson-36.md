# 第 36 课：Redis 共享缓存与故障降级

第 35 课的向量缓存保存在 Java 进程里。现在默认改用 Redis：后端实例连接同一 Redis、使用同一命名空间时，可以复用同一用户的问题向量。缓存仍只保存向量，文档检索和回答每次重新处理。

## 一、启动与体验

在项目根目录运行：

```bash
docker compose --env-file docker/.env -f docker/compose.yml up -d --wait redis
```

本次已在当前 Mac 启动 Redis，绑定本机 6380 端口；容器内部为 6379。然后停止原后端并启动新版，保持其他服务运行。无数据库迁移或索引重建。

1. 在知识库问答中提交相同问题两次，观察 MISS 与 HIT。
2. 在五分钟内仅重启后端，再登录同一账号、提交同一问题。Redis 仍在运行且缓存未被淘汰时，可以继续命中。
3. 勾选本次跳过缓存，观察 BYPASS 与实际向量生成次数。

对照第 35 课：内存缓存随后端重启消失；Redis 缓存独立于后端进程。本课 Redis 关闭磁盘持久化，Redis 自己重启会丢失缓存，这不影响原文件、索引和数据库中的业务数据。

## 二、配置与共享条件

默认配置无需额外填写。需要调整时，在本地 docker/.env 中设置：

```dotenv
VECTOR_CACHE_BACKEND=redis
VECTOR_CACHE_NAMESPACE=ai-knowledge
REDIS_HOST=127.0.0.1
REDIS_PORT=6380
```

后端连接与命令超时均配置为 500 ms；这些是客户端分项限制，不是整次业务请求的总截止时间。

若要对照上一课，可设置 VECTOR_CACHE_BACKEND=memory 并重启。memory 与 redis 二选一，不叠加两级缓存。页面使用通用命中状态，实际存储由后端配置决定。

多个实例共享缓存需要连接同一 Redis、使用同一命名空间、同一账号 ID 体系及相同 Embedding 地址与模型。不同开发环境或不同数据库不要共用 namespace，避免用户 ID 恰好相同造成跨环境复用。

本课容器只对本机开放，没有提供公网访问、TLS、集群或生产鉴权部署方案。Windows 共用 Mac 后端时不需要单独连接 Redis。

## 三、为什么引入接口

新增 VectorCache 接口，描述获取问题向量的统一操作：

```java
QuestionVectorCache.Result get(long userId, String space, String question,
    boolean bypass, Supplier<double[]> load);
```

原 QuestionVectorCache 和新 RedisQuestionVectorCache 都实现它。KnowledgeBaseRagService 通过构造器接收 VectorCache，不再在内部固定 new 一个内存缓存。

VectorCacheConfig 的 @Bean 根据配置返回具体实现。Service 只负责“拿到向量继续检索”，缓存实现负责“去哪里读取和保存”。未知 backend 配置会让启动失败，不悄悄换成另一个实现。

项目添加 Spring Boot 的 Redis starter，使用自动配置的 StringRedisTemplate 操作字符串键值。[Spring Data Redis 文档](https://docs.spring.io/spring-data/redis/reference/)

## 四、缓存键和值

键格式为：

```text
命名空间:qv:v1:用户ID:SHA256(模型空间 + 换行 + 去除两端空白的问题)
```

qv 表示问题向量，v1 标记当前格式；namespace 区分应用环境；用户 ID 保留隔离；模型空间与问题共同确定向量身份。键不直接放问题原文，摘要也不是加密。

值是 JSON 数值数组，例如 [0.1,0.2,...]。不用 Java 对象序列化，便于限制格式，也无需 Redis 了解 Java 类名。

读取后检查数组非空、维度最多 4096、所有元素为数值、向量范数有限且非零。文档检索还会继续检查向量维度是否匹配已发布索引。Redis 中的数据不能未经检查就拿去检索。

## 五、关键代码：读取、计算、带过期时间写入

核心流程为：

```text
GET key
 → 有有效向量：HIT
 → 无值：调用 load 生成向量
 → SET key value，过期时间五分钟
 → MISS
```

关键写入代码：

```java
redis.opsForValue().set(key, json.writeValueAsString(vector), Duration.ofMinutes(5));
```

这一次写入同时设置有效期，避免“先写值、后设过期”两步之间中断而留下没有过期时间的条目。读取不会续期。多个实例同时未命中时仍可能重复计算、先后写入，本课没有分布式锁或请求合并。

Redis 服务负责过期处理，后端无需遍历所有条目。为了限制缓存内存，本课 Redis 配置 maxmemory=64mb 和 allkeys-lru，内存压力下可以淘汰近期较少使用的键；这是近似 LRU，并非上一课 Java Map 的精确访问顺序。maxmemory 也不等于整个容器的总内存上限。[Redis 淘汰规则](https://redis.io/docs/latest/develop/reference/eviction/)

Redis 方案不再使用 128 条限制。五分钟内也可能因容量淘汰、Redis 重启或键删除而未命中。

## 六、关键代码：缓存坏了，业务怎样继续

缓存是可重新计算的数据。读取失败、连接不可用、超时或缓存内容格式异常时，本课直接调用 Embedding，并标记 DEGRADED。为了避免连续等待，读失败后不再尝试写 Redis。

如果读取正常但写入失败，已经生成的向量仍返回给检索流程，同样标记 DEGRADED。

重要的是异常处理范围：

```java
try {
    // 读取并校验 Redis
} catch (RuntimeException failure) {
    degraded = true;
}
var vector = load.get().clone();
```

load.get() 放在缓存 catch 之外。若真实模型调用失败，异常必须向上抛出，不能当成缓存故障吞掉，更不能再次调用模型自动重试。

异常缓存值不会被使用，但本课也不主动删除或覆盖它；后续请求可能继续降级，直到键过期或被清理。页面明确显示状态，没有后台自动修复任务。

## 七、状态和耗时的含义

沿用 HIT、MISS、BYPASS、NOT_USED，新增 DEGRADED：“缓存不可用或数据异常，已直接生成问题向量”。只有整次业务成功，页面才展示该状态；模型失败仍显示错误。

BYPASS 不读取、不写入 Redis，不代表清空缓存。命中时 Embedding 阶段为零次；降级时通常为一次。Redis 等待和 JSON 解析属于上一课的“其他处理”。

缓存故障时响应可能变慢，因为先等待缓存失败再调用模型。当前没有熔断器，连续请求仍可能逐次尝试 Redis，也没有把降级结果保存为本地备用缓存。

## 八、练习故障降级

在确认没有其他人依赖本机练习 Redis 时，可以手动停止它：

```bash
docker compose --env-file docker/.env -f docker/compose.yml stop redis
```

保持后端运行，提交有可用文档的问题，不勾选跳过缓存。若模型正常，应收到结果并显示 DEGRADED。再恢复服务：

```bash
docker compose --env-file docker/.env -f docker/compose.yml up -d --wait redis
```

恢复后首次正常计算可重新填入缓存，后续可命中。本课不会替你停止正在使用的缓存来做故障演示；自动测试通过模拟异常验证降级。

## 九、验证方式

本次 140 项后端测试、37 项前端测试及生产构建通过；模型使用模拟响应，未调用真实服务商。现有 8080 后端未停止，需要用户重启新版。

新增真实 Redis 测试创建两个独立连接和客户端，验证一端写入、另一端命中，检查 TTL、用户与模型隔离、跳过不覆盖和过期后重新计算。测试使用随机命名空间，结束只删除本次创建的键，不清空 Redis。

单元测试模拟读失败、写失败和畸形向量，检查业务能够降级，以及模型错误不会被吞掉或重试。原业务回归测试继续使用内存实现，专门的 Redis 测试覆盖共享存储，避免普通测试互相污染。

后续可以用更完整的请求日志、命中率和故障指标观察真实运行情况，再逐步学习异步索引处理。
