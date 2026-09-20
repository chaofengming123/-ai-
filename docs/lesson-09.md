# 第 9 课：知识库 API 与 Controller / Service 分工

## 本节目标

用真实 HTTP 请求读取知识库列表、查看详情、创建记录。数据暂存在 Java 后端内存中，后端重启后恢复初始示例。

前端仍使用本地模拟数据，下一课再连接这组接口。因此这节课通过 API 新建的记录，暂时不会显示在 Vue 页面中。

## 1. 接口约定

| 请求 | 作用 | 成功响应 |
| --- | --- | --- |
| GET /api/knowledge-bases | 查询列表 | 200 + JSON 数组 |
| GET /api/knowledge-bases/{id} | 查询一条记录 | 200 + JSON 对象 |
| POST /api/knowledge-bases | 新建知识库 | 201 + 新记录 + Location 响应头 |

`{id}` 是位置占位符，实际请求例如 `/api/knowledge-bases/1`。

本课只实现列表、详情、创建，不实现更新和删除，也不是完整 CRUD。没有数据库或鉴权。

## 2. 先请求一次

沿用第八课，在 backend 目录运行 `./mvnw spring-boot:run`。已有旧版服务时先在原终端按 Ctrl+C 停止，再启动新版。

浏览器可以打开：`http://127.0.0.1:8080/api/knowledge-bases`。应用内浏览器若拦截 API，改用自己的浏览器或终端：

```bash
curl -i http://127.0.0.1:8080/api/knowledge-bases
curl -i http://127.0.0.1:8080/api/knowledge-bases/1
```

创建需要 POST，地址栏访问只会发送 GET。用下面的命令发送 JSON：

```bash
curl -i http://127.0.0.1:8080/api/knowledge-bases \
  -H 'Content-Type: application/json' \
  -d '{"name":"产品设计知识库","description":"产品需求与设计规范"}'
```

`-i` 显示响应头；`-H` 设置内容类型；`-d` 提供请求体，并让 curl 默认使用 POST。成功后读取响应里的 id，或直接访问 Location 响应头给出的路径。

同一个名称再提交一次会得到 409。后端重启后内存数据恢复，id 也从初始状态重新分配。

## 3. Controller：处理 HTTP 入口

文件：`backend/src/main/java/com/example/aiknowledge/controller/KnowledgeBaseController.java`。

```java
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @GetMapping
    public List<KnowledgeBase> list() {
        return service.list();
    }
}
```

**作用：** 接收请求，调用业务逻辑并返回结果。

**如何实现：** 类注解定义公共路径；不带子路径的 `@GetMapping` 就对应 GET `/api/knowledge-bases`。方法不直接维护数组，只把工作交给 service。

构造方法的参数由 Spring 提供，这叫“构造器注入”。`@Service` 标记的类被扫描后由 Spring 创建、管理，再传给需要它的 Controller。这个由 Spring 管理的对象叫 Bean。

这也是依赖注入（DI）的例子：Controller 声明需要什么，由容器提供。我们没有在每次请求里 `new KnowledgeBaseService()`，否则每次可能得到刚初始化的数据。

`final` 表示 service 字段赋值后不能指向其他对象，并不代表 service 内部状态不能变化。

## 4. Service：处理业务规则和内存数据

文件：`backend/src/main/java/com/example/aiknowledge/service/KnowledgeBaseService.java`。

```java
@Service
public class KnowledgeBaseService {
    private final Map<Long, KnowledgeBase> records = new LinkedHashMap<>();
    private long nextId = 3;

    public synchronized List<KnowledgeBase> list() {
        return List.copyOf(records.values());
    }
}
```

以上为结构节选，实际构造方法还加入编号 1、2 的两条示例。

**作用：** 集中管理记录与业务操作，保持 Controller 简洁。

**如何实现：** Map 按 id 保存对象，LinkedHashMap 保留插入顺序。`List.copyOf` 返回当前值的不可修改列表快照；返回的 KnowledgeBase 是只包含数字和字符串的 record，调用方不能通过修改返回列表破坏内部容器。

Spring 默认将这个 Service 作为单例 Bean 使用。一个服务可能同时接收多个请求，因此读取与创建方法都加 `synchronized`，在同一个实例上依次访问数据，避免重名检查和写入之间发生竞争、或编号重复。

这种方法适合本课的小型单进程内存示例，不是生产数据库方案，也不能解决多个后端实例之间的数据一致性。后续 MySQL 会承担持久存储，唯一约束和事务会继续保护数据。

## 5. 路径参数：把地址里的 id 交给 Service

```java
@GetMapping("/{id}")
public KnowledgeBase get(@PathVariable long id) {
    return service.get(id);
}
```

**作用：** 读取地址中的编号并查找记录。

**如何实现：** `@PathVariable` 让 Spring 将 `/1` 中的文字转换为 long 参数。如果传 `/abc`，无法转换，返回 400；如果编号合法但记录不存在，Service 抛出业务异常，转换为 404。

Service 通过 `records.get(id)` 查找记录，找不到时不返回一个假的空知识库。

## 6. 请求体与 DTO：接收创建参数

文件：`dto/CreateKnowledgeBaseRequest.java`。

```java
public record CreateKnowledgeBaseRequest(String name, String description) {
}
```

**作用：** 描述客户端创建请求可以提供的数据。

Controller 中：

```java
@PostMapping
public ResponseEntity<KnowledgeBase> create(@RequestBody CreateKnowledgeBaseRequest request) {
    KnowledgeBase created = service.create(request.name(), request.description());
    return ResponseEntity.created(URI.create("/api/knowledge-bases/" + created.id())).body(created);
}
```

**如何实现：** `@RequestBody` 读取 JSON 请求体，转换为 Java record。`request.name()`、`request.description()` 是 record 自动生成的访问方法。

DTO 是 Data Transfer Object，表示用于传输的数据对象。本课请求不接收客户端分配的 id、文档数量或分类，这些字段由服务端生成。请求类型与响应类型分开，有助于明确客户端可以输入什么。

`ResponseEntity` 允许控制状态码、响应头和响应体。`created(...)` 设置 201 Created 与 Location；`.body(created)` 将新记录放进响应体。Location 是可查询新记录的相对路径。

## 7. 创建规则：校验通过才写入

Service 先规范化并检查：

```java
String normalizedName = name == null ? "" : name.strip();
String normalizedDescription = description == null ? "" : description.strip();
```

**作用：** 处理遗漏字段、null 和两端空白，避免空指针异常与无意义名称。`strip` 使用 Java 的空白字符规则，与 JavaScript trim 在某些特殊 Unicode 空白上的范围并不完全相同；服务端校验是最终依据。

然后检查名称非空、名称长度不超过 60、描述长度不超过 300；长度按 Java 字符串 UTF-16 单元计算，部分 emoji 占多个单元。

```java
records.values().stream().anyMatch(item -> item.name().equals(normalizedName))
```

`stream` 遍历已有记录，`anyMatch` 判断是否至少一条同名，作用类似前端的 `some`。重名比较区分英文大小写。

通过后：

```java
KnowledgeBase record = new KnowledgeBase(nextId++, normalizedName,
        normalizedDescription.isEmpty() ? "暂无描述" : normalizedDescription, 0, "自建知识库");
records.put(record.id(), record);
return record;
```

`nextId++` 先取当前编号，再加一。新建记录默认 0 份文档、分类为“自建知识库”。所有校验都在分配编号和写入之前，所以校验失败不会新增记录。

即使前端已有校验，后端仍必须检查，因为客户端也可以直接用 curl 调接口。

## 8. 异常处理：业务错误转换为 HTTP 响应

Service 抛出的 `KnowledgeBaseException` 携带业务类型和中文消息；不直接依赖 HTTP 状态码。

`ApiExceptionHandler` 使用 `@RestControllerAdvice` 集中处理异常，`@ExceptionHandler` 声明负责哪种异常。核心映射：

```java
HttpStatus status = switch (error.kind()) {
    case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
    case NOT_FOUND -> HttpStatus.NOT_FOUND;
    case CONFLICT -> HttpStatus.CONFLICT;
};
```

对应 400、404、409。错误响应示例：

```json
{"message":"这个名称已经存在，请换一个名称。"}
```

处理器还将无法解析的 JSON、空请求体和非整数路径编号转换为可读的 400 提示。本课不统一拦截所有未知异常，其他框架错误不保证采用相同响应结构。

## 9. 后端内存和前端内存有什么不同

现在前端模拟数据与后端内存记录是两份独立数据，还没有连接。

后端里的记录属于正在运行的 Java 进程：浏览器刷新不会清空它，多个客户端请求同一个进程可以读到相同记录；重启 Java 服务会丢失。它仍不是数据库持久化。

刷新前端则会重建前端模拟数据。下一课连接 API 后，前端就会从后端读取记录。

## 验证

在 backend 目录运行 `./mvnw test`。本课新增 HTTP 集成测试，覆盖列表、详情、创建后查询、Location、重复名称、空白/超长输入、非法 JSON、缺省描述和错误编号；同时保留第八课健康接口测试。

当前测试使用独立测试应用和内存数据，不影响正在运行的演示服务。

## 小练习

用 curl 创建一个知识库，再读取返回的 Location。重复创建同名记录，观察 201 与 409 的不同；对照源码找出错误在 Service 哪一行产生、如何变成 HTTP 响应。

## 下一步

让 Vue 通过真实 HTTP 请求读取和创建知识库，配置开发代理，并处理后端返回的错误。之后再加入 MySQL。
