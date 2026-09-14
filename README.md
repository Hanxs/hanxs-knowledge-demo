# hanxs-knowledge-demo

基于 **Spring Boot 4 + Spring AI 2.0** 的 RAG（检索增强生成）知识库问答示例。
底层对接阿里云百炼 DashScope 的 OpenAI 兼容接口（通义千问 `qwen-plus` + `text-embedding-v3`），
向量库使用 **PostgreSQL + pgvector**。

## 功能特性

- 文档入库：支持 TXT / PDF / Word 等，读取 → 补充元数据 → 切分 → 向量化 → 持久化
- 标准 RAG 问答：向量检索 + 大模型生成
- 流式问答（SSE）
- 多轮对话：会话级记忆 + **向量数据落库（PgVector）**，重启不丢失、不重复向量化
- 知识库管理：加载/重载、检索预览、按来源删除、状态查询、数据迁移
- 旧数据迁移：把 `SimpleVectorStore` JSON 快照一次性导入 PgVector（保留原始向量）
- 向量库可插拔：`pgvector` / `simple`（内存，离线或单测用）一行配置切换
- 统一响应体 + 全局异常处理
- 管理接口令牌鉴权
- Actuator 健康检查 + Swagger 接口文档

## 快速开始

### 1. 准备 PostgreSQL + pgvector

需要 **PostgreSQL 15+** 与 **pgvector 扩展**。

macOS（Homebrew）：

```bash
brew install postgresql@18 pgvector
brew services start postgresql@18

createdb knowledge_db
psql -d knowledge_db -f scripts/pgvector-init.sql
```

Docker（更省事）：

```bash
docker run -d --name knowledge-pg -p 5432:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=knowledge_db \
  pgvector/pgvector:pg16
```

> `scripts/pgvector-init.sql` 会启用 `vector` / `hstore` / `uuid-ossp` 三个扩展，
> 并创建 1024 维的 `public.vector_store` 表与 HNSW 索引。
> 应用侧开启 `initialize-schema=true` 时也会自动完成同样的事，二者做其一即可。

### 2. 配置密钥与数据库连接

编辑 `src/main/resources/application-local.yml`（已被 `.gitignore` 忽略，不会提交）：

```yaml
spring:
  # 本地数据库连接（覆盖 application.yml 中的默认值）
  datasource:
    url: jdbc:postgresql://localhost:5432/knowledge_db
    username: postgres
    password: 你的密码
  ai:
    openai:
      api-key: 你的DashScope密钥
```

该文件随 `local` profile **自动生效，无需设置任何环境变量**。

### 3. 启动

```bash
./gradlew bootRun
```

默认端口 `8080`，默认 profile 为 `dev`。

### 4. （可选）迁移旧向量数据

如果你之前用 `SimpleVectorStore` 跑过并留下了 `data/vector-store.json`：

```bash
./gradlew bootRun --args='--app.rag.migrate.enabled=true --app.rag.load-on-startup=false'
```

迁移是**幂等**的（`ON CONFLICT (id) DO NOTHING`，且向量表非空时自动跳过），
会原样保留旧向量，不会调用 Embedding 接口。也可在应用运行中调用
`POST /rag/admin/knowledge/migrate` 手动触发。

## 配置说明（`app.rag.*`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `auth-token` | 空 | 管理接口令牌，为空则开发模式不鉴权 |
| `load-on-startup` | true | 启动时自动加载知识库 |
| `sync-loaded-sources` | true | 启动时从向量表回填已加载来源，避免重启重复向量化 |
| `top-k` | 4 | 检索召回条数 |
| `similarity-threshold` | 0.5 | 相似度阈值 |
| `max-messages` | 10 | 对话记忆窗口大小 |
| `vector-store-path` | ./data/vector-store.json | 旧向量库 JSON，pgvector 模式下作为迁移数据源 |
| `chat-memory-path` | ./data/chat-memory.json | 对话记忆持久化路径 |
| `migrate.enabled` | false | 启动时执行一次性向量数据迁移 |
| `migrate.skip-if-not-empty` | true | 向量表非空时跳过迁移 |
| `migrate.source-file` | 空 | 迁移数据源，留空复用 `vector-store-path` |
| `migrate.keep-source-file` | true | 迁移后是否保留源 JSON（false 则重命名为 `.bak`） |
| `splitter.*` | - | 文本切分参数 |
| `documents` | - | 知识文档清单，按扩展名选择解析器 |

## 向量库配置（PgVector）

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/knowledge_db
    username: postgres
    password: ${PG_PASSWORD:postgres}
  ai:
    vectorstore:
      # pgvector（默认，落库持久化）/ simple（内存，离线或单测）/ none（不装配）
      type: ${VECTOR_STORE_TYPE:pgvector}
      pgvector:
        dimensions: 1024          # 必须与 Embedding 模型输出一致
        schema-name: public       # 注意属性名是 schema-name
        table-name: vector_store
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        initialize-schema: true   # 自动建表建索引，并自动 CREATE EXTENSION
        schema-validation: true
        max-document-batch-size: 10000
```

两点最容易踩坑：

1. **维度**：`text-embedding-v3` 输出 **1024** 维，不是 1536（那是 OpenAI
   `text-embedding-3-small` 的维度）。写错会直接报维度不匹配。
2. **属性名**：是 `dimensions`（复数）和 `schema-name`，不是 `dimension` / `schema`。

数据库连接信息除 `application-local.yml` 外，也可用环境变量覆盖：
`PG_URL` / `PG_USERNAME` / `PG_PASSWORD` / `VECTOR_STORE_TYPE`。

## 接口一览

### 问答

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/rag/ask?msg=问题&sessionId=可选` | 标准 RAG 问答 |
| GET | `/rag/ask/stream?msg=问题&sessionId=可选` | 流式问答（SSE） |

### 知识库管理（需请求头 `X-Admin-Token`，未配置令牌时放行）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/rag/admin/knowledge/load?force=false` | 加载/重载知识库 |
| POST | `/rag/admin/knowledge/upload` | 上传文档并入库 |
| POST | `/rag/admin/knowledge/migrate` | 一次性迁移旧向量数据到 PgVector |
| GET | `/rag/admin/knowledge/search?q=关键词&topK=4` | 检索预览 |
| DELETE | `/rag/admin/knowledge?source=knowledge.txt` | 按来源删除 |
| GET | `/rag/admin/knowledge/stats` | 知识库状态（含向量库类型与向量条数） |

### 运维

- 健康检查：`GET /actuator/health`
- 接口文档：`http://localhost:8080/swagger-ui.html`（仅 dev 开启）

## 知识维护（新增 / 更新 / 删除）

### 支持格式

| 格式 | 解析器 |
| --- | --- |
| `.txt` `.md` | TextReader |
| `.pdf` | PagePdfDocumentReader（PDFBox） |
| `.doc` `.docx` `.xlsx` `.pptx` `.odt` `.rtf` | TikaDocumentReader（Apache Tika） |

### 方式一：页面上传（推荐，无需重启）

进入「知识库管理」→「上传新知识」→ 选择文件，即自动解析入库。

文件保存在 `app.rag.doc-dir`（默认 `./data/docs`），后续启动会被自动发现。

### 方式二：直接丢进文档目录

把文件复制到 `./data/docs/`，然后调用：

```bash
curl -X POST "http://localhost:8080/rag/admin/knowledge/load?force=true"
```

目录下的所有受支持格式文件会被自动扫描加载。

### 方式三：声明式（适合打包在 jar 内的固定文档）

在 `application.yml` 中配置，适合随应用一起发布的文档：

```yaml
app:
  rag:
    documents:
      - classpath:doc/knowledge.txt
```

### 加载策略

| 调用 | 行为 |
| --- | --- |
| `POST .../load`（force 默认 false） | **增量加载**，只处理新增来源，已入库的跳过 |
| `POST .../load?force=true` | **全量重载**，会先清除该来源历史片段再写入，不会产生重复向量 |

> PgVector 是持久化存储：启动时会把向量表里已有的来源回填为"已加载"，
> 所以重启**不会**重新向量化已有文档（由 `app.rag.sync-loaded-sources` 控制）。
> 文档内容有变更时，用 `force=true` 重新入库即可。

### 删除

```bash
curl -X DELETE "http://localhost:8080/rag/admin/knowledge?source=knowledge.txt"
```

或在「知识库管理」输入来源文件名点击删除。

## 常见问题

### 提示「模型服务鉴权失败：未配置或无效的 API Key」

说明密钥没有生效（应用本身仍可正常启动）。

**推荐方式** —— 填写本地配置文件（已随 `local` profile 自动加载，无需环境变量）：

编辑 `src/main/resources/application-local.yml`，去掉注释并填入密钥：

```yaml
spring:
  ai:
    openai:
      api-key: 你的DashScope密钥
```

**备选方式** —— 环境变量：

```bash
DASHSCOPE_API_KEY=你的密钥 ./gradlew bootRun
```

若在 IDE 中运行，则需到 Run/Debug Configurations → Environment variables 添加
`DASHSCOPE_API_KEY`（终端里 `export` 对 IDE 启动的进程无效）。

两种方式任选其一，**改完必须重启应用**，随后调用
`POST /rag/admin/knowledge/load?force=true` 加载知识库。

## 目录结构

```
src/main/java/com/example/knowledge/
├── KnowlledgeApplication.java        启动类
├── common/                           统一响应体、全局异常处理
├── conf/                             配置：属性绑定、向量库/记忆/客户端装配、鉴权、OpenAPI
├── rag/                              知识库加载、启动初始化、文件化对话记忆仓库、向量数据迁移
└── controller/                       问答接口、知识库管理接口

scripts/
└── pgvector-init.sql                 建库脚本：扩展 + 向量表 + HNSW 索引

docs/
├── 技术方案.md
└── PgVector迁移方案.md
```

## 相关文档

- [技术方案](docs/技术方案.md)
- [PgVector 迁移方案](docs/PgVector迁移方案.md)
