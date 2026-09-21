# 第 13 课：创建用户与密码哈希

知识库已经可以增删改查。本课先解决登录前的准备工作：怎样建立一个用户，同时避免把原始密码保存到数据库？

本课完成用户表、注册接口和“创建账号”页面。下一课再实现登录验证与登录状态。目前知识库接口还没有访问权限限制。

## 1. 注册、登录与权限

| 概念 | 解决的问题 | 示例 |
| --- | --- | --- |
| 注册 | 建立用户身份 | 创建用户名 learner_13 |
| 登录（认证） | 证明请求者拥有这个身份 | 检查输入密码 |
| 权限（授权） | 判断这个身份允许做什么 | 能否删除某个知识库 |

打开本地页面的“创建账号”，输入用户名、密码和确认密码。成功后显示编号与用户名，但不会自动登录，也不返回 JWT。学习账号的密码自己保管，不需要发送到聊天中。

## 2. 用 Flyway V2 新增用户表

文件：backend/src/main/resources/db/migration/V2__create_app_user.sql。

~~~sql
CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
~~~

**作用：** 保存编号、用户名、密码哈希和创建时间。

**如何实现：**

- AUTO_INCREMENT 让 MySQL 分配编号。
- UNIQUE KEY 保证用户名唯一，也能处理并发同名注册。
- password_hash 保存哈希，表中没有明文密码列。
- 本课用户名限制为英文字母、数字与下划线，所以该列使用 ASCII。
- Flyway 检查迁移历史，启动时执行新的 V2。已经应用的 V1 不做修改。

V2 只新增用户表，不改变已有知识库记录。

## 3. Entity 与 Mapper 怎样连接数据库

文件：后端主代码包中的 entity/UserEntity.java 和 mapper/UserMapper.java。

~~~java
@TableName("app_user")
public class UserEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    // getter / setter 见源文件
}
~~~

@TableName 指定对应的表；项目已开启驼峰映射，所以 passwordHash 对应 password_hash。

~~~java
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {}
~~~

**作用：** 让 Service 使用 mapper.insert(entity) 新增用户。

**如何实现：** Mapper 接口由我们定义，MyBatis 创建代理对象；MyBatis-Plus 的 BaseMapper 提供通用新增等能力。SQL 通过 JDBC 驱动发送给 MySQL，真正保存数据的是 MySQL。

MyBatis-Plus 是 MyBatis 的增强工具，不是另一个数据库。Mapper 也不是必须采用的分层名称，其他实现可以用 Repository、JdbcTemplate 等。

## 4. 密码哈希是什么

本课用 BCrypt 对密码做单向处理，没有“使用密钥解密出原密码”的步骤。每次编码会生成随机盐，所以相同密码两次编码的结果通常也不同。算法增加计算成本，以提高大量猜测密码的代价。[Spring Security 密码存储说明](https://docs.spring.io/spring-security/reference/7.0/features/authentication/password-storage.html)、[Crypto 模块说明](https://docs.spring.io/spring-security/reference/7.0/features/integrations/cryptography.html)。

文件：config/PasswordConfig.java。

~~~java
@Configuration
public class PasswordConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
~~~

**作用：** 为 Service 提供统一的密码处理对象。

**如何实现：** @Configuration 标识配置类，@Bean 让 Spring 管理方法返回值。PasswordEncoder 是接口，BCryptPasswordEncoder 是本课选用的实现。

参数 12 是计算成本参数，不是密码长度。值越高，编码与校验通常越慢，生产配置需要结合硬件测量。本课加入的是 spring-security-crypto 模块，版本由 Spring Boot 管理；引入密码处理工具不会自动开启登录拦截。

两个方法需要分清：

~~~java
String hash = passwordEncoder.encode(rawPassword);
boolean correct = passwordEncoder.matches(inputPassword, hash);
~~~

encode 在注册时生成保存值，matches 在登录时检查输入是否匹配。不要重新 encode 后直接比较字符串，因为随机盐会使结果不同。本课的测试已调用 matches 验证正确和错误密码；登录 HTTP 接口留到下一课。

## 5. Service 怎样创建用户

文件：service/UserService.java。

~~~java
String normalizedUsername = username == null
    ? ""
    : username.strip().toLowerCase(Locale.ROOT);

if (!normalizedUsername.matches("[a-z0-9_]{3,32}")) {
    throw new RegistrationException(INVALID_INPUT, "...");
}
~~~

**作用：** 统一用户名规则，避免大小写造成重复身份。

**如何实现：** 去除两端空白、转为小写，再检查 3–32 位字母、数字或下划线。Locale.ROOT 避免服务器语言环境影响转换。例如输入 Learner_13，保存为 learner_13。

密码规则不同：

~~~java
password.codePointCount(0, password.length()) < 12
password.getBytes(StandardCharsets.UTF_8).length > 72
~~~

本课密码至少 12 个 Unicode 码点，不能全为空白，UTF-8 编码最多 72 字节。常见英文字母一个字节、常见汉字三个字节，所以 24 个这样的汉字刚好到达上限。码点数量与人眼看到的字符数量不总相同，例如组合 emoji。

上限与本课采用的 BCrypt 有关。超长密码被拒绝而不是截断。密码不做 trim 或小写转换，首尾空格也是密码的一部分。

核心保存代码：

~~~java
UserEntity entity = new UserEntity();
entity.setUsername(normalizedUsername);
entity.setPasswordHash(passwordEncoder.encode(password));
mapper.insert(entity);
return new UserResponse(entity.getId(), entity.getUsername());
~~~

执行顺序：

1. 创建实体，写入规范化后的用户名。
2. 用 encode 处理原始密码，只把哈希放入实体。
3. Mapper 执行 INSERT，MySQL 分配主键并检查唯一索引。
4. 返回仅有编号和用户名的 UserResponse。

实际代码捕获 DuplicateKeyException，转为 409 重名错误。数据库唯一索引是并发情况下的最终保证。这里仅有一条 INSERT，数据库会原子地完成它，没有多条需要一起提交的写操作。

## 6. Controller 收到什么，返回什么

文件：controller/AuthController.java。

~~~java
@PostMapping("/register")
public ResponseEntity<UserResponse> register(
        @RequestBody RegisterRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.register(request.username(), request.password()));
}
~~~

类上的 @RequestMapping("/api/auth") 与方法路径组合成 POST /api/auth/register。

@RequestBody 配合消息转换器把 JSON 请求转换成 RegisterRequest。Controller 调用 Service 后返回 Java 响应对象，Spring MVC 的 JSON 消息转换器再使用 Jackson 将它转换成 JSON。

成功状态是 201，正文只有：

~~~json
{"id": 1, "username": "learner_13"}
~~~

编号以实际创建结果为准。

| 类型 | 字段 | 用途 |
| --- | --- | --- |
| RegisterRequest | username、password | 接收输入，短暂使用原始密码 |
| UserEntity | id、username、passwordHash | 映射数据库 |
| UserResponse | id、username | 返回给前端 |

正常接口明确返回 UserResponse，不返回实体。实体哈希 getter 额外使用 @JsonIgnore，避免意外序列化泄露哈希；请求 DTO 的 toString 隐藏凭据。不要加入输出密码、请求正文或 SQL 参数的日志。

输入或 JSON 格式错误返回 400，用户名重复返回 409，错误正文沿用 message 字段。

## 7. Vue 怎样提交并更新页面

文件：frontend/src/api/auth.js。

~~~js
const response = await http.post('/auth/register', { username, password })
return response.data
~~~

**作用：** 发送 POST，凭据放在正文中，不放在 URL 里。

**如何实现：** Axios 已配置 /api 前缀，把对象编码为 JSON；收到响应后 response.data 是解析后的 JavaScript 对象。

文件：frontend/src/views/RegisterView.vue。

~~~js
error.value = validateRegistration(
  username.value, password.value, confirmation.value,
)
if (error.value) return
isSubmitting.value = true
~~~

前端先校验格式与两次密码是否一致，然后禁用提交按钮；函数也检查等待状态，防止重复提交。确认密码只在前端使用，不发送给后端。后端仍独立校验输入，因为请求可以绕过页面。

请求结束的 finally：

~~~js
password.value = ''
confirmation.value = ''
isSubmitting.value = false
~~~

密码只保存在本页表单中，不写入 Pinia、localStorage 或 sessionStorage。本页不在 KeepAlive 缓存名单里，离开后卸载。成功和请求失败都会清空密码；前端校验失败时保留输入供修正。

400 / 409 显示后端业务消息；网络失败提示“未能确认账号是否创建成功”，不自动重试。超时不代表数据库一定没写入。本课还没有账号查询页面，必要时通过开发环境核对，下一课再用登录验证账号。

密码框遮挡只影响显示，不等于加密传输。当前是本地回环地址的课程环境；部署时需要 HTTPS、注册限流和完整访问控制，这些尚未实现。

## 8. 完整执行链路

~~~text
填写表单
  → Vue 校验并进入等待状态
  → Axios 发送 JSON
  → Spring MVC / Jackson 转成 RegisterRequest
  → Controller 调用 Service
  → Service 校验并生成密码哈希
  → Mapper / MyBatis-Plus / MyBatis 准备 INSERT、绑定参数
  → JDBC 驱动发送 SQL
  → MySQL 保存用户并返回自增编号
  → Service 构造 UserResponse
  → Spring MVC / Jackson 转为 JSON 响应
  → Axios 解析为 JavaScript 对象
  → Vue 显示成功信息并清空密码
~~~

JSON 用于前后端交换数据；SQL 用于后端操作数据库。Java 对象和 JavaScript 对象通过 JSON 交换内容，不是同一个对象。

## 9. 验证和练习

- 后端 13 项集成测试通过，包含已有接口回归、注册、非法输入、大小写重名、并发注册和 UTF-8 边界。
- 验证数据库保存值不是原始密码，相同密码生成不同哈希，正确密码可匹配；错误密码及去除首尾空格后的密码不能冒充原密码。
- 响应与实体 JSON 不泄露哈希，请求 DTO 的字符串输出不泄露密码。
- 前端 4 项测试和生产构建通过，覆盖输入规则、确认密码、请求正文、失败不自动重试。
- 浏览器验证了页面入口、表单与空输入提示。有效凭据注册在独立测试库通过真实 HTTP 验证，没有代用户创建开发库账号。
- 新后端启动已应用 V2，已有知识库表不做修改。

自己在创建账号页建立一个学习账号，保管好密码，尝试短密码与确认不一致提示。数据库连接密码和应用账号密码是两类不同凭据。

下一课用该账号完成登录验证，再逐步建立登录状态和接口访问保护。
