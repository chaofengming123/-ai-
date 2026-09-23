# 第 30 课：知识库多文档检索与问答

第 29 课需要先选中一份文档。本课改为选中一个知识库，让后端检索其中多份文档，再把合并后的片段交给 GLM。单文档入口继续保留，便于比较结果。

## 一、先体验完整流程

1. 保持 MySQL、MinIO、Qdrant 运行，停止自己原来的后端，再启动新版。不要同时启动两份后端占用 8080。
2. 沿用硅基流动 Embedding 和智谱 GLM 配置，本课不需要新密钥或数据库迁移。
3. 打开“文档管理”，选择一个练习知识库。用编辑者或管理员上传 [上传要求](samples/upload-policy.md) 与 [访问要求](samples/access-policy.md)。
4. 分别打开两份文档的“文档索引”，建立成功索引。上传成功不代表已经索引。
5. 在页面上方“知识库检索与问答”输入：“上传文件有什么限制，哪些用户可以上传和提问？”
6. 先点击“查找相关片段”，核对来源；再点击“生成知识库回答”，查看答案与文件名。

两个按钮分别发起独立请求。生成回答会在后端重新检索，不使用浏览器上一次显示的原文。真实模型不一定每次引用两份文件，应结合实际来源核对。

本课最多接受 5 份模型兼容的成功索引，超过就明确拒绝，请用较小的练习知识库。Windows 共用 Mac 数据时，更新并重启 Mac 的后端即可。

## 二、执行顺序

```text
Vue 发送知识库 ID 与问题
  → Security 检查三项权限
  → Controller 接收 Java 请求对象
  → Service 查询该知识库的文档与已发布索引
  → 筛选模型兼容的成功索引
  → 问题生成一次 Embedding
  → 依次检索每份文档，各取最多 3 段
  → 合并、按相似度排序、原文去重、选最多 3 段
  → 检查知识库文档和索引版本是否变化
  → 仅查找：返回片段
  → 生成回答：GLM → 校验 JSON 和引用 → 再检查版本 → 返回
```

MySQL 负责“哪些文档属于这个库、哪个索引版本有效”；Qdrant 负责“哪些向量更接近问题”；GLM 负责根据选中的原文组织回答。查询阶段使用已存入 Qdrant 的原文，不重新从 MinIO 下载文件。

## 三、Controller 接收什么

`KnowledgeBaseRagController.java` 提供两个接口：

| 接口 | 用途 |
| --- | --- |
| `POST /api/knowledge-bases/{id}/search` | 返回跨文档检索结果 |
| `POST /api/knowledge-bases/{id}/answer` | 检索后生成回答 |

请求体为 `{"query":"上传有哪些要求？"}`。`@PathVariable` 接收知识库 ID，`@RequestBody Query` 把 JSON 绑定为 Java record，`@AuthenticationPrincipal Jwt` 获取已认证的用户身份。

Controller 将这些值交给 `KnowledgeBaseRagService.execute()`。最后返回的 Java record 由 Spring 的 JSON 消息转换器序列化；Axios 解析响应 JSON，Vue 用它更新页面。成功响应设置 `Cache-Control: no-store`。

Security 要求同时拥有 `knowledge-base:read`、`document:read`、`chat:send`。用户 ID 还用于防止同一用户重复请求。这里沿用团队共享知识库规则，没有新增个人知识库隔离。

## 四、先决定哪些文档可以参与

Service 的 `snapshot()` 调用 `documents.list(baseId)`，再通过 `indexes.find()` 查每份文档的索引状态。前端不能上传任意文档 ID 列表或伪造上下文，检索范围由后端确定。

判断依据是有没有 `activeCollection`，以及 `activeSpace` 是否等于当前 `embedding.spaceId()`：

- 没有成功索引：加入 skipped，说明“尚无成功索引”。
- 模型空间不兼容：加入 skipped，说明“模型配置不兼容”。
- 有兼容的已发布版本：加入 eligible，参与检索。

重建中的文档或最近重建失败的文档，仍可能保留旧成功版本。它们可以继续检索，返回 `usingPreviousVersion` 提示。不能只看状态是否 READY 就断定没有可用索引。

如果 eligible 超过 5，返回 400，不会偷偷只取前 5 份。页面同时显示总文档数、参与数量和跳过原因。“检索了两份”不等于“整个知识库的全部内容都已被回答模型阅读”。

## 五、为什么问题只生成一次向量

关键代码位于 `KnowledgeBaseRagService.java`：

```java
var vector = embedding.embed(List.of(question.strip())).get(0);
for (var item : eligible) {
    var found = search.searchWithVector(item.document().id(), question.strip(), vector);
    // 收集该文档返回的片段
}
```

`strip()` 去除问题两端空白；`embed()` 把问题转成数值数组；循环把同一个数组用于各文档检索。如果直接循环调用旧的 `search()`，同一个问题会重复请求向量模型。本课为 `DocumentSearchService` 增加 `searchWithVector()`，复用后续校验与查询代码。

这不是持久缓存：当前请求结束后不保存问题向量，下次点击按钮仍会重新生成。没有参与文档时，不请求 Embedding。

被查询的索引需属于同一模型空间。检索继续检查维度、Cosine 距离类型、文档归属、来源字段与索引版本。不能因为两组向量长度相同就认为可以混用。

## 六、多份文档的结果怎样合并

每份最多返回三个片段，五份文档最多形成十五个候选。排序代码是：

```java
candidates.sort(Comparator.comparingDouble(Hit::score).reversed()
    .thenComparingLong(Hit::documentId)
    .thenComparingInt(Hit::chunkIndex));
```

`reversed()` 把相似度改为从高到低。分数一样时按文档 ID、块编号排序，避免相同分数下来源顺序随意变化。同一模型空间和距离类型使跨集合比较有意义，但分数不是答案正确的概率。

接着用 `HashSet<String>` 保存已经选过的原文：

```java
if (!seen.add(hit.text())) continue;
```

`add()` 返回 false 表示这段文字已经出现，跳过重复候选。这里只去除完全相同的字符串，不会判断两段意思是否相近。重复内容只保留排序更靠前的一份来源，不会列出所有包含同样文字的文档。

最终最多选择三个片段，每段仍受索引块上限 800 个 UTF-16 字符约束，原文总量最多 2400 字符；问题、提示词与 JSON 包装另算，字符数也不等于 token 数。

这是“每文档 Top 3 → 合并去重 → 最终 Top 3”。去重后可能不足三段，即使其他文档还有未返回的独特块，本课也不追加查询。因此它不保证是全库去重后的精确前三名，也不保证每份文档至少出现一次。

## 七、跨文档引用为什么要重新编号

两份文件都可能有 `chunkIndex=0`，因此块编号不足以唯一标识来源。合并后重新分配本次 `sourceId=1,2,3`，每条 Hit 同时保存 documentId、fileName、chunkIndex、原文、偏移和索引时间。

模型只看到本次 sourceId 和原文。返回编号后，后端执行：

```java
generated.sourceIds().stream().map(number -> selected.get(number - 1)).toList()
```

编号 1 对应列表下标 0。文件名、文档 ID 和原文全部来自后端真实候选，不能让模型自己编造来源元数据。

第 29 课的生成逻辑抽到 `GroundedAnswer.generate()`，单文档和知识库问答共同使用。它组织固定 system 规则与包含问题、片段的 user JSON，调用 `LlmClient.complete()`，再检查资料不足标记、答案长度、引用编号与输出截断。

没有候选时直接返回资料不足，不调用 GLM；有候选时仍可能资料不足。普通答案必须有有效且不重复的引用，资料不足必须没有引用。生成入口仍先要求 LLM 本地配置完整。编号有效只证明来源存在，不证明每句话都有依据，需阅读引用原文核实。

## 八、处理中数据变化或请求失败怎么办

`versions()` 将文档 ID 映射到已发布集合与模型空间，`unchanged()` 在检索后、生成后重新读取并比较。成员列表或索引记录发生变化时返回 409，提示重新提交。每份文档检索自身也继续校验版本。

这种检查是时点检查，不是跨 MySQL、Qdrant 和模型服务的数据库事务；响应后的更新不会自动改写旧答案。

未索引文件可以提前明确跳过，但已经参与的某份文档若出现 Qdrant 错误、来源异常或索引冲突，则整个请求失败。这样不会把临时漏搜包装成一次完整成功的查询。

同一用户同时只允许一个知识库请求，单后端进程最多两个；`finally` 确保失败也释放名额。这与其他聊天入口的并发限制分别计算，不是整个集群的统一限流。

目前顺序查询各文档，适合本课的小规模练习。前端等待上限为 300 秒，上游沿用各自超时，并不是服务端存在统一的 300 秒总截止时间。页面取消接收也不保证上游立即停止，失败不会自动重试。

## 九、Vue 如何展示并清理状态

新增 `KnowledgeBaseAskPanel.vue`，由 `DocumentView.vue` 传入当前知识库 ID：

```vue
<KnowledgeBaseAskPanel v-if="selectedId && canRead"
  :key="selectedId" :base-id="Number(selectedId)" />
```

`base-id` 决定请求地址；`:key` 变化让 Vue 销毁旧组件、创建新组件，避免切换知识库后还显示上个库的答案。`onBeforeUnmount(reset)` 清理请求状态。

两种按钮共用 busy，响应增加 kind 区分查找与回答。`createVectorStorage()` 继续检查请求代次及登录会话版本，丢弃过时响应。回答和原文使用 Vue 插值作为文本显示，不执行其中的 HTML。

本课回答不保存到 MySQL，不携带上一轮聊天历史。检索把问题发送到硅基流动；生成还会把选中的原文发送到智谱，模型密钥始终留在后端。

## 十、验证与练习

本次通过 110 项后端测试、32 项前端测试与生产构建。新增检查覆盖一次向量调用、跨文档排序与去重、跳过原因、超限拒绝、参与文档失败、来源编号映射、生成期间索引变化，以及真实 HTTP 的知识库范围和三项权限。MySQL、MinIO、Qdrant 使用测试数据；模型返回使用模拟数据，未调用真实服务商。

练习：

1. 暂不索引第二份文件，查找一次；再建立索引，观察参与数量与来源变化。
2. 对两份文件共同涉及的问题提问，解释回答中各来源分别来自哪里。
3. 问“公司客服电话是多少？”，核对资料不足判断是否合理。
4. 解释为什么查五份文档仍只生成一次问题向量，为什么去重后不一定凑齐三个片段。

下一步观察检索质量：回答错误究竟来自没有检索到正确片段，还是模型没有正确使用片段，再逐步学习高级检索方法。
