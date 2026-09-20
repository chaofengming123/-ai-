# 第 12 课：修改与删除，完成知识库 CRUD

上一课把知识库保存到了 MySQL。这一课继续完成两个操作：修改名称和描述、确认后删除知识库。

完成后，打开知识库页面，每张卡片都有“编辑”和“删除”。编辑成功后刷新仍能看到修改结果；删除会移除数据库中的记录。当前还没有接入真实文档，卡片上的文档数量只是已有字段，本课不处理文件删除。

## 1. CRUD 与接口怎么对应

CRUD 是四种常见数据操作的英文首字母：

| 操作 | 英文 | 本项目接口 | 成功结果 |
| --- | --- | --- | --- |
| 新增 | Create | POST /api/knowledge-bases | 201，返回新记录 |
| 查询 | Read | GET /api/knowledge-bases 或 /api/knowledge-bases/{id} | 200，返回列表或详情 |
| 修改 | Update | PUT /api/knowledge-bases/{id} | 200，返回修改后的记录 |
| 删除 | Delete | DELETE /api/knowledge-bases/{id} | 204，不返回响应正文 |

比如修改编号 5：请求地址确定“哪条记录”，JSON 正文确定“改成什么”。

```http
PUT /api/knowledge-bases/5
Content-Type: application/json

{"name":"产品设计知识库","description":"收录设计规范与评审记录"}
```

这里的 5 是示意编号，练习时要使用自己新建记录的实际编号。不要复制示例请求去操作已有数据。

本项目的 PUT 要提交完整的可编辑字段：名称必填，描述留空或省略时变成“暂无描述”。它不是“只传哪个字段就改哪个字段”的局部修改接口。编号、分类和文档数量由后端维护。

## 2. 卡片负责发出操作意图

文件：`frontend/src/components/KnowledgeBaseCard.vue`。

```vue
<button type="button" :disabled="busy"
  @click="$emit('edit', knowledgeBase.id)">编辑</button>
```

**作用：** 告诉父页面用户想编辑哪条知识库。

**如何实现：** `knowledgeBase` 是父页面传入的 Props。`$emit` 发出名为 `edit` 的事件，第二个参数把编号交给父页面。卡片不发送 HTTP 请求，也不直接改数据。

父页面的接收代码：

```vue
<KnowledgeBaseCard
  :knowledge-base="knowledgeBase"
  :busy="isLoading || isSaving"
  @edit="openEditDialog"
  @delete="openDeleteDialog"
/>
```

`@edit` 把收到的编号传入 `openEditDialog(id)`；`busy` 让等待请求时的编辑与删除按钮禁用。这是第 4 课 Props / Emit 在新功能中的应用。

## 3. 为什么编辑表单要先保存草稿

文件：`frontend/src/views/KnowledgeBaseView.vue`。

```js
const editingId = ref(null)

function openEditDialog(id) {
  const record = knowledgeBases.value.find(item => item.id === id)
  if (!record) return
  editingId.value = id
  name.value = record.name
  description.value = record.description
  error.value = ''
  notice.value = ''
  createDialog.value.showModal()
}
```

**作用：** 找到要编辑的记录，把原值填入表单，打开弹窗。

**如何实现：** `find` 根据稳定的编号查找；`name` 和 `description` 是单独的 `ref`，复制进去的是字符串。因此输入过程中改变的是表单草稿，原来的卡片不会跟着改变。点击“取消”只关闭弹窗，不发请求。

不要直接把表单的 `v-model` 绑定到列表对象的名称上，否则用户还没保存，甚至最后点了取消，卡片就可能已经被修改。

新建和编辑共用一个表单：

```js
const data = { name: name.value, description: description.value }
const result = editingId.value === null
  ? await knowledgeBaseStore.addKnowledgeBase(data)
  : await knowledgeBaseStore.editKnowledgeBase(editingId.value, data)
```

`editingId === null` 表示新建；其他值表示修改该编号。新建入口会重新设置 `editingId.value = null` 并清空输入，防止上一次编辑状态影响下次新建。标题和提交按钮也根据它切换。

## 4. Axios 和 Pinia 怎样完成一次修改

文件：`frontend/src/api/knowledgeBases.js`。

```js
export async function updateKnowledgeBase(id, data) {
  const response = await http.put(`/knowledge-bases/${id}`, data)
  return response.data
}
```

**作用：** 发送 PUT 请求，把后端返回的记录交给调用者。

**如何实现：** 共用的 Axios 实例已有 `/api` 前缀；模板字符串放入编号；第二个参数成为 JSON 请求正文。`await` 等待请求完成，`response.data` 是服务器返回的 JSON 内容。

文件：`frontend/src/stores/knowledgeBases.js`，修改成功的核心：

```js
const record = await updateKnowledgeBase(id, data)
knowledgeBases.value = knowledgeBases.value.map(
  item => item.id === id ? record : item,
)
```

**作用：** 只替换对应编号的记录。

**如何实现：** `map` 逐个访问原数组元素；编号相等时使用服务器的新记录，否则保留原对象，最终生成新数组。数量不变，顺序不变。使用后端返回值，还能同步后端去除空格后的实际名称。

这两行的顺序很重要：先等待后端成功，再更新列表。请求失败会进入 `catch`，不会执行 `map`，页面继续保留此前的数据。

`isSaving` 在发送前设为 true，在 `finally` 中恢复为 false；编辑、删除和加载都有相应等待判断，减少同一页面的重复操作。它不能阻止其他浏览器操作同一数据库，因此后端也必须保护数据。

## 5. Controller 怎样接收修改请求

文件：`backend/src/main/java/com/example/aiknowledge/controller/KnowledgeBaseController.java`。

```java
@PutMapping("/{id}")
public KnowledgeBase update(
        @PathVariable long id,
        @RequestBody SaveKnowledgeBaseRequest request) {
    return service.update(id, request.name(), request.description());
}
```

**作用：** 把 HTTP 请求转换成 Service 方法调用。

- `@PutMapping` 匹配 PUT 方法与地址中的编号。
- `@PathVariable` 把地址中的编号转换成 Java 的 `long`。
- `@RequestBody` 把 JSON 转换为请求 DTO。
- 返回的 `KnowledgeBase` 被 Spring 转换为 JSON 响应。

创建和修改允许输入相同的字段，所以本课把 `CreateKnowledgeBaseRequest` 重命名为 `SaveKnowledgeBaseRequest`，两个接口共用它：

```java
public record SaveKnowledgeBaseRequest(String name, String description) {}
```

DTO 只表达允许输入的字段，避免直接把数据库实体作为可随意修改的请求对象。

## 6. Service 怎样安全地修改一行

文件：`backend/src/main/java/com/example/aiknowledge/service/KnowledgeBaseService.java`。省略重复的重名异常捕获后，主流程是：

```java
@Transactional
public KnowledgeBase update(long id, String name, String description) {
    KnowledgeBaseEntity entity = mapper.findForUpdate(id);
    if (entity == null) {
        throw new KnowledgeBaseException(NOT_FOUND, "知识库不存在。");
    }
    setEditableFields(entity, name, description);
    mapper.updateById(entity);
    return toResponse(entity);
}
```

实际代码使用完整的注解包名，效果相同。

**作用：** 查出原记录，校验并修改可编辑字段，保存后返回结果。

**如何实现：**

1. `findForUpdate` 查找并锁住本次要修改的记录。
2. 找不到就返回 404，避免把不存在的编号当作成功修改。
3. `setEditableFields` 是创建与修改共用的校验函数：去除两端空白，拒绝空白名称，限制名称与描述长度，给空描述设置默认值。
4. 在原实体上只设置名称与描述；编号、文档数量和分类保留原值。
5. `updateById` 根据主键执行 UPDATE；MyBatis-Plus 负责生成 SQL。
6. `toResponse` 转成对外展示的数据对象。

如果名字与另一条记录冲突，MySQL 唯一索引会拒绝写入；Service 捕获 `DuplicateKeyException` 并转为业务异常，最终返回 409。保存自己的原名称不冲突，因为还是同一条记录。

### 事务和行锁在这里解决什么问题

文件：`mapper/KnowledgeBaseMapper.java` 的查询包含：

```sql
SELECT id, name, description, document_count, category
FROM knowledge_base
WHERE id = #{id}
FOR UPDATE
```

`#{id}` 是参数绑定，不是把用户输入直接拼进 SQL。

假设先查出记录，另一个请求随后删除它，当前请求再执行修改，就可能出现“返回成功对象，数据库却没有对应记录”的问题。

`@Transactional` 把读取与修改放在同一个事务中；`FOR UPDATE` 锁住查到的行，直到当前事务提交或回滚。另一条修改或删除同一行的请求需要等待。抛出的业务异常是运行时异常，默认会让事务回滚。

这是本课接触事务的具体原因，暂时不需要背所有隔离级别。它也有边界：锁释放后别人仍然可以删除记录；本项目还没有版本号检测，两个人先后保存同一条记录时，后提交的编辑可能覆盖前一次结果。

## 7. 删除为什么返回 204

Controller：

```java
@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable long id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
}
```

Service：

```java
public void delete(long id) {
    if (mapper.deleteById(id) == 0) {
        throw new KnowledgeBaseException(
            NOT_FOUND, "知识库不存在，可能已被删除。");
    }
}
```

**作用：** 删除指定记录，并准确告知结果。

**如何实现：** `deleteById` 返回受影响的行数。0 表示没有删到记录，返回 404；删到一条返回 204。这里没有需要返回的新对象，所以成功响应不带 JSON 正文。`Void` 表示没有响应体类型。

第一次删除成功，第二次再删会返回 404。两次请求后数据都处于“该记录不存在”的状态，但 HTTP 状态码不必相同。

前端对应代码：

```js
await deleteKnowledgeBase(id)
knowledgeBases.value = knowledgeBases.value.filter(item => item.id !== id)
```

API 函数只等待 `http.delete(...)` 完成，不读取空响应中的字段。`filter` 保留所有编号不同的记录，生成删除后的数组；工作台数量也会通过共享 store 的 getter 重新计算。

## 8. 删除确认和失败提示怎样工作

点击卡片“删除”时，页面只保存 `{ id, name }` 并打开确认弹窗，还没有发送 DELETE。

弹窗明确展示目标名称和不可恢复的后果，默认焦点在“取消”。只有提交确认表单才调用 `confirmDelete`，再调用 store。等待期间按钮禁用，Esc 也不会关闭正在提交的弹窗。成功才关闭并显示通知，失败则保留弹窗和错误提示。

本课做的是物理删除：直接删除 MySQL 中的知识库行，没有回收站。以后接入文档时，需要另行设计关联数据和文件的删除规则。

错误有两类：

- 收到 400、404、409 等明确业务响应：显示后端消息，用户可按提示处理。记录已被别人删除时，关闭弹窗并重新加载列表。
- 网络断开、超时或其他无法确定结果的情况：提示“未能确认操作结果”，让用户关闭弹窗并重新加载核对。服务器可能已经提交，只是响应没有到达浏览器。

本项目不会自动重发这些修改请求。保留旧列表是为了避免凭空显示成功，并不代表服务器一定没有变化。

## 9. 一次修改的完整执行顺序

```text
点击卡片编辑
  → emit 携带编号
  → 父页面复制原值到表单草稿
  → 用户保存
  → Pinia 设置等待状态
  → Axios 发送 PUT
  → Controller 读取编号与 JSON
  → Service 开启事务、读取并锁行、校验
  → Mapper 执行 UPDATE，事务提交
  → 后端返回新记录
  → Pinia 用 map 替换列表项
  → Vue 更新卡片，弹窗关闭
```

删除则是“点击删除 → 确认弹窗 → DELETE → 数据库删除 → 204 → filter 移除列表项”。

现在可以把前面学过的组件事件、响应式数据、共享状态、异步请求、Controller / Service / Mapper 串成一条完整链路。

## 10. 练习与本课验证

建议自己新建“第十二课练习”记录，围绕这条记录练习：

1. 修改名称和描述，保存后刷新，确认数据仍在。
2. 编辑输入后点击取消，确认卡片内容未变化。
3. 尝试改为另一个已有名称，观察重名提示。
4. 点击删除再取消，确认记录仍在；确定不再需要这条练习记录后，再确认删除。

本次已完成的验证：

- 后端打包成功，9 项真实 MySQL HTTP 集成测试通过。新增覆盖修改读回、保留受保护字段、不改名保存、非法输入、重名回滚、删除 204、删除后查询及重复删除 404。
- 前端 2 项共享状态测试与生产构建通过，覆盖保存成功才改列表、等待期间阻止并发操作、失败保留数据与删除空响应。
- 浏览器验证了编辑回填、重名错误、保存、刷新读回、删除弹窗与取消操作。最终删除由真实 API 验证并清理临时记录，未通过浏览器点击最终删除确认。
- 开发库原有 4 条记录的编号和内容完全保留；后端集成测试使用独立测试库。

下一课开始登录基础，先理解用户身份、用户表与密码存储，再逐步接入权限。
