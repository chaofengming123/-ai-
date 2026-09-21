# 第 15 课：保护知识库接口与前端路由

上一课实现了登录和身份查询，但知识库仍能匿名访问。本课把登录凭证真正用于业务请求：

- 未登录打开知识库或工作台，先跳到登录页。
- 登录后回到原本想访问的页面。
- 查询、新增、修改和删除知识库都必须经过后端认证。
- 退出、到期或收到当前登录的 401 时，清理页面与业务缓存。

本课实现的是“需要有效登录身份”。所有登录用户仍然共享同一份知识库，尚未按角色或创建者区分权限。接下来的课程再实现授权规则。

## 1. 为什么前端和后端都要检查

前端路由守卫负责体验：未登录时不显示业务页面，引导用户登录。

后端认证负责真正的访问控制：即使绕过浏览器，直接用其他工具请求 API，也不能匿名读取或修改数据。

~~~text
前端：能否进入这个页面？
后端：这次 HTTP 请求能否执行？
~~~

只隐藏删除按钮或只做页面跳转，无法保护数据库。后端必须在 Controller 执行前拒绝未认证请求。

## 2. 后端扩大过滤链范围

文件：backend/src/main/java/com/example/aiknowledge/config/AuthSecurityConfig.java。

~~~java
http.securityMatcher(
        "/api/auth/**",
        "/api/knowledge-bases",
        "/api/knowledge-bases/**")
    .authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.POST,
            "/api/auth/login", "/api/auth/register").permitAll()
        .anyRequest().authenticated());
~~~

这里省略了之前已有的无状态会话、JWT 和错误响应配置。

**作用：** 把整个知识库 API 纳入认证范围。

**如何实现：**

- securityMatcher 决定哪些请求进入这条安全过滤链。
- 列表地址与带编号地址都被覆盖，因此 GET、POST、PUT、DELETE 都要认证。
- 登录和注册仍允许匿名，否则没有凭证的人无法开始登录。
- anyRequest().authenticated() 要求进入本链的其余请求都有合法身份。
- 没有凭证、凭证无效时返回 401 和 JSON 消息，不进入知识库 Controller。

这里只保护明确列出的接口范围，健康检查仍公开，未知地址沿用 404 行为。后续新增业务 API 时也要审查安全规则，不能认为 anyRequest 覆盖了所有不匹配本链的地址。

知识库 Service 和 Mapper 的增删改查逻辑不需要重写，因为认证发生在它们之前。

## 3. 已删除用户的 JWT 还能用吗

只检查签名与到期时间，无法知道数据库中的账号是否已经删除。

本课在 TokenConfig.java 增加 subject 校验器：

~~~java
JwtClaimValidator<String> existingUser =
    new JwtClaimValidator<>("sub", subject -> {
        try {
            return subject != null
                && users.selectById(Long.parseLong(subject)) != null;
        } catch (NumberFormatException error) {
            return false;
        }
    });
~~~

**作用：** 确认凭证对应的用户当前仍存在。

**如何实现：** 从 subject 读取用户编号，转成数字并查询 UserMapper。缺少编号、编号格式错误、用户不存在都拒绝认证。它与原来的签发方、时间校验组合使用，签名验证仍由 JwtDecoder 完成。

代价是认证时增加一次用户查询，这是本课采用的明确取舍。此处没有角色、禁用状态或服务端登出名单；退出浏览器也仍然不会立即撤销已经复制出去的 JWT。

## 4. Axios 请求拦截器统一附带凭证

文件：frontend/src/api/authInterceptors.js。

~~~js
http.interceptors.request.use(config => {
  if (protectedRequest(config)) {
    config.authVersion = auth.sessionVersion
    if (auth.accessToken) {
      config.headers.set('Authorization', 'Bearer ' + auth.accessToken)
    } else {
      config.headers.delete('Authorization')
    }
  }
  return config
})
~~~

**作用：** 让每个知识库请求都自动携带当前登录凭证，避免在每个 API 函数里重复写代码。

**如何实现：**

1. Axios 在真正发出请求前调用请求拦截器。
2. protectedRequest 检查它是否是本项目 /api 下的知识库或 /auth/me 请求。
3. 从认证 store 读取当前凭证，写入 Authorization。
4. 返回 config，Axios 再继续发送请求。

拦截器只给指定的本地相对地址附加凭证，不给登录、注册或任意外部绝对 URL 附加。请求中没有凭证时主动移除旧 Authorization，避免误用残留值。

main.js 在创建 Pinia 后安装拦截器，再安装 Router：

~~~js
const pinia = createPinia()
const app = createApp(App).use(pinia)
installAuthInterceptors(http, useAuthStore(pinia))
app.use(router).mount('#app')
~~~

这样首次路由加载之前，请求处理已经准备好。把 store 作为参数传入，也避免 http 模块和 store 彼此导入形成循环依赖。

## 5. 401 为什么不能随便触发退出

响应拦截器检查：

~~~js
if (config && protectedRequest(config)
    && error.response?.status === 401
    && config.authVersion === auth.sessionVersion
    && auth.isLoggedIn) {
  auth.logout('登录已失效，请重新登录。')
}
return Promise.reject(error)
~~~

**作用：** 后端拒绝当前凭证时，统一清除登录状态。

**如何实现：**

- 只处理受保护请求；登录接口密码输错的 401 不会误清理其他状态。
- 请求发出时记录 sessionVersion，收到响应时再对比。
- 只有属于当前登录周期的 401 才能触发退出。
- 原错误继续传给调用方，没有偷偷重发请求。

为什么要比较登录周期？假设旧账号发出请求，随后退出并登录另一个账号，旧请求此时才返回 401。如果不检查版本，就会把新账号也退出。

网络断开、500 和 403 不直接触发这个退出逻辑。401 表示无法认可身份；403 通常表示身份已知但无权执行。角色权限尚未接入，本课测试先确保不会混淆这两种结果。

## 6. Vue Router 怎样阻止未登录进入

文件：frontend/src/router/index.js 与 authGuard.js。

业务路由增加标记：

~~~js
{
  path: '/knowledge-bases',
  component: KnowledgeBaseView,
  meta: { title: '知识库', requiresAuth: true },
}
~~~

工作台、知识库、文档和 AI 页面都标记为需要登录。文档和 AI 目前仍是占位页面，不因此增加上传或问答功能。

注册全局路由守卫：

~~~js
router.beforeEach(to => authGuard(to, useAuthStore().isLoggedIn))
~~~

守卫的关键判断：

~~~js
if (to.meta.requiresAuth && !isLoggedIn) {
  return {
    path: '/login',
    query: { redirect: safeDestination(to.path) },
    replace: true,
  }
}
return true
~~~

**作用：** 在目标页面组件显示前，决定继续导航还是改去登录页。

**如何实现：** to 是准备进入的路由；读取它的 meta。需要登录但当前没有身份时，返回新的导航目标。例如知识库访问会变为：

~~~text
/login?redirect=/knowledge-bases
~~~

replace 避免把被拒绝的跳转反复堆入历史。登录、注册和 404 页面不要求登录，所以不会形成登录页跳回自己的死循环。

## 7. 登录后为什么能回到原页面

文件：frontend/src/views/LoginView.vue。

~~~js
if (result.error) error.value = result.error
else if (result.success) {
  await router.replace(safeDestination(route.query.redirect))
}
~~~

**作用：** 登录成功后返回之前的目标；直接打开登录页则默认去知识库。

**如何实现：** 从查询参数中读取 redirect，但只允许明确列出的站内业务路径。外部网址、数组参数或未知地址都回退到 /knowledge-bases。不能把地址栏里任意传入的 URL 直接当成跳转目的地。

本课只记住页面路径，不保留搜索参数与锚点。

## 8. 退出时要清理哪些状态

认证 store 在退出和新登录成功时更新 sessionVersion，并调用知识库 store 的 reset：

~~~js
function reset() {
  generation++
  knowledgeBases.value = []
  hasLoaded.value = false
  isLoading.value = false
  isSaving.value = false
  loadError.value = ''
}
~~~

**作用：** 清除列表、加载标记和错误，让下一次登录重新读取数据。

但只清空数组还不够：上一个请求可能还在路上。

每个加载或写入动作在发送前保存 generation，等待之后再判断：

~~~js
const requestGeneration = generation
const records = await fetchKnowledgeBases()
if (requestGeneration !== generation) {
  return { error: '登录状态已变化，请重新操作。' }
}
knowledgeBases.value = records
~~~

finally 中也只有版本相等才能关闭等待状态，否则旧请求可能把新请求的“正在加载”错误清除。

新增、编辑和删除也使用同样检查。它们只阻止旧结果写回浏览器，不会撤销后端已经完成的操作；退出不是数据库事务回滚。

## 9. KeepAlive 缓存也要按登录周期清理

文件：frontend/src/App.vue。

~~~vue
<KeepAlive :key="auth.sessionVersion" include="KnowledgeBaseView">
  <component
    v-if="!route.meta.requiresAuth || auth.isLoggedIn"
    :is="Component"
  />
</KeepAlive>
~~~

**作用：** 登录周期变化时丢弃旧页面的搜索词、表单和弹窗状态。

**如何实现：** key 改变会让 Vue 创建新的 KeepAlive 实例，旧缓存被卸载。v-if 在失去身份时停止显示受保护组件，避免跳转完成前继续展示旧页面。

App 还监听 isLoggedIn：如果从已登录变为未登录，而当前在业务页面，就跳到登录页。路由守卫只在导航时检查，这个监听用于处理“人还停在页面上，凭证却已过期或被拒绝”的情况。

## 10. 一次受保护请求的完整流程

~~~text
进入知识库地址
  → 路由守卫检查内存登录状态
  → 无身份：跳登录页，记录返回路径
  → 登录成功：Pinia 保存凭证，返回知识库
  → 页面调用列表 action
  → Axios 拦截器附带 Bearer JWT
  → Spring Security 检查签名、时间、签发方和用户存在性
  → Controller → Service → Mapper → MySQL
  → 返回数据，Pinia 校验请求周期后更新列表

退出或当前请求返回 401
  → 清除凭证和业务状态
  → 丢弃 KeepAlive 缓存与迟到请求结果
  → 跳到登录页
~~~

登录仍然保存在内存中。刷新整个页面会丢失凭证，所以会回到登录页；这不是数据库数据被清空。

## 11. IDEA 中怎样启动

在项目根目录的终端执行：

~~~bash
python3 scripts/init-db-env.py
docker compose --env-file docker/.env -f docker/compose.yml up -d --wait
scripts/backend.sh spring-boot:run
~~~

另开终端启动前端：

~~~bash
npm run dev --prefix frontend -- --host 127.0.0.1
~~~

如果 IDEA 终端已经在 backend 目录，使用：

~~~bash
../scripts/backend.sh spring-boot:run
~~~

启动脚本会读取被忽略的 docker/.env 并导出环境变量。直接运行 ./mvnw spring-boot:run 不会自动加载它，可能缺少 DB_PASSWORD 或 JWT_SECRET。不要把密码写进代码或提交到 Git。

本课不修改表结构，也不重置已有账号和知识库。

## 12. 验证与练习

- 后端 18 项真实 MySQL 集成测试通过，原有 CRUD 测试改为携带已登录用户凭证。
- 匿名和无效凭证的 GET、POST、PUT、DELETE 返回 401，记录不变；删除账号后其有效签名凭证也不能访问知识库。
- 前端 9 项测试与构建通过，覆盖路由守卫、站内返回路径、凭证附带范围、旧 401、新旧请求交错、退出后的迟到读写结果。
- 浏览器直接访问知识库已验证跳到登录页并保留返回路径；运行中的开发接口匿名读取返回 401。
- 有效账号下的业务操作由独立测试库中的真实 HTTP 测试验证；未代用户登录或操作现有数据。

练习：未登录访问工作台，登录后确认回到工作台；进入知识库搜索并打开表单，然后退出，重新登录后确认旧搜索和弹窗不再保留。最后刷新页面，观察为何需要重新登录。

下一课进入角色权限：在“已登录”的基础上，继续判断“允许执行哪些操作”。
