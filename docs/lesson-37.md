# 第 37 课：后台索引任务与自动查询状态

这一课把文档索引改为后台执行。点击“提交索引任务”后，网页先收到任务已接受的答复，再自动查询处理结果。请先重启后端并启动前端，进入知识库，打开某份文档的索引面板。

## 1. 为什么要改

读取文件、解析文字、调用 Embedding、写入 Qdrant 都需要时间。之前前端必须等待整个 POST 请求完成；关闭页面后，用户也不容易知道后端是否仍在处理。

现在把“提交任务”和“查询结果”拆开：

```text
Vue → POST /api/documents/{id}/index/tasks
    → 权限检查 → 登记 PROCESSING → 提交后台线程 → 返回 HTTP 202

后台线程 → MinIO 原文件 → 提取文字 → 分块 → Embedding
        → Qdrant 写入 → MySQL 发布 READY 或记录 FAILED

Vue → 每次查询结束后等待 2 秒 → GET /api/documents/{id}/index
    → 显示最新状态，结束后停止查询
```

202 表示接受了任务，不表示索引已经成功。原来的同步 `POST /api/documents/{id}/index` 仍保留，以兼容早期课程；当前页面使用新接口。

## 2. Controller：接受请求，返回明确的 HTTP 语义

文件：`backend/src/main/java/com/example/aiknowledge/controller/DocumentIndexController.java`。

```java
@PostMapping("/tasks")
public ResponseEntity<DocumentIndexService.Status> submit(@PathVariable long id) {
    return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(indexes.submit(id));
}
```

- `@PostMapping` 与类上的路径组合，定位提交接口；`@PathVariable` 把路径中的文档 ID 转为 Java 参数。
- `indexes.submit(id)` 先登记状态并提交后台工作，返回一个 Java `Status` 对象。
- `accepted()` 设置状态码 202；Spring MVC 的 JSON 消息转换器把对象序列化为响应正文。
- `noStore()` 告诉客户端不要缓存这个响应。查询状态也返回最新数据库结果。

新接口仍要求 `document:index` 权限，查询要求 `document:read`。认证和授权发生在提交之前，后台线程只执行已经接受的工作，并不依赖请求线程中的登录上下文。已接受的任务不会因为用户随后关闭网页或退出登录而取消。

## 3. Service：先登记，再执行

文件：`backend/src/main/java/com/example/aiknowledge/service/DocumentIndexService.java`。

```java
if (!capacity.tryAcquire()) throw new ChatException(503, "当前有文档正在建立索引，请稍后再试。");
indexes.initialize(id);
if (indexes.claim(id, attempt) != 1) throw new ChatException(409, "...");
var accepted = status(id);
worker.execute(() -> {
    try { execute(id, document, attempt, collection); }
    catch (RuntimeException ignored) { /* execute 已尝试记录失败 */ }
});
return accepted;
```

这是关键顺序的摘录，完整代码还处理提交失败与许可释放。

`Semaphore(1)` 控制每个后端进程同时接受一份索引工作。`tryAcquire()` 立即尝试获取名额，失败就回复 503，不让大量请求在后台等待。`finally` 释放名额，使下一份文档可以提交。

`initialize` 保证状态记录存在；`claim` 使用数据库条件更新登记 PROCESSING 和本次 `attempt`。更新行数为 1 才表示领取成功。这保留了第 27 课的十分钟租约规则，也防止多个后端同时领取同一份尚未过期的文档任务。进程内的信号量不能限制整个集群的总并发数。

`worker.execute(...)` 把 Lambda 交给独立线程执行。它不是直接调用索引方法并等待结果。线程池只有一个工作线程，队列容量也有限；配合前面的许可，不建立无限任务队列。

先读取 `accepted` 快照，再提交线程，保证本次接受响应表达的是 PROCESSING；即使后台很快完成，最终结果也通过 GET 查询。若线程池拒绝任务，代码尝试记录失败、释放许可，并返回 503，不能回复虚假的接受成功。

## 4. 后台失败后，数据如何保持一致

`execute` 沿用已有完整索引流程，每次尝试生成独立集合名。所有向量写入后，`publish` 使用本次 attempt 条件更新 MySQL 中的有效集合指针。旧尝试不能覆盖新尝试的结果。

重建失败时记录 FAILED，并保留原来的有效集合。因此 `state=FAILED` 与 `hasActiveIndex=true` 可以同时出现：最近一次重建失败，但之前发布的索引仍然存在。模型空间是否匹配仍由原来的检查决定。

后台异常已经无法作为原 POST 的失败响应返回，因为 202 可能早就发给了浏览器。用户通过 GET 读取失败信息。数据库状态不确定时，代码保守保留可能已发布的集合，避免误删有效向量。

本课没有增加数据库表或迁移，继续使用 `document_index`。Redis 仍然只缓存问题向量，没有参与任务分发。

## 5. Vue：只查询，不自动重复提交

文件：`frontend/src/utils/indexMonitor.js` 与 `frontend/src/components/DocumentIndexPanel.vue`。

```js
await store.run(action)
if (stopped || version !== sessionVersion()) return
if (store.error.value) {
  notice.value = '自动刷新已暂停，可手动刷新确认状态。'
  return
}
if (store.status.value?.state === 'PROCESSING') {
  if (timers.now() >= deadline) notice.value = '已自动查询五分钟，可稍后手动刷新；任务可能仍在执行。'
  else timer = timers.set(() => refresh('status', true), 2000)
}
```

`store.run` 复用请求状态、错误处理和过期响应保护。收到 PROCESSING 后设置一次定时器，定时器调用的始终是 `status`，不会再次执行 `build`。采用“本次查询完成后再等待”的方式，可以避免慢请求与下一次查询重叠。

READY、FAILED 或查询错误都会停止自动查询。自动观察窗口为五分钟，超出后可手动刷新；这是浏览器的观察期限，不代表后台任务自动取消。后台原有处理期限检查和外部请求超时仍分别生效，并不是精确到秒的统一强制中断。

组件卸载时清除定时器、终止当前前端请求并丢弃迟到响应；会话版本检查防止旧登录请求污染新会话。终止浏览器请求不会取消后台索引。

页面展示真实状态，不显示没有测量依据的百分比进度。提交请求若网络失败，先手动刷新状态确认是否已经接受，不要盲目重复提交。

## 6. 动手验证

1. 上传一份带有文字的文件，打开索引面板，点击提交。任务较快时，可能很快显示完成。
2. 处理中关闭面板，再打开，观察页面从数据库恢复状态并继续查询。
3. 完成后执行检索，确认索引仍然可用。
4. 处理中再次提交，会收到忙碌提示；不会无限堆积后台工作。
5. 已有索引的文档重建失败时，查看失败信息和旧索引可用状态。自动化测试使用模拟模型故障验证此情况，无需改动真实密钥。

上传上限仍是 5 MB，但可索引文字仍受之前的正文长度、分块数量和 PDF 页数限制；异步化不会自动扩大这些限制。

## 7. 当前边界与下一步

这是进程内后台线程，不是持久化任务队列。MySQL 保存处理状态，线程中的待执行工作并不会随之保存。后端关闭时会尝试中断线程；如果来不及记录失败，状态可能停在 PROCESSING，需要原领取时间满十分钟后手动重试。重启不会自动恢复或重放任务。

还没有取消、自动重试、阶段进度和跨进程任务调度。下一步可以学习任务恢复与重试设计：怎样区分可重试故障、限制重试次数，并避免重复执行产生重复数据。

想一想：为什么不能先返回 202，再尝试把任务写进数据库？如果用户刷新页面，为什么应该发 GET，而不是再次发 POST？
