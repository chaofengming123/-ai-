# 第 20 课：PDF、DOCX 上传与格式校验

本课把文档上传扩展为 TXT、Markdown、PDF、DOCX 四种格式。上传、MinIO 保存和原文件下载沿用前两课，新增的是“如何判断文件内容符合所选格式”。

先记住一个区别：文件名以 .pdf 结尾，不代表内容就是 PDF；浏览器声明它是 PDF，也不能代替服务器检查。

## 1. 本课可以怎样体验

使用已有的启动方式，在 IDEA 中重启后端，或者先停止旧后端，再从项目根目录运行：

```sh
scripts/backend.sh spring-boot:run
```

需要 MySQL 与 MinIO 已启动；本课无需新增环境变量，也不新增数据库迁移。不要同时启动两份 8080 后端。

用 EDITOR 或 ADMIN 账号登录，进入“文档管理”，选择知识库，上传一个不超过 5 MB 的 PDF 或 DOCX，再点击“下载原文件”。普通用户可以查看、下载，仍然不能上传。

可以在 Word 中创建一段文字，保存为 DOCX，再导出为 PDF，分别上传。下载后的内容应与原文件一致。不要把 .doc 文件直接改名成 .docx，需要用 Word 实际另存为 DOCX。

本课的范围：

| 格式 | 检查内容 | 限制 |
| --- | --- | --- |
| TXT / Markdown | UTF-8 编码、无 NUL 字符 | 非空，最多 5 MB |
| PDF | 文件头、可读取的 PDF 结构、页数、加密状态 | 未加密，1–500 页，最多 5 MB |
| DOCX | ZIP 结构、Word 主文档声明与关系、必要 XML | 无宏，最多 5 MB；内部最多 128 项，单项解压最多 4 MB，合计最多 8 MB |

这里的 MB 沿用前两课：5 MB = 5,242,880 字节。DOC、DOCM、加密 Word 文件及超出上述限制的文档暂不支持。上传成功后的状态仍是 UPLOADED，不表示已经提取正文或建立检索索引。

## 2. 后缀、MIME 与内容各是什么

| 信息 | 由谁提供 | 能说明什么 |
| --- | --- | --- |
| 文件后缀 | 文件名，如 report.pdf | 用户希望按什么类型处理 |
| MIME / Content-Type | 客户端请求，如 application/pdf | 客户端对内容类型的声明 |
| 实际字节与内部结构 | 上传文件本身 | 服务器能实际检查的内容 |

用户可以改文件名，程序也可以伪造请求头。因此前端先用后缀提示用户，后端再按允许的类型检查字节与结构。

本课没有根据 getContentType() 直接放行。正确的 PDF 即使客户端标成 application/octet-stream，也会按实际 PDF 结构检查；普通文本即使声称 application/pdf，也不能直接通过。

## 3. 关键设计：把格式判断拆出 Service

打开 backend/src/main/java/com/example/aiknowledge/service/DocumentService.java：

```java
DocumentFormatValidator.validate(type, bytes);
```

这行位于大小检查之后、保存文件之前。作用是：只有格式校验通过，才会继续锁定知识库、写入 MinIO 和插入 MySQL。

DocumentService 负责协调业务步骤；新的 DocumentFormatValidator 专门负责判断格式。它不保存文件，不调用 Mapper，也不产生检索正文，便于单独测试各种合法和错误输入。

```java
switch (type) {
    case "txt", "md" -> text(bytes);
    case "pdf" -> pdf(bytes);
    case "docx" -> docx(bytes);
    default -> throw invalid("支持的格式为 TXT、Markdown、PDF 和 DOCX。");
}
```

type 来自经过长度和路径校验的文件名，后缀统一转成小写，因此 .PDF 与 .pdf 按同一种格式处理。新增格式时主要扩展校验分支，不需要重写上传与下载接口。

## 4. PDF 为什么不能只检查文件头

PDF 通常以 %PDF- 开头。代码首先检查这个标识，快速拒绝明显不是 PDF 的内容，但单独写几个字符也能伪造文件头，所以还要实际读取结构。

本课使用 Apache PDFBox 3.0.8。Loader 是 PDFBox 3.x 加载文档的入口，支持传入字节数组，参见 [PDFBox 3.0 迁移指南](https://pdfbox.apache.org/3.0/migration.html)；版本来自 [官方发布页面](https://pdfbox.apache.org/download)。

```java
try (var document = Loader.loadPDF(bytes)) {
    if (document.isEncrypted()) {
        throw invalid("本课暂不支持加密 PDF，请先导出为未加密文件。");
    }
    int pages = 0;
    for (var page : document.getPages()) {
        if (++pages > 500) throw invalid("本课 PDF 最多支持 500 页。");
    }
    if (pages == 0) throw invalid("PDF 必须包含至少一页。");
}
```

作用与执行顺序：Loader 尝试读取文档结构；读取成功后检查加密状态，再遍历页面并限制数量。无法读取、需要密码或超过本课限制时返回错误。try-with-resources 在离开代码块时关闭文档资源。

这里没有调用 save，也没有用 PDFBox 重新生成上传内容。校验通过后仍把原始 bytes 交给存储层，保证下载得到的是用户原文件。

这属于基本可读性检查，不是完整 PDF 标准符合性验证，也没有逐一执行页面绘制指令。PDFBox 可能容忍部分非标准结构；更严格的格式验收和隔离解析属于后续工程能力。

## 5. DOCX 为什么要检查 ZIP 和 XML

DOCX 是一种由 ZIP 包组织多个部件的 Office Open XML 文档。主文档内容使用 WordprocessingML，通常位于 word/document.xml；部件之间通过关系关联。参见 [Microsoft 文档结构说明](https://learn.microsoft.com/en-us/office/open-xml/word/structure-of-a-wordprocessingml-document)与[包和部件关系](https://learn.microsoft.com/en-us/office/open-xml/general/how-to-create-a-package)。

本课检查常见 Word 导出结构中的三个文件：

```text
document.docx
├── [Content_Types].xml   各部件类型的声明
├── _rels/.rels          包到主文档的关系
└── word/document.xml    Word 主文档 XML
```

只是一个 ZIP 包还不够：把照片压成 ZIP 再改成 .docx，并不会拥有这些 Word 结构。

校验器使用 ZipFile 读取中央目录，依次检查内部路径、重复条目、条目数量、解压大小和 CRC。它不会把内部文件解压到这些路径，而是最多读取限定字节用于检查。

```java
expanded = input.readNBytes(MAX_ENTRY_BYTES + 1);
total += expanded.length;
if (expanded.length > MAX_ENTRY_BYTES || total > MAX_EXPANDED_BYTES) {
    throw invalid("DOCX 解压后的内容过大……");
}
```

为什么上传已经限制 5 MB，还要限制解压后的大小？ZIP 能把大量重复内容压得很小。服务器读取内部数据时消耗的是解压后的内存，所以必须依据实际读取量再次设限，而不能只信压缩包声明的大小。

本课设置：每项最多 4 MB，合计最多 8 MB，最多 128 个目录或文件项。超出限制的文档可能是正常但复杂的 Word 文件；本课会拒绝它，并显示原因。

CRC 用于检查压缩条目的数据一致性，不是证明文件可信的密码学签名。临时 ZIP 文件由服务器随机创建，结束时删除；原上传内容仍保持不变。

## 6. 找到 XML 后，为什么还要检查关系

三个文件存在不代表它们描述的是同一个有效 Word 主文档。代码还检查：

1. [Content_Types].xml 是否声明 /word/document.xml 为标准 Word 主文档类型。
2. _rels/.rels 是否通过内部关系指向 word/document.xml。
3. 主文档的根节点及命名空间是否为 Word document，并且包含一个 body。

命名空间用于区分不同 XML 词汇。例如随便写一个 <document> 标签，不代表它就是 Word 的 document。

本课支持常见的 Word 主文档位置，以及 Transitional / Strict 两种 Word 命名空间。它不是完整 OOXML 校验器，不逐个验证所有附件、图片、样式和关系。具有其他合法主部件布局的文件也可能被拒绝；此时可先用 Word 重新保存为普通 DOCX。

此外，.docm 不在后缀白名单中；包内出现 vbaProject.bin 或主部件声明为宏启用类型时也会拒绝。文件不会在服务器上通过 Office 打开或执行。

## 7. 为什么 XML 解析需要显式配置

XML 能包含声明和对外部资源的引用，上传文件不应该决定服务器去读哪个本地文件或访问哪个网址。

```java
factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
```

本课关闭 DOCTYPE、外部 DTD 和 Schema 访问，也关闭 XInclude，并把元素嵌套深度限制为 128。解析错误时只返回简明提示，不把上传的 XML 内容打印给用户。

这些措施限制当前检查器处理文件的方式，不等于完成了杀毒或任意恶意文件的全面识别。面对公共上传和复杂文档解析，还需要隔离工作进程、超时与资源限制等机制。

## 8. 前端与错误提示怎样配合

打开 frontend/src/utils/documents.js，后缀白名单已扩展为：

```js
/\.(txt|md|pdf|docx)$/i
```

DocumentView.vue 的文件选择器也同步设置 accept=".txt,.md,.pdf,.docx"。前端快速检查有没有选文件、后缀和大小；真正的 PDF／DOCX 结构校验由服务器执行。

格式不正确返回 400，并说明如“PDF 结构无法读取”“DOCX 缺少必要 XML”等原因。文件太大仍返回 413；未登录和权限不足仍分别返回 401、403。

校验发生在存储之前，所以失败文件不会生成新的 MinIO 对象或 MySQL 文档记录。原来的其他文档保持可用；按钮的禁用、防重复提交和会话检查沿用第十八课。

## 9. 一次 PDF 上传经过哪些步骤

```text
前端选择 PDF → 后缀和大小初筛 → FormData + JWT
  → Security 检查上传权限 → Controller 接收 MultipartFile
  → Service 读取限定大小的字节 → PDF 格式校验
  → MinIO 保存原字节 → MySQL 保存元数据
  → 返回 UPLOADED → 页面显示 PDF 类型 → 可下载原文件
```

数据库的 file_type 原本就是字符串字段，因此本课直接存 pdf / docx，无需加新列。MinIO 也只保存字节，不需要为了新增格式增加一套对象存储服务。

## 10. 验证与小练习

自动测试生成合法 PDF 与 DOCX，验证上传与下载后字节一致，并覆盖假后缀、损坏文件、加密或空 PDF、超过页数限制、缺失主部件、外部关系、宏文件、XML 外部引用、过深嵌套、ZIP 条目数及解压大小限制。测试同时检查失败后没有增加文档记录或对象。

本次通过 40 项后端测试、13 项前端测试及生产构建。后端测试使用独立数据库、临时目录、随机 MinIO 测试桶和随机端口；没有修改开发库已有文档，也没有停止你在 8080 运行的后端。

练习时可以把普通 TXT 的副本改成 .pdf 上传，再与实际导出的 PDF 比较结果。思考：为什么浏览器可以选中两个文件，而只有真正的 PDF 能通过？为什么上传成功仍然不代表 AI 已经能回答文件里的问题？

到这里，Phase 5 的四种格式上传与 MinIO 保存已在本课限制内实现。下一课按手册进入基础 LLM Chat，先理解模型请求、消息角色与回复，再进入 RAG 的文档解析和检索。
