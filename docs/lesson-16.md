# 第 16 课：普通用户与管理员，开始角色授权

上一课解决“请求者是否已登录”。本课继续判断：已经登录的人，是否允许执行这次操作？

现在普通用户能查看知识库，管理员才能新建、修改和删除。角色决定操作权限，知识库仍是共享数据；本课没有按创建者隔离记录。

## 1. 认证与授权的区别

| 问题 | 名称 | 本项目做法 |
| --- | --- | --- |
| 你是谁？身份是否有效？ | 认证 Authentication | 校验 JWT、检查用户存在 |
| 你是否能执行这个操作？ | 授权 Authorization | 检查数据库中的角色 |

具体规则：

| 操作 | 未登录 | USER 普通用户 | ADMIN 管理员 |
| --- | --- | --- | --- |
| 列表与详情 | 401 | 允许 | 允许 |
| 新建、修改、删除 | 401 | 403 | 允许 |

401 是身份无法确认，需要登录；403 是身份已知但权限不足，不应该让用户不停重复登录。

本课采用一个用户一个角色的入门设计。尚未建立完整的 role、permission、user_role、role_permission 关系表，后续再扩展为可配置 RBAC。

## 2. Flyway V3 增加角色字段

文件：backend/src/main/resources/db/migration/V3__add_user_role.sql。

~~~sql
ALTER TABLE app_user
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER',
    ADD CONSTRAINT chk_app_user_role CHECK (role IN ('USER', 'ADMIN'));
~~~

**作用：** 给用户保存一个明确的角色。

**如何实现：** 新字段默认 USER，因此已有账号迁移后都是普通用户。CHECK 限制数据库只保存 USER 或 ADMIN。V1、V2 不改写，Flyway 只执行新增的 V3。

没有自动把第一个账号或已有账号变成管理员。升级后原本能管理知识库的账号，在被明确设为 ADMIN 之前只能查看。这是本课引入权限规则后的预期变化。

## 3. 注册为什么不能接受客户端指定角色

文件：service/UserService.java。

~~~java
entity.setUsername(normalizedUsername);
entity.setRole("USER");
entity.setPasswordHash(passwordEncoder.encode(password));
mapper.insert(entity);
~~~

**作用：** 所有公开注册都创建普通用户。

**如何实现：** RegisterRequest 只声明用户名和密码；Service 自己设置 USER。即使请求正文额外传入 role: ADMIN，也不会用它赋予权限。

不能让客户端说“我是管理员”，服务器就保存为管理员。角色设置属于独立的管理操作。

UserEntity 新增 role 字段，MyBatis-Plus 自动映射到同名列。UserResponse 也增加 role，供前端显示；密码和哈希仍不返回。

## 4. JWT 证明身份，数据库决定当前角色

文件：config/AuthSecurityConfig.java。

~~~java
@Bean
JwtAuthenticationConverter databaseRoles(UserMapper users) {
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
        var user = users.selectById(Long.parseLong(jwt.getSubject()));
        if (user == null) {
            throw new InvalidBearerTokenException("Account unavailable");
        }
        return List.of(
            new SimpleGrantedAuthority("ROLE_" + user.getRole()));
    });
    return converter;
}
~~~

**作用：** 把已通过校验的 JWT 身份，转换成带有当前角色的认证对象。

**如何实现：**

1. 第十五课的 JwtDecoder 已检查签名、期限、签发方与用户存在性。
2. 转换器再按 subject 中的编号查询用户，读取数据库当前 role。
3. 把 ADMIN 转换成 ROLE_ADMIN，把 USER 转换成 ROLE_USER。
4. SimpleGrantedAuthority 表示 Spring Security 可以用于判断的权限标记。
5. 转换器交给 Resource Server，后续授权就能读取这些标记。

~~~java
.oauth2ResourceServer(resource -> resource
    .jwt(jwt -> jwt.jwtAuthenticationConverter(databaseRoles)))
~~~

本课不采用 JWT 自带的 role 或 scope 声明作为角色来源。测试甚至使用带 ADMIN 声明的合法签名测试凭证，数据库用户仍为 USER 时，写请求照样返回 403。

这样管理员降权后，原 JWT 的下一次请求就按新角色判断，不必等 15 分钟到期。已经通过授权并开始执行的旧请求不会自动回滚。

代价是增加数据库查询。目前存在性校验和角色转换各查询一次，/me 本身还会读取用户；这是入门实现，后续可整合查询，但不能为了缓存而忽略权限变更。

## 5. hasRole 怎样保护写操作

同一个配置文件中的授权顺序：

~~~java
.requestMatchers(HttpMethod.POST,
    "/api/auth/login", "/api/auth/register").permitAll()
.requestMatchers(HttpMethod.GET,
    "/api/knowledge-bases", "/api/knowledge-bases/**").authenticated()
.requestMatchers(HttpMethod.HEAD,
    "/api/knowledge-bases", "/api/knowledge-bases/**").authenticated()
.requestMatchers(
    "/api/knowledge-bases", "/api/knowledge-bases/**").hasRole("ADMIN")
.anyRequest().authenticated()
~~~

**作用：** 登录用户可读，管理操作只允许管理员。

**如何实现：** 规则按声明顺序匹配。GET、HEAD 先匹配读取规则，要求已认证；其余知识库请求再匹配管理员规则，包括现有 POST、PUT、DELETE。

hasRole("ADMIN") 默认查找 ROLE_ADMIN，所以这里不能写 hasRole("ROLE_ADMIN")。规则顺序和 ROLE_ 前缀行为见 [Spring Security 请求授权文档](https://docs.spring.io/spring-security/reference/7.0/servlet/authorization/authorize-http-requests.html)。

过滤链仍只覆盖已有 auth 和 knowledge-bases 范围；未来增加新业务接口需要同步配置保护。健康检查仍公开。

权限检查发生在知识库 Controller 之前。普通用户即使绕过前端、手工发送 DELETE，也无法进入删除业务方法。

## 6. 403 怎样返回给前端

本课新增 AccessDeniedHandler：

~~~java
response.setStatus(HttpServletResponse.SC_FORBIDDEN);
response.setContentType("application/json;charset=UTF-8");
response.getWriter().write(
    "{\"message\":\"当前账号没有管理知识库的权限，请联系管理员。\"}");
~~~

**作用：** 将拒绝授权转换成统一的 JSON 错误，而不是 HTML 错误页面。

AuthenticationEntryPoint 继续负责 401；AccessDeniedHandler 负责身份有效但权限不足的 403。两种情况分别处理。

Service 原有的输入校验和重名判断继续保留，角色允许只是第一关。例如管理员创建重名知识库，仍然会收到 409。

## 7. 前端怎样显示角色和可用操作

文件：frontend/src/utils/permissions.js。

~~~js
export function canManageKnowledgeBases(user) {
  return user?.role === 'ADMIN'
}
~~~

**作用：** 集中判断前端是否展示管理操作。

缺少角色或角色未知时默认不允许管理。认证 store 把这个判断与 isLoggedIn 组合成 computed，顶栏显示“用户名 · 管理员”或“用户名 · 普通用户”。

知识库页面的新建按钮：

~~~vue
<button v-if="auth.canManageKnowledgeBases"
  @click="openCreateDialog">
  + 新建知识库
</button>
~~~

卡片通过 Props 接收 canManage，只在为 true 时显示编辑、删除区域。普通用户仍能搜索、查看详情，页面明确说明当前只读权限。

打开弹窗与提交的函数也检查权限。如果身份信息更新后不再是管理员，watch 会关闭管理弹窗，避免继续显示过期的操作入口。

这些判断改善页面体验；真正的授权依然由后端完成。修改浏览器中的角色字段，最多影响按钮显示，无法改变数据库角色或后端授权结果。

## 8. 管理员被降权后，页面如何更新

角色是在登录和 /me 响应中传给前端的，数据库刚变更时，前端可能还暂时显示旧角色。

本课的响应拦截器在收到当前登录周期的业务 403 后：

~~~js
void auth.verifySession?.()
return Promise.reject(error)
~~~

**作用：** 不退出账号、不重试原写请求，只核对最新身份。

**如何实现：** 原操作继续报告错误；额外的 /me 更新 store.user.role，computed 随之重新计算，管理按钮隐藏。身份查询失败则保留当前信息并提示稍后重试，后端仍按真实角色拒绝越权请求。

/me 自身不会触发这条 403 刷新逻辑，避免递归。旧登录周期的响应也不会影响新账号。

也可以进入“登录”页面主动点击“核对登录状态”，或者退出后重新登录，让前端显示最新角色。

## 9. 如何给自己的学习账号设置管理员

本课提供本地开发脚本 scripts/set-user-role.py。它只操作本项目 Docker MySQL 中的 ai_knowledge 数据库，不是公开的角色修改接口。

先自己注册账号。下面用 learner_13 举例，请换成自己的实际用户名。

在项目根目录预览：

~~~bash
python3 scripts/set-user-role.py --username learner_13 --role ADMIN
~~~

它会显示当前角色与目标角色，不写入数据库。

确认要让这个账号管理知识库后，自己执行：

~~~bash
python3 scripts/set-user-role.py --username learner_13 --role ADMIN --apply
~~~

恢复普通用户：

~~~bash
python3 scripts/set-user-role.py --username learner_13 --role USER --apply
~~~

**关键实现：**

- argparse 限制角色只能为 USER 或 ADMIN。
- 用户名经过同注册规则一致的格式检查，拒绝特殊 SQL 字符。
- 默认仅执行 SELECT，只有 --apply 才执行 UPDATE。
- 应用阶段用事务与行锁操作指定用户名，再核对最终角色。
- 数据库管理员凭据从已有容器环境读取，不出现在命令参数或输出中。
- 不存在的账号不会被创建，脚本不修改密码。

有能力运行这个脚本的人已经具有本地数据库管理能力。它不适用于给普通网页用户自行提权。生产环境的管理入口、审计和防止误降最后一个管理员等规则尚未实现。

本次实现没有给任何开发库账号自动提权。管理员行为在独立测试库中的临时账号上验证。

## 10. 完整请求流程

~~~text
前端根据 role 显示可用按钮
  → 用户提交操作
  → Axios 附带 JWT
  → JwtDecoder 校验凭证和用户存在性
  → JwtAuthenticationConverter 查询当前数据库角色
  → Spring Security 根据 HTTP 方法和路径匹配规则
      → 普通用户读请求：允许
      → 普通用户写请求：403，Controller 不执行
      → 管理员写请求：进入 Controller → Service → Mapper → MySQL
  → 前端展示成功结果或错误
  → 403 时核对最新身份，更新角色与按钮
~~~

与上一课相比，多了一步“认证成功后的授权判断”。

## 11. 启动、验证和练习

启动方式仍是项目根目录运行 scripts/backend.sh spring-boot:run。它会加载 .env，并由 Flyway 自动执行 V3。前端使用 npm run dev --prefix frontend。

本课验证：

- 后端 20 项真实 MySQL 集成测试通过，保留登录、注册、匿名拒绝和原 CRUD 回归。
- 普通用户列表与详情可读；新增、修改、删除返回 403，数据库记录不变。
- 管理员新增、修改、删除成功；同一凭证提权后可写，降权后立即拒绝后续写请求。
- 注册正文额外指定 ADMIN 不会提权；JWT role / scope 声明不能覆盖数据库角色。
- 前端 11 项测试与构建通过，覆盖默认拒绝、角色更新、403 核对与保持登录。
- 本地角色脚本参数帮助已检查；没有对现有账号运行提权操作。角色按钮规则通过前端状态测试验证，未代用户使用真实账号做浏览器登录演练。

练习：先以普通用户登录，观察只读提示；预览并明确应用自己账号的 ADMIN 角色，核对身份后查看管理按钮；再降回 USER，确认管理入口消失。只用自己新建的练习知识库测试删除。

下一课把单个角色字段扩展成角色与权限关系表，理解可配置的 RBAC。
