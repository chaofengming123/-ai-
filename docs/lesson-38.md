# 第 38 课：有限重试与过期任务恢复

上一课把索引放到后台。这一课解决两个不同问题：模型服务短暂失败时，能否再试一下；后端意外退出后，怎样重新处理遗留任务。

重启后端、刷新前端后即可使用，无新增配置或数据库迁移。模型、密钥和 5 MB 上传上限不变。

## 1. 先分清两种处理

| 情况 | 本课处理 |
| --- | --- |
| Embedding 明确返回 HTTP 502、503、504 | 当前批次最多再试两次 |
| 密钥错误、输入错误、模型不存在、429 限流或额度不足 | 记录失败，修正原因后手动提交 |
| 网络异常、请求超时、无效响应 | 不自动重试，先确认服务和任务状态 |
| 后端退出后仍显示 PROCESSING | 原领取时间超过十分钟后，显示恢复按钮 |

“最多重试两次”包含第一次调用在内最多三次，并不保证一定调用三次：中断、期限到达或出现不可重试错误都会提前结束。这里只对文档索引使用重试；普通问题向量、向量实验等调用不会自动采用这一策略。

## 2. 为什么不能看到 503 就重试

文件：`backend/src/main/java/com/example/aiknowledge/service/EmbeddingClient.java`。

原来的客户端会把配置缺失映射为对外的 503，把密钥错误映射为 502。它们都需要修改配置，多调用几次也不会好。因此，不能根据最终返回给浏览器的错误码来决定重试。

本课在收到模型服务响应的地方识别明确的上游故障：

```java
if (response.statusCode() == 502 || response.statusCode() == 503 || response.statusCode() == 504)
    throw new RetryableEmbeddingException();
```

`RetryableEmbeddingException` 继承 `ChatException`，保留现有对外错误处理，同时通过 Java 异常类型表达“这次故障允许索引流程有限重试”。密钥错误、429、超时、JSON 解析失败仍抛普通异常。

这里采用保守分类，不分析服务商的错误正文来判断额度是否恢复，也没有实现 `Retry-After`。上游明确报错不保证没有消耗计算资源，重试仍可能增加调用量；所以次数必须有限。客户端自己等待超时尤其不能推断服务端没有处理，本课不自动重试这种情况。

## 3. 重试器：限定次数、等待间隔和退出条件

文件：`backend/src/main/java/com/example/aiknowledge/service/IndexEmbeddingRetry.java`。

核心结构摘录：

```java
for (int attempt = 0; ; attempt++) {
    // 先检查中断和总处理期限
    try { return operation.get(); }
    catch (RetryableEmbeddingException error) {
        if (attempt >= 2) throw error;
        long delay = 500L << attempt;
        // 剩余时间不足则退出，否则等待后再调用
        sleeper.sleep(delay);
    }
}
```

`Supplier<T>` 表示一段可以执行并返回结果的操作。调用 `operation.get()` 才真正发起本批次向量计算；传递 Supplier 本身不会立即执行。

`attempt` 从 0 开始，所以 `attempt >= 2` 对应第三次调用失败，不能再试。`500L << attempt` 在两次等待时分别得到 500 毫秒、1000 毫秒。间隔逐渐增加，避免失败后立即连续请求。本课没有随机抖动或跨实例限流，大规模部署还需要进一步设计。

每次调用前检查线程中断和原有五分钟处理期限，等待前检查剩余时间是否足够。线程在等待时被中断，会恢复中断标记并抛出错误，避免后端正在关闭时继续重试。该期限不是强制截断正在执行的外部请求；外部调用仍有自己的超时限制。

测试通过注入 `Sleeper` 记录等待时间，不必真的等待，也能验证中断处理。正式运行使用 `Thread::sleep`，等待占用的是后台工作线程；本课每进程只有一个索引名额，等待期间不会接纳另一份索引任务。

## 4. 把重试放在正确的位置

文件：`backend/src/main/java/com/example/aiknowledge/service/DocumentIndexService.java`。

```java
vectors.addAll(IndexEmbeddingRetry.run(() -> embedding.embed(batch), deadline));
```

Lambda 捕获当前 `batch`。如果第一次失败，重试的仍然是同一批文字。只有成功返回后才把向量加入 `vectors`，不会把失败结果或同一批结果重复加入。

执行顺序如下：

```text
领取任务 → 读取与解析 → 分块
  → 第一批 Embedding（必要时有限重试）→ 收集成功结果
  → 下一批 Embedding（必要时有限重试）→ 收集成功结果
  → 写入本次独立 Qdrant 集合 → 条件发布有效索引
```

没有把整个 `execute` 包进重试器，因此不会因为一个 Embedding 暂时失败，就重复下载、重新领取任务或重复执行发布。Qdrant、MinIO、数据库的异常仍沿用原来的失败处理，不自动重试。失败时保留旧有效索引的规则也继续生效。

## 5. 后端重启后为什么需要“恢复”

线程池里的工作会随进程退出消失，MySQL 中的 PROCESSING 记录却还在。数据库状态并不等于任务队列，也不能凭 PROCESSING 判断线程此刻一定活着。

文件：`backend/src/main/java/com/example/aiknowledge/mapper/DocumentIndexMapper.java`。

```sql
SELECT COUNT(*) FROM document_index
WHERE document_id = #{id}
  AND state = 'PROCESSING'
  AND updated_at < CURRENT_TIMESTAMP - INTERVAL 10 MINUTE
```

这个查询使用数据库时钟判断是否符合恢复等待期，避免依赖浏览器的时间。状态接口增加 `recoveryAllowed` 字段，Vue 根据它显示“恢复索引任务”。未过期的 PROCESSING 状态会禁用提交按钮，仍允许手动查询。

查询只提供提示，真正提交时仍由已有 `claim` 条件更新决定是否领取成功。页面显示可恢复到用户点击之间，别人可能已经领取了任务，因此后端必须再次检查，不能信任页面上的布尔值。

恢复仍调用同一个 `/index/tasks` 接口，生成新的 attempt 和独立集合，从头处理原文件，不是从上一批继续。领取后重新进入 PROCESSING，页面自动查询最终结果。失败后的普通重新提交仍然可用。

## 6. 如何防止旧任务覆盖新任务

发布和记录失败时都带有类似条件：

```sql
WHERE document_id = #{id}
  AND state = 'PROCESSING'
  AND attempt_id = #{attempt}
```

假设旧任务 A 很慢，租约过期后新任务 B 已领取。A 后来返回时，其 attempt 不再匹配，更新行数为 0，不能覆盖 B 的状态。这种版本条件保护发布结果；它不能保证服务商只计算一次，也不会主动终止另一个进程里的旧线程。

同一进程还受信号量限制：即使数据库租约显示过期，只要旧线程仍占用名额，提交也可能收到忙碌提示。不要把“可以尝试恢复”理解为后端保证立即接受。

## 7. 验证与练习

正常操作：打开已有文档的索引面板，提交任务，等待完成，再执行检索。短暂重试期间仍显示 PROCESSING，没有虚构进度百分比，也没有独立的重试次数展示。

自动化测试覆盖：

- 暂时性故障后成功、500/1000 毫秒间隔、最多三次调用。
- 普通错误即使对外错误码是 502、503、504 也不重试。
- 中断与期限到达后不再调用模型。
- 本地模拟 HTTP 服务验证上游错误分类，不调用真实模型。
- 使用测试库模拟过期任务：未过期拒绝领取，过期后恢复成功；Embedding 失败一次后重试成功，只写一次向量，旧 attempt 无法更改新结果。

测试通过修改独立测试库时间模拟过期，不需要为了练习修改开发数据库或强制关闭服务。

思考：如果把整个“读取文件到发布索引”流程重复三遍，会增加哪些副作用？为什么 `recoveryAllowed` 不能代替 Mapper 的条件更新？

## 8. 下一步

目前没有持久化任务队列、自动扫描恢复、失败任务历史或自动重启续跑。下一课可以补充任务记录与可观测性，让用户知道一次任务何时开始、何时结束、为什么失败，再逐步讨论可靠调度。
