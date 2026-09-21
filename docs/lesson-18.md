# 第 18 课：第一个文档上传闭环

本课进入 Phase 5。你可以选择一个知识库，上传 TXT 或 Markdown 文件，再看到文件名、类型、大小、上传时间和“已上传”状态。普通用户可以查看列表，编辑者和管理员可以上传。

先完成“浏览器发送文件 → 后端接收 → 保存文件和信息 → 页面显示”的过程。本课支持 UTF-8 文本，单个文件最多 1 MB（1,048,576 字节），每次上传一个文件。原文件暂存后端本地目录；下一课接入手册中的 MinIO 对象存储，再继续扩展格式支持。Phase 5 尚未全部完成。

## 1. 先体验结果

在项目根目录启动数据库和后端：

```sh
docker compose --env-file docker/.env -f docker/compose.yml up -d --wait
scripts/backend.sh spring-boot:run
```

脚本会加载 docker/.env 并进入 backend 目录；后端启动时 Flyway 自动应用 V5。不要删除旧数据库卷。

如果使用 IDEA 启动，则先停止其他后端进程，确认已配置第十五课讲过的环境变量，再运行项目。只运行一份占用 8080 的后端。代码升级后需要重启后端；仍在运行的旧版本不会自动拥有新接口。

前端在另一个终端启动：

```sh
cd frontend
npm run dev
```

用自己的 EDITOR 或 ADMIN 账号登录，打开“文档管理”，选择知识库，上传一个 UTF-8 编码的 .txt 或 .md 文件。已有账号登录后可通过“核对登录状态”获取新增权限，也可以重新登录。若仍是普通用户，按照第十七课的脚本预览、确认后自行分配角色。本课没有自动提升任何账号。

可新建一个 lesson-18.md 文件，写入：

```text
# 公司知识库说明

这里用于保存团队共享的学习资料。
```

看到“已上传”后，刷新文档列表或重新登录，记录仍应存在。回到知识库页面，文档数量会包含这次上传。此前卡片上的数量是示例数，本课改为统计真实 document 记录；初次升级后未上传的知识库显示 0，并非删掉了真实文件。

## 2. 文件内容与文件信息有什么区别

| 存在哪里 | 保存什么 | 例子 |
| --- | --- | --- |
| MySQL document 表 | 元数据，即描述文件的信息 | 文件名、大小、所属知识库、状态、内部存储编号 |
| 后端本地文件目录 | 原始字节内容 | 你上传的整份文本 |

打开 backend/src/main/resources/db/migration/V5__document_upload.sql。document.knowledge_base_id 是外键，确保文档属于一个真实知识库。object_key 唯一，指向某个实际文件；file_name 用于页面显示。两个字段职责不同：不同用户可以上传同名文件，但保存位置必须不同。

默认从 backend 目录启动时，原文件在 backend/uploads/documents，已被 Git 忽略。若更换启动工作目录，默认相对路径会随之变化；可设置 DOCUMENT_STORAGE_DIR 为固定的绝对路径，确保重启或切换启动方式后仍使用同一位置。

不要把 uploads 放进网站静态资源目录。本课不提供下载或在线预览，响应也不返回 object_key 或磁盘路径。备份时需要同时保存数据库和原文件。

## 3. Vue 如何把文件发给后端

打开 frontend/src/views/DocumentView.vue：

```html
<input type="file" accept=".txt,.md">
```

作用：让用户从电脑选择文件。选择后得到 JavaScript 的 File 对象，它包含文件名、大小以及可发送的内容。accept 只是文件选择提示，不能代替后端校验。

再看 frontend/src/api/documents.js：

```js
const form = new FormData()
form.append('knowledgeBaseId', String(knowledgeBaseId))
form.append('file', file)
return (await http.post('/documents', form, { timeout: 30000 })).data
```

作用：把知识库编号和文件组合成同一个 HTTP 请求。实现方式是使用 multipart/form-data，它把表单分为多个部分，一部分是编号，一部分是文件字节。浏览器自动产生分隔各部分的 boundary，因此这里不手动设置 Content-Type。

与此前提交名称和描述的 JSON 请求相比，这次上传的是文件表单。Axios 仍负责发送请求并解析后端返回的 JSON；JWT 仍由统一拦截器附加。本课把 /documents 加进受保护请求名单，保证上传也携带登录身份。

接口为：

```text
POST /api/documents
Content-Type: multipart/form-data; boundary=浏览器自动生成
字段 knowledgeBaseId：知识库编号
字段 file：文件

GET /api/documents?knowledgeBaseId=1
```

## 4. Controller 如何接收文件

打开 backend/src/main/java/com/example/aiknowledge/controller/DocumentController.java：

```java
@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<DocumentInfo> upload(
        @RequestParam long knowledgeBaseId,
        @RequestParam MultipartFile file) {
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(documents.upload(knowledgeBaseId, file));
}
```

@RequestParam 按字段名取出表单内容。编号转换为 long；MultipartFile 是 Spring 提供的上传文件接口，让 Service 可以读取文件名和输入流。上传文件可能先保存在临时位置，需要由应用保存到持久位置；请求结束后不能依赖这个临时对象继续存在。参见 [Spring MultipartFile 文档](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/multipart/MultipartFile.html)。

Controller 负责接收参数和返回 201 Created。校验、保存等业务放在 DocumentService。成功后返回 DocumentInfo，Spring MVC 将它序列化成 JSON，页面据此显示结果。

## 5. Service 按什么顺序处理

打开 service/DocumentService.java，顺着 upload 方法阅读：

1. 校验文件名长度、路径分隔符和控制字符，限定 .txt / .md 后缀。
2. 最多读取“1 MB + 1 字节”。多读一个字节是为了判断是否超限，同时限制内存占用。
3. 拒绝空文件；检查 UTF-8 编码是否合法，并拒绝包含 NUL 字符的内容。
4. 确认知识库存在并锁住对应行，协调上传与知识库删除。
5. 保存原文件，获得随机存储编号。
6. 通过 Mapper 插入 document 元数据，查询并返回 DocumentInfo。

关键代码：

```java
bytes = input.readNBytes(MAX_BYTES + 1);
```

它不依赖浏览器声称的文件大小，而是限制实际读取量。应用还在 application.properties 配置了单文件 1 MB、整次请求 2 MB 的限制；总请求需要容纳表单字段和 multipart 分隔信息。Servlet 层发现超限时返回 413。

UTF-8 检查使用 CharsetDecoder，并设置 CodingErrorAction.REPORT，遇到非法字节就报错，而不是用替代字符悄悄改动原文。后端不会因为 Content-Type 写着 text/plain 就直接相信它。这里是课程范围内的文本校验，不等于杀毒或对任意文件格式的全面识别。

## 6. 文件怎样保存，为什么不用原文件名

打开 service/LocalDocumentStorage.java：

```java
String key = UUID.randomUUID().toString();
Path target = root.resolve(key);
try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
    output.write(bytes);
}
```

UUID 生成内部存储编号。用户提供的 notes.md 只保存在数据库用于显示，不参加磁盘路径拼接，因此同名文件不会互相覆盖，也不会利用文件名写到上级目录。CREATE_NEW 要求目标文件不存在，进一步防止覆盖；try-with-resources 会关闭输出流。

本课允许重复上传同名、同内容文件，每次都产生新文档。文件去重和上传幂等性尚未实现，所以网络异常时不会自动重试：页面会提示先刷新列表，检查服务器是否其实已经保存成功。

## 7. MySQL 事务能自动撤销磁盘写入吗

不能。@Transactional 管理的是数据库事务，普通文件写入不在 MySQL 的事务里。

本课在文件写入成功后注册事务回调：

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void afterCompletion(int status) {
        if (status == STATUS_ROLLED_BACK) storage.remove(key);
    }
});
```

作用：数据库明确回滚后，删除这次新建的文件，避免留下无对应记录的文件。Spring 会在事务完成时通知回调结果，参见 [TransactionSynchronization 文档](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html)。

这是失败补偿，不是数据库与文件系统的原子事务。进程突然退出、清理失败或事务结果无法确定时，仍可能留下孤立文件。状态不确定时保留文件，避免误删可能已提交记录所引用的内容；后续需要对账清理机制。原文件写入失败时，代码会尝试删除写到一半的文件，并且不执行元数据插入。

本课也没有文档删除流程，因此含文档的知识库删除返回 409。上传与删除都先锁住同一知识库行，数据库外键再作为约束保护，防止出现没有所属知识库的文档。

## 8. 谁能上传，以及页面怎样防止旧数据混入

V5 新增 document:read 和 document:upload 两个权限。USER 拥有查看权限；EDITOR 和 ADMIN 拥有查看及上传权限。AuthSecurityConfig 在进入 Controller 前检查具体权限，未登录返回 401，无上传权限返回 403。

页面根据 document:upload 控制上传表单，同时后端再次授权。文档仍是共享知识库的数据，没有实现按上传者隔离。

DocumentView 切换知识库时会取消上一次列表请求，并用 requestId 检查返回结果是不是最新请求；即使旧请求较晚返回，也不会覆盖新知识库的文档。sessionVersion 用于忽略退出登录、切换账号或权限改变前的响应。离开页面时也会取消列表请求。

上传期间禁用重复提交和知识库切换。上传成功后重新读取知识库数量及文档列表。取消浏览器等待不代表取消服务器操作，所以切走页面后已发送的上传仍可能完成。

## 9. 一次上传的完整链路

```text
选择 File → FormData → Axios 附带 JWT
  → Security 认证与 document:upload 授权
  → Controller 接收 MultipartFile
  → Service 校验文件和知识库
  → LocalDocumentStorage 保存原文件
  → DocumentMapper → JDBC → MySQL 保存元数据
  → 提交事务 → JSON 响应 → Vue 刷新文档与数量
```

“已上传”只代表原文件及元数据已经保存。它不代表完成了解析、分块、Embedding 或检索，这些属于后续 RAG 课程。

## 10. 验证与小练习

本课后端测试使用独立数据库、独立临时文件目录及随机 HTTP 端口。覆盖同名文件分别保存、原始字节一致、列表按知识库过滤、真实计数、角色授权、空文件、错误后缀、非法 UTF-8、路径文件名、缺参数、1 MB 边界、超限、知识库不存在及事务回滚清理。

前端测试覆盖文件选择校验、FormData 字段、上传失败不自动重试，以及文档请求附带 JWT。生产构建也需要通过。

本次验证：27 项后端测试、13 项前端测试和生产构建通过。浏览器确认匿名访问文档管理会跳转到登录页；登录后上传行为通过独立测试库的真实 HTTP 请求验证。

练习：上传两次同名文件，解释为什么列表出现两条、磁盘文件却没有覆盖；再试一个空文件和一个超过 1 MB 的文件，观察失败后列表是否保持原样。最后用自己的话说明：MultipartFile、DocumentInfo、object_key 各承担什么职责？

下一课：把原文件存储接入 MinIO，理解对象存储中的 bucket 和 object key。
