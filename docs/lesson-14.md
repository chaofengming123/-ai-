# 第 14 课：登录验证与 JWT 登录状态

上一课创建了用户，数据库保存的是密码哈希。本课让用户提交用户名与密码，后端验证后签发一个短期登录凭证，前端保存它并展示当前身份。

打开侧栏“登录”，使用自己上一课创建的账号。成功后可以点击“核对登录状态”，验证后端是否仍认可这次登录；点击“退出”清除浏览器中的登录状态。

## 1. 本课范围

| 操作 | 接口 | 行为 |
| --- | --- | --- |
| 注册 | POST /api/auth/register | 保留上一课功能 |
| 登录 | POST /api/auth/login | 密码正确返回 JWT 与用户信息 |
| 当前身份 | GET /api/auth/me | 必须携带有效 JWT，否则返回 401 |

本课先保护身份查询接口。知识库接口尚未接入访问控制，仍可不登录直接访问；下一课再完成业务接口保护和前端路由守卫。登录成功不代表已经实现角色权限或数据归属隔离。

本课把登录凭证存在内存里，切换页面可以继续使用，刷新整个页面需要重新登录。有效期 15 分钟，不实现自动续期。

## 2. Service 怎样检查密码

文件：backend/src/main/java/com/example/aiknowledge/service/LoginService.java。

~~~java
UserEntity user = mapper.selectOne(new LambdaQueryWrapper<UserEntity>()
        .eq(UserEntity::getUsername, normalized));

boolean matches = passwords.matches(
        password, user == null ? dummyHash : user.getPasswordHash());
if (user == null || !matches) throw invalid();
~~~

**作用：** 查找用户名，并检查输入密码是否匹配数据库中的哈希。

**如何实现：**

1. 用户名按注册时的规则去两端空格、转小写，密码保持原样。
2. LambdaQueryWrapper 描述查询条件，相当于 WHERE username = ?。
3. UserMapper 通过 MyBatis-Plus / MyBatis 查询，结果映射成 UserEntity。
4. PasswordEncoder.matches 使用已有哈希中的参数和盐来验证输入，不是把保存值解密。
5. 失败统一返回 401 和同一条提示，不区分“不存在账号”和“密码错误”。

用户不存在时也对 dummyHash 做一次校验，避免直接跳过昂贵的密码计算。它只是减少明显的时间差，并不等于完整的恒定时间防护。注册仍然会明确提示重名。

这里使用已经注入的 PasswordEncoder；没有再造一个密码算法，也没有调用 encode 后比较两个哈希字符串。

## 3. JWT 是什么，里面放什么

JWT 是一种表示声明的格式。本课采用带签名的 JWT，字符串有三个以点分隔的部分：

~~~text
编码后的头部.编码后的声明.签名
~~~

声明中放用户编号、签发时间、到期时间和签发方。签名让后端能检查内容是否被修改。JWT 不是加密保险箱，编码的声明可以被读取，所以不能放密码、密码哈希或私密文档。

本课由 Spring Security 的 NimbusJwtEncoder 签发，NimbusJwtDecoder 校验，算法明确配置为 HS256。框架实现参考 [JWT Resource Server 文档](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) 和 [NimbusJwtEncoder API](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/oauth2/jwt/NimbusJwtEncoder.html)。

JWT 是 Bearer 凭证：持有有效凭证的人可以使用它。不要把它打印到日志、写入讲义或分享给其他人。

## 4. 登录成功后怎样签发凭证

文件：service/LoginService.java。

~~~java
Instant now = Instant.now();
JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer(TokenConfig.ISSUER)
        .subject(user.getId().toString())
        .issuedAt(now)
        .expiresAt(now.plusSeconds(900))
        .id(UUID.randomUUID().toString())
        .build();
~~~

**作用：** 描述这次登录对应的身份与期限。

- issuer：签发方，本项目固定为 ai-knowledge-local。
- subject：用户编号，用来查回真实用户。
- issuedAt / expiresAt：签发时间与到期时间；900 秒就是 15 分钟。
- id：这次凭证的随机编号，避免同一秒内两次登录产生相同内容。

接着编码并签名：

~~~java
String token = tokens.encode(JwtEncoderParameters.from(
        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
~~~

返回 LoginResponse，包括 accessToken、tokenType、expiresIn 和只含公开字段的 user。Controller 使用 Cache-Control: no-store，要求不缓存登录响应。这里的“返回 user”用于展示，后续后端验证身份依靠有效凭证，不相信客户端自行填写的用户名。

## 5. 签名密钥放在哪里

文件：config/TokenConfig.java 和 scripts/init-db-env.py。

初始化脚本使用安全随机数生成 32 字节的 JWT_SECRET，Base64 编码后追加到被 Git 忽略的 docker/.env。已有数据库密码和已有 JWT_SECRET 均保留，不每次启动生成新密钥。

从旧版本升级，先在项目根目录运行：

~~~bash
python3 scripts/init-db-env.py
scripts/backend.sh spring-boot:run
~~~

后端读取环境变量，解码后检查密钥至少 32 字节。不提供开发默认密钥；测试配置中的固定密钥只用于独立测试环境。

~~~java
NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
~~~

编码器用密钥签名，解码器用同一密钥校验。解码器还配置了签发方和时间验证。框架的默认时间校验允许少量时钟偏差，因此浏览器的到期倒计时不应当被当成唯一的安全判断。

本课没有更换数据库结构，所以不需要新增 Flyway 迁移。密钥与用户密码是不同用途的秘密，不能把数据库密码拿来直接当 JWT 密钥。

## 6. Spring Security 在 Controller 之前做什么

文件：config/AuthSecurityConfig.java。

~~~java
http.securityMatcher("/api/auth/**")
    .csrf(csrf -> csrf.disable())
    .sessionManagement(session ->
        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.POST,
            "/api/auth/login", "/api/auth/register").permitAll()
        .anyRequest().authenticated())
    .oauth2ResourceServer(resource -> resource.jwt(jwt -> {}));
~~~

上面省略了源码中返回 JSON 错误的配置。

**作用：** 在请求进入 Controller 前检查登录凭证。

**如何实现：**

- securityMatcher 限定本条过滤链只处理 /api/auth/**，所以其他业务接口本课仍不受保护。
- 注册与登录允许匿名调用，否则未登录的人无法开始登录。
- 其他匹配请求必须通过认证，包含 /api/auth/me。
- STATELESS 表示不依赖服务器 HTTP Session 来记住登录。
- Resource Server 从 Authorization 请求头读取 Bearer Token，再交给 JwtDecoder 验证签名、时间和签发方。
- 缺少或无效凭证返回 401，使用 JSON 提示，不跳转到框架自带登录页。

本课凭证只通过手动设置的请求头提交，不使用浏览器自动附带的认证 Cookie，因此对这条无状态过滤链关闭 CSRF。以后如果改成 Cookie 认证，需要重新设计 CSRF 防护，不能照搬这个配置。

## 7. /me 怎样知道是哪个用户

前端发送：

~~~http
GET /api/auth/me
Authorization: Bearer <本次登录返回的凭证>
~~~

Controller 参数使用：

~~~java
@AuthenticationPrincipal Jwt jwt
~~~

**作用：** 取得 Spring Security 已经验证过的身份对象。

**如何实现：** 过滤器通过验证后，把身份放入安全上下文；Spring 为 Controller 注入该对象。Service 读取 jwt.getSubject()，转成用户编号，再查数据库：

~~~java
UserEntity user = mapper.selectById(id);
if (user == null) throw invalid();
return new UserResponse(user.getId(), user.getUsername());
~~~

因此，凭证有效但用户已经删除时也返回 401。返回的用户名来自当前数据库记录，不是浏览器自行声称的身份。

缺少凭证的请求会在过滤器处被拦截，根本不会进入这个 Controller 方法。

## 8. Pinia 怎样保存登录状态

文件：frontend/src/stores/auth.js。

登录 action 等待 API 成功后：

~~~js
token.value = result.accessToken
user.value = result.user
expiryTimer = setTimeout(
  () => logout('登录已到期，请重新登录。'),
  result.expiresIn * 1000,
)
~~~

**作用：** 为多个页面共享本次登录状态，并在有效期结束时清理。

**如何实现：** user 和 token 存在内存中；isLoggedIn 根据两者是否存在计算。顶栏和登录页读取同一个 store，因此用户信息能同步显示。setTimeout 是页面体验上的定时清理，安全校验仍由后端执行，后台标签页可能延迟计时器。

密码只在登录表单中短暂保存，请求结束就清空。JWT 也不写入 localStorage，本课刷新即退出。

“核对登录状态”调用 frontend/src/api/auth.js：

~~~js
http.get('/auth/me', {
  headers: { Authorization: 'Bearer ' + token },
})
~~~

当前只对 /me 显式附带凭证，尚未给所有请求添加全局拦截器。请求成功后使用后端用户信息更新 store；401 清空登录状态；临时断网只提示无法核对，不直接当成凭证无效。

## 9. 退出与异步请求为什么要配合

退出会清除计时器、token 和 user，并增加 generation：

~~~js
generation++
clearTimeout(expiryTimer)
token.value = ''
user.value = null
~~~

登录或核对请求在发出时记住当时的 generation，响应到达后先检查是否仍相等。若用户在等待时已经退出，旧响应必须丢弃，防止“刚退出又被旧请求登录回来”。

本课退出只是清除当前浏览器中的凭证，没有服务器撤销列表。已经被复制出去的 JWT 在到期前仍可能使用。清除浏览器状态、使服务器凭证立即失效是两件事；未来需要撤销机制时再扩展。当前也没有刷新令牌、登录限流或角色授权，仍用于本地课程环境。

## 10. 完整执行顺序

~~~text
输入用户名和密码 → POST /api/auth/login
  → Security 允许匿名登录请求
  → Controller 接收 LoginRequest
  → Service 查用户并 matches 密码
  → JwtEncoder 签发 JWT
  → 返回 JSON，Axios 解析
  → Pinia 保存凭证与用户，Vue 更新顶栏

点击核对登录状态 → GET /api/auth/me + Bearer JWT
  → Security / JwtDecoder 检查凭证
  → Controller 得到已认证的 Jwt
  → Service 按 subject 查用户
  → 返回公开用户信息

点击退出 → 清除当前浏览器凭证与用户状态
~~~

## 11. 本课验证与练习

- 后端 17 项集成测试通过，包含之前的注册与知识库回归。
- 新增验证正确登录、无 Cookie Session、有效期、/me、错误密码、无此用户、无凭证、格式错误、篡改、过期、错误签发方、用户删除后的凭证拒绝。
- 前端 6 项测试与生产构建通过，覆盖登录、Authorization 头、网络失败、401、退出和迟到响应。
- 浏览器验证登录入口、空表单提示和不存在账号的错误提示。成功登录和身份查询由独立测试库的真实 HTTP 测试及前端状态测试验证，不代用户使用真实账号登录。

练习：使用上一课的账号登录，点击核对状态，切换到工作台观察顶栏用户名，再退出；重新登录后刷新，观察内存登录状态消失。最后回看 SecurityFilterChain：为什么 /me 必须认证，而 /login 必须允许匿名？

下一课将保护知识库接口，并让前端对未登录访问作出正确引导。
