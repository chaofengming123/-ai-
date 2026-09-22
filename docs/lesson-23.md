# 第 23 课：提取文档正文，开始 RAG 文档处理

前面已经完成文件上传、MinIO 保存、原文件下载和流式聊天。本课把两条路线向前推进一步：从已上传文件读取文字，让你在“文档管理”中点击“查看正文”，看到解析结果。

当前只做只读预览，不调用 GLM，不保存分块或向量。上传状态仍是“已上传”，不能把一次成功预览等同于文档已进入 RAG 索引。

## 一、先动手看结果

重启新版后端，前端沿用现有启动方式。macOS 在项目根目录运行：

```sh
scripts/backend.sh spring-boot:run
npm run dev --prefix frontend
```

两个命令分别在两个终端运行；已有后端先停止再启动，避免占用 8080。Windows 启动脚本及 IDEA 环境变量配置继续参考第二十一课。

登录后打开“文档管理”，选择知识库：

1. 对已有 TXT、Markdown 或 DOCX 文件点击“查看正文”。
2. 上传一份能选中文字的 PDF，再查看正文。
3. 对照原文件，看段落、换行和表格是否符合预期。
4. 切换另一个文件或知识库，确认旧预览被清空。

预览最多保留 40000 个 UTF-16 字符；PDF 仅提取前 20 页。超过范围会显示“部分预览”。这套限制用于本课同步预览，不是后续完整文档入库规则。

## 二、校验与解析有什么区别

| 操作 | 回答的问题 | 输出 |
| --- | --- | --- |
| 格式校验 | 文件是否满足本课允许的格式与大小限制？ | 通过，或返回错误 |
| 正文提取 | 文件里哪些内容可以转换为文字？ | 文本字符串与限制说明 |
| 后续分块 | 这些文字应该如何分成检索片段？ | 多个文本块 |
| 后续向量化 | 如何用数字表示文本以便相似度检索？ | 向量 |

例如，一个只有扫描图片的 PDF 可能通过格式校验，却提取不到文本层。提取不到文字不是上传失败，也不能直接推断文件是空的。

## 三、本次完整请求链

```text
用户点击“查看正文”
 → Vue / Axios GET /api/documents/{id}/text，带 JWT
 → Spring Security 检查 document:read
 → DocumentController.text(id)
 → DocumentService.preview(id)
 → DocumentMapper 查询文件记录
 → DocumentStorage 从 LOCAL 或 MINIO 读取原始字节
 → DocumentTextExtractor 按格式提取文字
 ← Preview Java 对象
 ← Spring MVC 序列化为 JSON
 ← Vue 显示文本与预览说明
```

浏览器只提交文档 ID，不能任意指定服务器磁盘路径或 MinIO 对象键。实际位置由数据库记录决定，继续复用前面做好的存储封装。

接口示意响应：

```json
{
  "documentId": 12,
  "fileName": "学习笔记.md",
  "content": "# 学习笔记\n正文内容",
  "truncated": false,
  "note": "按 UTF-8 读取文字，Markdown 保留原始标记。"
}
```

这里的 ID 是示例，实际值来自上传记录。`truncated` 表示因页数或字符预算只返回部分内容；即使它为 false，也不代表已恢复图片、排版等所有信息。

## 四、Controller 与 Service 的关键代码

文件：`backend/src/main/java/com/example/aiknowledge/controller/DocumentController.java`。

```java
@GetMapping("/{id}/text")
public ResponseEntity<DocumentService.Preview> text(@PathVariable long id) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(documents.preview(id));
}
```

`@PathVariable` 从 URL 取出文档 ID，Controller 调用 Service 并返回结果。`noStore()` 要求 HTTP 缓存不要保存正文响应。鉴权由 Security 配置在进入 Controller 前完成。

`DocumentService.preview()` 先取得一个预览名额，再调用原有 `download(id)`：它会检查文档是否存在，读取存储内容，并核对字节数与数据库记录的 fileSize 是否一致。然后才调用提取器。

```java
var file = download(id);
var text = DocumentTextExtractor.extract(file.name(), file.bytes());
return new Preview(id, file.name(), text.content(), text.truncated(), text.note());
```

进程最多同时执行两次正文预览，使用 `Semaphore(2)` 控制；繁忙时返回 503，不继续堆积解析工作。`finally` 释放名额，解析失败也不会一直占着名额。

这次没有新增数据库表或 Flyway 迁移，也没有更新文档状态。每次点击都会重新读取文件并解析，关闭页面后丢弃预览。后续完整入库才会引入处理状态和持久化结果。

## 五、TXT / Markdown：解码字节

文件：`backend/src/main/java/com/example/aiknowledge/service/DocumentTextExtractor.java`。

文本文件最直接：先复用格式校验，拒绝无效 UTF-8 和空字节，再按 UTF-8 解码为字符串。Markdown 保留 `#`、`**` 等标记，没有转换成 HTML。

```java
output.write(new String(bytes, StandardCharsets.UTF_8));
```

字节是文件实际存储的数据，Java String 是解码后的文字，两者长度不相等。一个中文字符通常需要多个 UTF-8 字节，所以 1 MB 文件限制与 40000 字符预览限制解决的是不同问题。

返回前统一 CRLF / CR 为 LF，移除文件开头的 UTF-8 BOM 对应字符，方便前端显示。没有删除普通段落换行，也没有让模型“润色”正文。

## 六、PDF：提取文本层

```java
try (var pdf = Loader.loadPDF(bytes)) {
    var stripper = new PDFTextStripper();
    stripper.setSortByPosition(true);
    stripper.setStartPage(1);
    stripper.setEndPage(MAX_PDF_PAGES);
    stripper.writeText(pdf, output);
}
```

`Loader.loadPDF` 读取 PDF 文档结构，`PDFTextStripper` 从文本层提取字符，`writeText` 将结果交给受限 Writer。try-with-resources 保证解析结束后关闭文档。

PDF 主要描述页面如何绘制，不保证内部文字顺序就是人眼阅读顺序。`setSortByPosition(true)` 尝试按位置排序，但多栏、跨页表格等仍可能出现顺序偏差。[PDFBox 官方 FAQ](https://pdfbox.apache.org/3.0/faq.html)也说明了文本顺序与扫描图片无法直接提取文字的问题。

本课不做 OCR。扫描文件需要识别图片中的文字，那是另一条处理流程。输出为空时返回明确说明，不制造一段正文代替原文。

## 七、DOCX：读取安全解析后的主文档 XML

DOCX 是包含多个部件的 ZIP 包，主文档通常位于 `word/document.xml`。

第二十课的 `DocumentFormatValidator.docx()` 原先只验证结构。本课让它在校验成功后返回已经安全解析的 XML 根元素；上传仍然只关心是否通过，正文提取则复用这个结果。

这样不会另开一个绕过限制的解压入口：仍检查 ZIP 项数、解压大小、CRC、主文档关系，并禁止 XML DOCTYPE 和外部资源读取。

提取器从 body 开始遍历主文档节点：

| XML 元素 | 本课处理方式 |
| --- | --- |
| `w:t` | 取文字内容 |
| `w:tab` | 输出制表符 |
| `w:br` / `w:cr` | 输出换行 |
| `w:p` | 遍历段落后换行 |
| `w:tc` / `w:tr` | 遍历表格内容，并加入简单分隔 |

代码同时支持常见与 Strict Word 命名空间。删除修订、移出修订和图形内容会跳过；页眉页脚、批注、图片、文本框、自动编号不在这次提取范围。表格输出是文字顺序，不恢复原始单元格布局。

## 八、为什么使用受限 Writer

如果先提取整篇字符串再取前 40000 字符，提取过程已经占用了整篇文本的输出内存。本课的 `PreviewWriter` 每次写入时检查剩余空间：

```java
int available = MAX_CHARACTERS - value.length();
value.append(chars, offset, Math.min(available, length));
if (length > available) throw new PreviewLimit();
```

达到上限后用内部异常停止继续输出，保留已经提取的前缀，并设置 truncated。这个异常代表预览预算用完，不是文件损坏。

预算按 Java / JavaScript 的 UTF-16 长度计数，emoji 可能占两个单位。截断后如果最后只剩一个高位代理字符，会移除它，避免显示半个 emoji。

这些限制约束文件输入、解析页数、输出和并发，并不是严格的 CPU / 内存沙箱。复杂 PDF 在解析单页时仍可能耗时。后续批量入库需要后台任务、超时及更完善的资源隔离，不能直接把同步预览接口改成无限页数。

## 九、Vue 如何避免旧预览覆盖新文件

`frontend/src/api/documents.js` 新增 `fetchDocumentText()`，复用 Axios 实例，因此已有拦截器会给本地文档接口附带 JWT，并处理 401/403。

`frontend/src/utils/documentPreview.js` 管理预览数据、正在读取的文档 ID 和错误：

- 选择另一个文档时，先关闭旧预览并取消旧请求。
- `generation` 区分每次预览操作；旧请求完成时不能覆盖新预览。
- `sessionVersion` 检查账号是否仍是原来的登录会话。
- 切换知识库、刷新列表或离开页面时清空预览。

显示正文使用：

```vue
<pre class="document-text-preview">{{ preview.content }}</pre>
```

Vue 插值将内容作为文字显示，不执行文件中的 HTML。CSS 保留换行、允许长行换行，并限制预览区高度。接口里的文字仍是用户上传的数据，后续进入 RAG 提示词时也不能把它视为系统指令。

## 十、验证与理解检查

本课测试覆盖 UTF-8、BOM、换行、emoji 截断、DOCX 段落与表格、Strict 命名空间、PDF 文本层与空页，以及畸形文件和 XML 外部实体拒绝。接口测试验证未登录 401、无权限 403、不存在文档 404、正文只读与 no-store；MinIO 测试验证解析前后原文件字节保持不变。

当前 document:read 仍是共享文档的查看权限，不代表已经实现个人文档或租户隔离。后续检索也必须延续并完善服务器端权限过滤。

思考：

1. 为什么 PDF 上传成功后仍可能提取不到文字？
2. 为什么解析过程不需要调用 GLM？
3. 为什么原始文件保留在 MinIO，提取文字却可以只存在内存？
4. 为什么“未截断”也不等于完整恢复了文档？
5. 为什么要在 Writer 写入时限制长度？

下一课学习文本分块：把提取出的文字切成适合检索的片段，观察块大小、重叠与段落边界的作用。本课预览上限不能被误当成完整文档入库结果。
