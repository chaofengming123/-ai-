# 企业 AI 知识管理平台——全栈与 RAG 实战学习手册

## 1. 项目目标

本项目的目标不是单独学习 Vue、Spring Boot 或 RAG，而是从零实现一个可以实际运行和部署的企业级 AI 知识管理平台。

最终系统支持：

- 用户登录与权限管理
- 企业知识库创建与管理
- PDF、Word、Markdown 等文档上传
- 文档解析与文本切分
- Embedding 向量化
- 向量数据库存储与检索
- RAG 问答
- 引用来源展示
- 多知识库权限隔离
- Redis 缓存
- 异步文档处理
- Hybrid Search
- Reranker
- Docker 部署
- Nginx 反向代理
- 日志、测试、异常处理
- 基础性能优化
- 基础 AI 安全设计

最终目标是能够独立解释并实现如下架构：

```text
                    Browser
                       │
                       ▼
                  Nginx
                       │
             ┌─────────┴─────────┐
             │                   │
           Vue 3            Spring Boot
                                 │
                  ┌──────────────┼──────────────┐
                  │              │              │
                MySQL          Redis          MinIO
                                                 │
                                                 ▼
                                         Document Parser
                                                 │
                                                 ▼
                                            Chunking
                                                 │
                                                 ▼
                                           Embedding
                                                 │
                                                 ▼
                                            Qdrant
                                                 │
                                                 ▼
                                          Vector Search
                                                 │
                                                 ▼
                                            Reranker
                                                 │
                                                 ▼
                                               LLM
```

---

# 2. 技术栈

## 前端

- Vue 3
- Vite
- JavaScript
- Vue Router
- Pinia
- Axios
- Element Plus

后期根据需要可以加入：

- TypeScript
- Markdown Renderer
- SSE / Streaming Response

---

## 后端

- Java
- Spring Boot
- Spring MVC
- Spring Security
- MyBatis-Plus
- Maven

后期加入：

- Validation
- Global Exception Handler
- AOP
- Async
- Scheduler

---

## 数据层

### MySQL

存储：

- 用户
- 角色
- 权限
- 知识库
- 文档元数据
- 聊天记录
- RAG 会话信息

### Redis

用于：

- 缓存
- Token 管理
- 限流
- 热点数据
- 部分 AI 查询缓存

### MinIO

用于：

- PDF
- Word
- Markdown
- 图片
- 原始企业文档

### Qdrant

用于：

- Chunk Vector
- Vector Search
- Metadata Filtering

---

# 3. 最终业务功能

系统主要包括以下页面：

```text
Login
│
├── Dashboard
│
├── Knowledge Base
│   ├── Knowledge Base List
│   └── Knowledge Base Detail
│
├── Documents
│   ├── Upload
│   ├── Processing Status
│   └── Document Detail
│
├── AI Chat
│   ├── New Conversation
│   ├── History
│   └── Source Citation
│
├── User Management
│
└── Role & Permission
```

---

# 4. 核心业务流程

## 4.1 普通 Web 请求

用户打开知识库页面：

```text
Vue
 ↓
Axios
 ↓
GET /api/knowledge-bases
 ↓
Spring Boot Controller
 ↓
Service
 ↓
Mapper
 ↓
MySQL
 ↓
JSON
 ↓
Vue
```

这是整个项目最基础的一条链路。

必须彻底掌握。

---

# 5. RAG 业务流程

用户上传：

```text
company-policy.pdf
```

后台执行：

```text
Upload
 ↓
MinIO
 ↓
Document Parser
 ↓
Text
 ↓
Chunking
 ↓
Embedding
 ↓
Vector
 ↓
Qdrant
```

用户提问：

```text
上海出差酒店最多报销多少钱？
```

执行：

```text
Question
 ↓
Embedding
 ↓
Vector Search
 ↓
Metadata Filtering
 ↓
Top K Chunks
 ↓
Reranker
 ↓
Prompt Construction
 ↓
LLM
 ↓
Answer
 ↓
Citation
```

这就是完整 RAG Pipeline。

---

# 6. 学习原则

整个项目采用：

```text
知识点
 ↓
实现功能
 ↓
运行
 ↓
调试
 ↓
测试
 ↓
Git Commit
 ↓
解释原理
 ↓
面试问题
```

而不是：

```text
看教程
 ↓
复制代码
 ↓
项目结束
```

每完成一个功能必须做到：

1. 项目能够运行
2. 知道请求经过哪些组件
3. 能解释为什么这么设计
4. 能处理基本错误
5. 有 Git Commit
6. 可以回答对应面试问题

---

# 7. 项目阶段

---

# Phase 0：环境和工程准备

## 学习目标

建立真实全栈开发环境。

理解：

- JDK
- Maven
- Node.js
- npm
- MySQL
- Redis
- Docker
- Git
- IDE
- 前后端项目为什么分开

## 安装工具

推荐：

```text
JDK 21
Maven
Node.js LTS
npm
MySQL 8
Redis
Docker Desktop
Git
IntelliJ IDEA
VS Code
```

检查：

```bash
java -version

mvn -version

node -v

npm -v

git --version

docker --version
```

---

## Git 仓库

项目：

```text
enterprise-ai-knowledge-platform/
```

结构：

```text
enterprise-ai-knowledge-platform/

├── frontend/
├── backend/
├── docker/
├── docs/
├── README.md
└── .gitignore
```

第一次提交：

```bash
git init

git add .

git commit -m "chore: initialize enterprise AI knowledge platform"
```

---

## Phase 0 验收

必须能够回答：

- JDK 和 JVM 有什么区别？
- Maven 是什么？
- Node.js 为什么是 Vue 开发环境的一部分？
- npm 是什么？
- Docker 是什么？
- Git 和 GitHub 有什么区别？
- 为什么前后端通常分开开发？

---

# Phase 1：Vue 3 前端基础

第一阶段暂时不接后端。

目标是建立企业后台 UI。

---

## 学习内容

需要掌握：

### JavaScript

```text
let
const

Array
Object

function

arrow function

map
filter

Promise

async
await

import
export
```

重点：

```javascript
async function loadData() {
    const response = await fetch('/api/users')
}
```

必须理解异步。

---

## Vue 核心

学习：

```text
ref
reactive
computed
watch

v-if
v-for
v-model
@click

Props
Emit

Component
Lifecycle
```

---

## 创建 Vue 项目

```bash
npm create vite@latest frontend
```

选择：

```text
Vue
JavaScript
```

进入：

```bash
cd frontend

npm install

npm run dev
```

---

## 安装依赖

```bash
npm install vue-router
npm install pinia
npm install axios
npm install element-plus
```

---

## 第一版目录

```text
frontend/src/

├── api/
│
├── assets/
│
├── components/
│   ├── AppHeader.vue
│   └── AppSidebar.vue
│
├── layouts/
│   └── MainLayout.vue
│
├── router/
│   └── index.js
│
├── stores/
│
├── utils/
│
├── views/
│   ├── LoginView.vue
│   ├── DashboardView.vue
│   ├── KnowledgeBaseView.vue
│   ├── DocumentView.vue
│   └── ChatView.vue
│
├── App.vue
└── main.js
```

---

## 第一阶段实际功能

实现：

```text
/login

/dashboard

/knowledge-bases

/documents

/chat
```

先使用 Mock Data。

例如：

```javascript
const knowledgeBases = [
    {
        id: 1,
        name: 'HR Knowledge Base',
        description: 'Human resource policies'
    },
    {
        id: 2,
        name: 'Engineering Knowledge Base',
        description: 'Engineering documentation'
    }
]
```

页面展示：

```text
Knowledge Bases

--------------------------------
HR Knowledge Base
Human resource policies
--------------------------------

Engineering Knowledge Base
Engineering documentation
--------------------------------
```

---

## Phase 1 Git Commit

```text
feat(frontend): initialize Vue application

feat(frontend): add main application layout

feat(frontend): configure Vue Router

feat(frontend): add knowledge base list page
```

---

## Phase 1 验收

需要能够解释：

- Vue 是什么？
- Vue 为什么是响应式框架？
- ref 是什么？
- reactive 是什么？
- Props 是什么？
- Emit 是什么？
- Vue Router 是什么？
- SPA 是什么？
- Pinia 是什么？
- Axios 是什么？
- async/await 是什么？

---

# Phase 2：Spring Boot 后端

建立真正的 REST API。

---

## 学习目标

理解：

```text
Controller
Service
Mapper
Entity
DTO
VO
```

并理解：

```text
IOC
DI
Bean
Spring Container
```

---

## 创建项目

创建：

```text
backend/
```

Dependencies：

```text
Spring Web
Validation
Lombok
MySQL Driver
MyBatis-Plus
```

---

## 后端目录

```text
backend/src/main/java/com/example/aiknowledge/

├── controller/
├── service/
├── service/impl/
├── mapper/
├── entity/
├── dto/
├── vo/
├── config/
├── exception/
├── security/
└── rag/
```

---

# Phase 3：MySQL + Knowledge Base CRUD

第一个真正的全栈功能：

```text
Knowledge Base CRUD
```

数据库：

```sql
CREATE TABLE knowledge_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    owner_id BIGINT,
    created_at DATETIME,
    updated_at DATETIME
);
```

---

## API

```text
GET

/api/knowledge-bases
```

查询全部知识库。

```text
GET

/api/knowledge-bases/{id}
```

```text
POST

/api/knowledge-bases
```

```text
PUT

/api/knowledge-bases/{id}
```

```text
DELETE

/api/knowledge-bases/{id}
```

---

## 完整链路

必须亲自实现：

```text
Vue
 ↓
Axios
 ↓
REST
 ↓
Controller
 ↓
Service
 ↓
Mapper
 ↓
MySQL
```

这是本项目第一道重要里程碑。

---

# Phase 4：登录和权限

实现：

```text
Username
Password
 ↓
Spring Security
 ↓
Authentication
 ↓
JWT
```

前端：

```text
Login
 ↓
POST /api/auth/login
 ↓
JWT
 ↓
Pinia
```

后续请求：

```http
Authorization: Bearer xxx
```

---

## RBAC

数据库：

```text
user

role

permission

user_role

role_permission
```

角色示例：

```text
ADMIN

USER
```

后期扩展：

```text
HR

FINANCE

ENGINEERING
```

---

## 权限目标

HR：

```text
HR Knowledge Base
```

Engineering：

```text
Engineering Knowledge Base
```

不同用户不能检索没有权限的企业知识。

这是 RAG 安全非常重要的一部分。

---

# Phase 5：文档上传

实现：

```text
POST /api/documents
```

支持：

```text
PDF
DOCX
TXT
Markdown
```

---

## 文件不能直接全部存 MySQL

设计：

```text
MySQL

Document Metadata
```

+

```text
MinIO

Original File
```

document：

```text
id
knowledge_base_id
file_name
object_key
file_type
status
created_at
```

status：

```text
UPLOADED

PROCESSING

COMPLETED

FAILED
```

---

# Phase 6：LLM Chat

在加入 RAG 前先实现普通 LLM Chat。

流程：

```text
Vue Chat
 ↓
Spring Boot
 ↓
LLM API
 ↓
Response
```

理解：

```text
System Prompt

User Prompt

Context Window

Token

Temperature

Streaming
```

之后实现：

```text
SSE
```

让回答逐字返回。

---

# Phase 7：RAG Ingestion Pipeline

这是 AI 项目的核心阶段。

上传：

```text
PDF
```

执行：

```text
Parser

↓

Text

↓

Chunk

↓

Embedding

↓

Qdrant
```

---

## Chunk

例如：

```text
Chunk Size

500 tokens
```

Overlap：

```text
50 tokens
```

后面会实验：

```text
300

500

800
```

对检索效果的影响。

---

## Qdrant Point

示例：

```json
{
    "id": 1001,

    "vector": [
        0.182,
        -0.231,
        0.712
    ],

    "payload": {
        "knowledgeBaseId": 10,
        "documentId": 100,
        "chunkIndex": 3,
        "content": "..."
    }
}
```

---

# Phase 8：RAG Retrieval

用户：

```text
上海出差酒店最多可以报销多少钱？
```

系统：

```text
Query
 ↓
Embedding
 ↓
Vector Search
 ↓
Top K
```

例如：

```text
Top K = 10
```

加入：

```text
knowledgeBaseId
```

过滤。

这是：

```text
Metadata Filtering
```

---

## Prompt

构造：

```text
You are an enterprise knowledge assistant.

Use only the context below.

If the context does not contain enough information,
say that the information cannot be found.

Context:

...

Question:

...
```

---

## Citation

最终返回：

```text
上海出差住宿标准最高为800元/晚。

Sources:

员工差旅管理制度.pdf
Page 13
```

---

# Phase 9：高级 RAG

实现：

```text
Hybrid Search
```

即：

```text
BM25
+
Vector Search
```

解决例如：

```text
COMSM0166
```

这种精确字符串搜索问题。

---

## Reranker

流程：

```text
Vector Search

Top 20

↓

Reranker

↓

Top 5

↓

LLM
```

学习：

```text
Recall

Precision

Reranking

Top K
```

---

# Phase 10：Redis 与异步处理

## Redis

加入：

```text
Cache

Rate Limit

Token Blacklist

Hot Knowledge Base

AI Query Cache
```

---

## 异步文档处理

错误设计：

```text
HTTP

↓

500 page PDF parsing

↓

Embedding

↓

30 seconds later

↓

Response
```

正确：

```text
Upload

↓

Save

↓

Return

status = PROCESSING
```

后台：

```text
Worker

↓

Parse

↓

Chunk

↓

Embedding

↓

Qdrant

↓

COMPLETED
```

---

## Kafka

作为增强内容学习：

```text
Spring Boot

↓

Kafka

↓

Document Processing Worker
```

理解：

```text
Decoupling

Async Processing

Peak Shaving

Message Queue
```

Kafka 不作为第一版系统的硬性要求。

---

# Phase 11：Docker 部署

最终容器：

```text
frontend

backend

mysql

redis

minio

qdrant
```

使用：

```text
docker-compose.yml
```

---

## Nginx

生产环境：

```text
Browser

↓

Nginx

├── /
│
│   Vue
│
└── /api
    │
    Spring Boot
```

---

# Phase 12：测试与企业级优化

学习：

```text
Unit Test

Integration Test

API Test

Frontend Test
```

Spring：

```text
JUnit
Mockito
MockMvc
```

接口测试：

```text
Postman
```

---

# 13. Observability

增加：

```text
Logging

Metrics

Tracing
```

关注：

```text
request latency

LLM latency

retrieval latency

token usage

error rate
```

---

# 14. RAG Evaluation

不能只说：

```text
回答看起来不错
```

需要开始评估：

```text
Retrieval Precision

Retrieval Recall

Hit Rate

MRR

Answer Correctness

Faithfulness
```

准备：

```text
Question

Expected Answer

Expected Source
```

建立测试数据集。

---

# 15. AI 安全

企业 RAG 必须学习：

```text
Prompt Injection

Data Leakage

Permission Bypass

Malicious File

Sensitive Data

LLM Hallucination
```

重点原则：

```text
用户没有权限的数据

绝不能进入 Retrieval Context
```

权限应该：

```text
Retrieval之前
```

执行，而不是只依赖 Prompt。

---

# 16. 最终数据库

预计包括：

```text
user

role

permission

user_role

role_permission

knowledge_base

knowledge_base_member

document

document_chunk

conversation

message
```

---

# 17. 最终项目结构

```text
enterprise-ai-knowledge-platform/

├── frontend/
│
│   ├── src/
│   │   ├── api/
│   │   ├── components/
│   │   ├── layouts/
│   │   ├── router/
│   │   ├── stores/
│   │   ├── utils/
│   │   └── views/
│   │
│   └── package.json
│
├── backend/
│
│   ├── src/main/java/
│   │
│   └── pom.xml
│
├── docker/
│
├── docs/
│
│   ├── architecture.md
│   ├── database.md
│   └── rag.md
│
├── docker-compose.yml
│
└── README.md
```

---

# 18. Git 学习规则

禁止一次提交：

```text
finished project
```

应该逐阶段提交。

例如：

```text
chore: initialize project

feat(frontend): initialize Vue application

feat(frontend): add application layout

feat(frontend): add knowledge base page

feat(backend): initialize Spring Boot application

feat(knowledge-base): add knowledge base REST API

feat(auth): implement JWT authentication

feat(document): implement document upload

feat(storage): integrate MinIO

feat(rag): implement document chunking

feat(rag): integrate embedding model

feat(rag): integrate Qdrant vector search

feat(rag): add source citations

feat(rag): add metadata filtering

feat(rag): add reranking

feat(cache): integrate Redis

chore: add Docker deployment
```

这会同时训练真实企业开发习惯。

---

# 19. 每阶段固定学习方式

每个阶段都按照以下格式推进。

## Step 1

先解释概念。

例如：

```text
Vue Router 是什么？
```

## Step 2

查看项目为什么需要它。

## Step 3

实际写代码。

## Step 4

启动项目。

## Step 5

制造一个错误。

## Step 6

阅读错误日志。

## Step 7

修复。

## Step 8

使用 Git 提交。

## Step 9

总结请求流程。

## Step 10

做面试题。

---

# 20. 第一阶段任务

现在正式开始。

当前目标：

```text
Enterprise AI Knowledge Platform v0.1
```

实现：

```text
Sidebar

Dashboard

Knowledge Base

Documents

AI Chat
```

第一阶段全部使用 Mock Data。

暂时：

```text
不连接数据库

不实现AI

不实现RAG
```

重点是理解：

```text
Vue
Component
Router
State
Frontend Project Structure
```

---

# 21. 第一阶段完成后的页面

Sidebar：

```text
AI Knowledge

Dashboard

Knowledge Bases

Documents

AI Chat
```

Knowledge Bases：

```text
Knowledge Bases

+ Create Knowledge Base


HR Knowledge Base
Human resource policies
3 documents


Engineering Knowledge Base
Engineering documentation
10 documents
```

点击：

```text
Create Knowledge Base
```

弹出：

```text
Name

Description

Create
```

使用 Mock Data 创建。

这一阶段结束以后，再接 Spring Boot API。

---

# 22. 学习过程中不允许跳过的核心问题

必须逐步理解：

```text
Vue为什么需要组件？

Router为什么存在？

Pinia为什么存在？

Axios为什么存在？

HTTP到底发送了什么？

REST是什么？

Controller为什么不能直接写所有逻辑？

Service是什么？

Mapper是什么？

IOC是什么？

DI是什么？

JWT解决什么问题？

Redis解决什么问题？

Vector DB和MySQL有什么区别？

Embedding是什么？

为什么需要Chunk？

为什么需要Reranker？

为什么RAG仍然可能产生幻觉？

为什么权限过滤必须发生在Retrieval阶段？
```

如果这些问题无法解释，就不急着继续增加新技术。

---

# 23. 项目最终面试介绍目标

完成项目以后，应能够独立介绍：

“项目采用 Vue 3 和 Spring Boot 构建前后端分离的企业 AI 知识管理平台。前端使用 Vue Router、Pinia 和 Axios，后端采用 Controller、Service 和 Mapper 分层，并使用 Spring Security 和 JWT 处理认证授权。

用户、权限、知识库和文档元数据存储在 MySQL，原始文件存储在 MinIO。文档上传后通过异步任务执行解析、Chunk、Embedding，并写入 Qdrant。

用户提问时，系统首先根据用户权限限制可搜索的知识库，然后执行向量检索和 Metadata Filtering。候选 Chunk 可以进一步经过 Hybrid Search 和 Reranker，最后将相关上下文发送给 LLM。回答中同时返回文档引用。

Redis 用于缓存、限流和部分会话数据。整个系统通过 Docker Compose 部署，并由 Nginx 提供前端静态资源和 API 反向代理。”

达到这个程度后，这个项目不仅是一个学习 Demo，也可以成为全栈 / AI 应用开发方向的求职项目。