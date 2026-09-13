# hanxs-knowledge-demo

基于 **Spring Boot 4 + Spring AI 2.0** 的 RAG（检索增强生成）知识库问答示例。
底层对接阿里云百炼 DashScope 的 OpenAI 兼容接口（通义千问 `qwen-plus` + `text-embedding-v3`）。

## 功能特性

- 文档入库：支持 TXT / PDF，读取 → 补充元数据 → 切分 → 向量化 → 持久化
- 标准 RAG 问答：向量检索 + 大模型生成
- 流式问答（SSE）
- 多轮对话：会话级记忆，且**记忆与向量库均落盘持久化**，重启不丢失
- 知识库管理：加载/重载、检索预览、按来源删除、状态查询
- 统一响应体 + 全局异常处理
- 管理接口令牌鉴权
- Actuator 健康检查 + Swagger 接口文档

## 快速开始

### 1. 配置密钥（必须）

编辑 `src/main/resources/application-local.yml`（已被 `.gitignore` 忽略，不会提交），
去掉注释并填入你的密钥：

```yaml
spring:
  ai:
    openai:
      api-key: 你的DashScope密钥
```

该文件随 `local` profile **自动生效，无需设置任何环境变量**。

### 2. 启动

```bash
./gradlew bootRun
```

默认端口 `8080`，默认 profile 为 `dev`。

## 配置说明（`app.rag.*`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `auth-token` | 空 | 管理接口令牌，为空则开发模式不鉴权 |
| `load-on-startup` | true | 启动时自动加载知识库 |
| `top-k` | 4 | 检索召回条数 |
| `similarity-threshold` | 0.5 | 相似度阈值 |
| `max-messages` | 10 | 对话记忆窗口大小 |
| `vector-store-path` | ./data/vector-store.json | 向量库持久化路径 |
| `chat-memory-path` | ./data/chat-memory.json | 对话记忆持久化路径 |
| `splitter.*` | - | 文本切分参数 |
| `documents` | - | 知识文档清单，按扩展名选择解析器 |

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
| GET | `/rag/admin/knowledge/search?q=关键词&topK=4` | 检索预览 |
| DELETE | `/rag/admin/knowledge?source=knowledge.txt` | 按来源删除 |
| GET | `/rag/admin/knowledge/stats` | 知识库状态 |

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
├── rag/                              知识库加载、启动初始化、文件化对话记忆仓库
└── controller/                       问答接口、知识库管理接口
```
