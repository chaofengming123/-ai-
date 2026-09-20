# 第 8 课：Spring Boot，第一个真实 HTTP 接口

## 本节目标

启动 Java 后端，在浏览器访问 `http://127.0.0.1:8080/api/health`，得到：

```json
{"status":"UP","service":"ai-knowledge-backend"}
```

本课开始真实 HTTP 服务。前端仍使用第七课的模拟数据，还没有调用这个后端；没有接数据库、登录或 AI。

## 1. 先运行，再理解代码

本项目使用 Java 17 和 Spring Boot 4.1.1，工程骨架由 Spring Initializr 生成。官方系统要求支持 Java 17，版本已固定在 pom.xml。

在项目根目录打开终端：

```bash
cd backend
./mvnw spring-boot:run
```

保持终端运行，出现服务启动成功的日志后，打开 `http://127.0.0.1:8080/api/health`。按 Ctrl+C 停止服务。

`mvnw` 是 Maven Wrapper，首次运行会下载指定 Maven 版本和依赖，需要网络。电脑没有全局 `mvn` 也能运行，但必须有 JDK。Windows 使用 `mvnw.cmd spring-boot:run`。

普通运行默认把下载内容缓存在用户的 `.m2`。本次自动验证把缓存放在项目被忽略的 work 目录，避免修改用户级配置；这不影响上述标准用法。

## 2. 这次请求经过哪里

浏览器请求 → 内嵌 Tomcat 接收 HTTP → Spring MVC 匹配地址和方法 → HealthController 执行 → 返回 Java 对象 → JSON 序列化 → 浏览器显示响应。

前端开发服务通常在 5173 端口，本课后端在 8080 端口。端口用于区分同一台机器上的服务。`127.0.0.1` 指本机。

## 3. pom.xml：声明如何构建项目

关键配置：

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.1.1</version>
  <relativePath/>
</parent>
```

**作用：** 使用 Spring Boot 管理的一组依赖版本与构建默认值。

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
```

**如何实现：** Maven 读取依赖坐标并下载需要的库。Web MVC starter 引入构建 HTTP 接口所需的 Spring MVC、JSON 处理和内嵌服务器等依赖，所以不必手动逐个安装。

`<java.version>17</java.version>` 声明本项目 Java 编译目标。测试 starter 仅在测试阶段使用，`spring-boot-maven-plugin` 负责启动与可执行 JAR 打包。

Maven 类似前端 npm 的部分职责：管理依赖、运行构建任务，但它服务于 Java 工程，两者不是同一个工具。

## 4. 启动类：创建并运行后端应用

文件：`backend/src/main/java/com/example/aiknowledge/AiKnowledgeApplication.java`。

```java
@SpringBootApplication
public class AiKnowledgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiKnowledgeApplication.class, args);
    }
}
```

**作用：** 提供 Java 程序入口并启动 Spring Boot。

**如何实现：** JVM 从 main 方法开始执行；`SpringApplication.run` 创建 Spring 应用上下文，发现组件并应用自动配置，启动内嵌 Web 服务器。

`@SpringBootApplication` 是注解，组合了配置、自动配置与组件扫描等能力。默认从启动类所在包及其子包扫描组件。因此 Controller 放在 `com.example.aiknowledge.controller`，处于扫描范围内。

`package` 声明类的命名空间；`import` 引入代码使用的类型，不是在运行时下载依赖。依赖下载由 Maven 完成。

## 5. Controller：把请求映射到方法

文件：`backend/src/main/java/com/example/aiknowledge/controller/HealthController.java`。

```java
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "ai-knowledge-backend");
    }

    public record HealthResponse(String status, String service) {
    }
}
```

**作用：** 声明一个处理 HTTP 请求的类，提供健康检查路径。

**如何实现：**

- `@RestController` 将类标记为由 Spring 管理的控制器，并把方法返回值写入 HTTP 响应体。
- 类上的 `@RequestMapping("/api")` 定义公共路径前缀。
- 方法上的 `@GetMapping("/health")` 匹配 GET 请求，组合后的路径为 `/api/health`。
- 匹配后执行 `health()`，返回响应对象。

`new HealthResponse(...)` 创建 Java 对象。Spring MVC 的消息转换器将其序列化为 JSON。我们没有手动拼 JSON 字符串，浏览器也不需要懂 Java。

`record` 是 Java 的简洁数据载体语法，会生成字段访问方法和构造方法等。这里两个 String 字段分别对应 JSON 的 status 和 service。后续复杂接口会逐步拆分 DTO、VO 等类型，本课响应很小，先作为控制器内的嵌套 record。

Controller 目前没有业务计算，因此无需人为增加 Service、Mapper 和数据库层。后续知识库业务复杂后再学习分层。

## 6. HTTP 方法、状态码和内容类型

在浏览器地址栏打开链接，会发出 GET 请求。成功返回：

```http
HTTP/1.1 200
Content-Type: application/json
```

- GET：读取信息。
- 200：请求成功。
- Content-Type：告诉客户端响应内容采用什么格式。
- JSON 响应体：实际返回的数据。

本项目测试还验证：向此路径发送 POST 返回 405，表示不支持这个方法；请求 `/api/not-found` 返回 404，表示没有对应资源。

JSON 中的 `UP` 只是这个接口固定返回的状态文字，能证明服务可接收并处理请求；不代表数据库、Redis、RAG 等依赖都健康。本课还没有这些依赖，也没有使用 Actuator 的完整健康检查机制。

## 7. application.properties：服务配置

```properties
spring.application.name=ai-knowledge-backend
server.address=127.0.0.1
server.port=8080
```

**作用：** 设置应用名称、监听地址和端口。

监听 127.0.0.1 表示本课只在当前电脑访问。应用名称是配置；响应体中的 service 是我们在 Controller 中明确填写的字符串，两者本课相同，但不会自动同步。

若 8080 被其他程序占用，不要停止未知程序，可以指定另一端口：

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

然后访问对应的 8081 地址。

## 8. 验证与打包

在 backend 目录运行：

```bash
./mvnw test
./mvnw package
java -jar target/ai-knowledge-backend-0.0.1-SNAPSHOT.jar
```

`test` 运行测试；`package` 包含测试并生成 JAR；最后一行运行已打包程序。不要与另一个使用相同端口的实例同时启动。

测试使用 `@SpringBootTest(webEnvironment = RANDOM_PORT)` 启动真实 Spring 应用，并由系统选择临时端口。测试通过 HTTP 请求验证 200 与 JSON 字段、POST 的 405、未知路径的 404，不依赖默认 8080 是否空闲。

应用内浏览器若拦截这个 API 地址，可在自己的浏览器打开，或在终端运行 `curl -i http://127.0.0.1:8080/api/health` 查看状态码、响应头和 JSON。本课已通过实际 HTTP 请求验证；浏览器拦截不等于后端未启动。

## 小练习

1. 打开 `/api/health`，看两个 JSON 字段。
2. 打开 `/api/not-found`，观察找不到接口时的结果。
3. 对照两个注解，解释为什么完整路径是 `/api/health`。

此时前端与后端是两个独立服务。接下来才把前端模拟读取替换成真实 API 请求。

## 下一步

用内存列表提供知识库 REST API，逐步引入 Controller 与 Service，再让 Vue 请求后端。MySQL 持久化在之后加入。

## 官方参考

- [Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Initializr](https://start.spring.io/)
