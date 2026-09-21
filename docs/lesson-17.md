# 第 17 课：角色与权限关系表（RBAC）

本课把第十六课的单个 role 字段升级为用户、角色、权限之间的关联关系。账号密码不变，原有 USER / ADMIN 角色迁移保留。登录及 /me 响应从 role 字符串变成 roles 和 permissions 数组，前后端需要同时升级。

## 1. 本课解决什么问题

只有 USER 和 ADMIN 时，我们把所有写操作都交给管理员。如果想让某个同事编辑知识库，却不让他删除，就需要把“编辑”和“删除”拆成独立权限。

RBAC 是 Role-Based Access Control，即基于角色的访问控制：用户获得角色，角色包含权限。一个用户可以有多个角色，其有效权限是这些角色权限的并集。

| 初始角色 | 查看 | 新建 | 编辑 | 删除 |
| --- | --- | --- | --- | --- |
| USER 普通用户 | ✓ | | | |
| EDITOR 编辑者 | ✓ | ✓ | ✓ | |
| ADMIN 管理员 | ✓ | ✓ | ✓ | ✓ |

这些是数据库迁移写入的初始配置。ADMIN 也不是绕过检查的特殊身份；如果数据库取消它的删除权限，它同样不能删除。

## 2. 数据库如何表示关系

打开 backend/src/main/resources/db/migration/V4__relational_rbac.sql。

~~~text
app_user（已有账号表）
  ↓ app_user_role：user_id + role_id
app_role（角色，如 EDITOR）
  ↓ app_role_permission：role_id + permission_id
app_permission（权限，如 knowledge-base:update）
~~~

app_role 与 app_permission 存放定义，两张中间表存放关系。比如用户 7 同时拥有 USER 和 EDITOR，就在 app_user_role 中保存两行，而不是把多个角色用逗号拼进一个字段。

关键代码：

~~~sql
PRIMARY KEY (user_id, role_id),
FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
~~~

联合主键让同一用户不能重复绑定同一角色。外键保证关联的用户真实存在；删除账号时，只级联删除它的角色关联，不删除角色定义，更不会删除知识库。

迁移先建立四张表、写入初始角色和权限，再把旧 app_user.role 转成关联记录，最后删除旧字段。已经应用过的 V1–V3 不修改，Flyway 只执行新增的 V4。MySQL 的建表、改表不是整个脚本统一回滚；正式部署前应备份并安排迁移窗口，失败时需先检查实际状态，不能盲目重跑。

## 3. Mapper 如何找出用户权限

打开 backend/src/main/java/com/example/aiknowledge/mapper/UserAccessMapper.java。

~~~java
@Select("SELECT DISTINCT p.code FROM app_permission p "
      + "JOIN app_role_permission rp ON rp.permission_id=p.id "
      + "JOIN app_user_role ur ON ur.role_id=rp.role_id "
      + "WHERE ur.user_id=#{userId} ORDER BY p.code")
List<String> permissions(long userId);
~~~

作用：输入用户编号，返回权限字符串列表。实现过程：先找到用户的角色关联，再找到角色授予的权限，最后读取权限 code。多个角色可能同时授予查看权限，所以 DISTINCT 去重。ORDER BY 保持稳定顺序。参数通过 #{userId} 绑定，不能用字符串拼接用户输入。

这个 Mapper 使用 MyBatis 的注解 SQL；原先 UserMapper 仍继承 MyBatis-Plus 的 BaseMapper，负责常规账号查询。复杂关联查询可以自己写 SQL，与 MyBatis-Plus 的通用方法一起工作。

## 4. 注册为什么增加事务

打开 service/UserService.java 和 service/UserAccessService.java。

~~~java
@Transactional
public UserResponse register(String username, String password) {
    // 校验、密码哈希、插入账号……
    access.assignDefaultRole(entity.getId());
    return access.profile(entity);
}
~~~

现在注册要做两次写入：创建 app_user，再写入 app_user_role。事务让它们作为一个整体提交；如果默认 USER 角色缺失，assignDefaultRole 检查受影响行数并抛出异常，账号插入也回滚，避免创建只有账号却没有默认角色的半成品。

注册请求依然只能提交用户名与密码。请求里额外写 roles、permissions 或 ADMIN 不会授予权限。UserAccessService.profile 查询两组关联并组装 UserResponse；Controller 返回它后，Spring MVC 的 JSON 转换组件负责序列化。

返回结构示例：

~~~json
{
  "id": 7,
  "username": "learner",
  "roles": ["EDITOR", "USER"],
  "permissions": ["knowledge-base:create", "knowledge-base:read", "knowledge-base:update"]
}
~~~

## 5. Spring Security 如何做决定

打开 config/AuthSecurityConfig.java。

~~~java
return access.permissions(user.getId()).stream()
    .map(code -> (GrantedAuthority) new SimpleGrantedAuthority(code)).toList();
~~~

作用：把数据库权限转成 Spring Security 认识的 GrantedAuthority。SimpleGrantedAuthority 保存一个权限字符串。这里直接使用 knowledge-base:update，不再增加 ROLE_ 前缀。

~~~java
.requestMatchers(HttpMethod.PUT, "/api/knowledge-bases/*")
.hasAuthority("knowledge-base:update")
~~~

作用：匹配修改请求，要求当前用户拥有完全相同的权限字符串。查看、新建、删除分别匹配 read、create、delete；其他知识库请求默认拒绝。规则按匹配顺序执行。

JWT 依旧只证明身份，每次请求都从数据库加载权限。角色分配或角色权限关联发生变更后，下一次请求就采用新配置，不需要重新签发 JWT。已经进入业务逻辑的请求不会被追溯取消。本课没有权限缓存，查询更直观，但请求会增加数据库读取，未来再讨论缓存失效。

完整执行顺序：

1. Vue 发出修改请求，Axios 附带 Bearer Token。
2. Spring Security 校验 JWT 的签名、有效期、签发者和账号。
3. UserAccessMapper 查询该用户通过角色获得的权限。
4. Security 检查 knowledge-base:update；未登录返回 401，已登录但无权限返回 403。
5. 通过后才进入 Controller → Service → Mapper → JDBC → MySQL。
6. 业务结果通过响应对象转成 JSON，Axios 解析为 JavaScript 对象，Vue 更新页面。

无角色账号可以访问 /me 查看身份，但不能访问知识库：认证成功不等于拥有业务权限。

## 6. Vue 为什么也检查权限

打开 frontend/src/utils/permissions.js：

~~~js
export function hasPermission(user, action) {
  return Array.isArray(user?.permissions)
    && user.permissions.includes(`knowledge-base:${action}`)
}
~~~

作用：检查响应中是否存在准确的权限码。数组缺失时默认不允许。前端不再看到 ADMIN 就直接放行，而是分别控制新建、编辑、删除按钮。卡片通过 canEdit、canDelete 两个 Props 接收决定。

前端检查负责合理展示；后端检查负责真正保护数据。浏览器内容可以被修改，所以隐藏按钮不能代替后端授权。

auth store 核对 /me 时，如果权限列表变化，就清空知识库 store 并改变 sessionVersion，销毁旧 KeepAlive 页面和弹窗。业务 403 也会触发身份核对，不自动重试写请求。数据库变化不会主动推送给浏览器；可以到登录页点击核对登录状态，让按钮同步更新。

## 7. 本地练习

按 README 启动 MySQL、后端和前端。后端启动时应用 V4；使用自己的已有账号登录，先查看右上角角色及知识库按钮。

在项目根目录预览角色变更，把 learner 替换为自己的用户名：

~~~sh
python3 scripts/set-user-role.py --username learner --role EDITOR
~~~

确认账号无误后自己执行：

~~~sh
python3 scripts/set-user-role.py --username learner --role EDITOR --apply
~~~

脚本替换该账号的全部角色，不修改密码。随后核对登录状态：应看到新建、编辑按钮，没有删除按钮。也可使用 --role USER EDITOR 分配两个角色，查看权限不会重复。练习结束可用 --role USER --apply 恢复普通用户。

脚本只用于本机课程 Docker 数据库，不是开放的管理员接口。课程不会自动把你的账号提升为管理员。角色权限本身目前由数据库迁移管理，尚未实现可视化管理页。

## 8. 验证与理解检查

本课验证结果：22 项后端测试、11 项前端测试及生产构建通过。开发库迁移前后摘要一致，确认账号、密码、角色与知识库内容保留。

自动验证涵盖普通用户只读、管理员增删改、编辑者不能删除、多角色权限去重、撤销全部角色后拒绝读取、同一个 JWT 在关系配置变化后立即采用新权限，以及伪造角色字段不能提权。前端验证独立权限判断与权限变化后的缓存重置，另执行生产构建。

请尝试回答：

- 同时拥有 USER 和 EDITOR，为什么 read 权限只返回一次？
- /me 返回 200，而知识库返回 403，表示登录失败吗？
- 删除按钮被人手工显示出来，什么地方仍会拦住请求？
- 为什么注册账号和分配默认角色要处于同一个事务？

当前知识库仍是共享数据，权限不等于数据归属；“能编辑”不代表实现了“只能编辑自己的”。下一课进入文档上传基础。
