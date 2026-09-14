# 向量库迁移方案：SimpleVectorStore → PgVector

> 目标：把向量库从进程内内存实现（`SimpleVectorStore` + JSON 落盘）迁移到
> PostgreSQL + pgvector，实现真正的持久化、多实例共享与可扩展检索，
> 并把历史向量数据无损搬运过去。

## 1. 迁移前 vs 迁移后

| 维度 | 迁移前 `SimpleVectorStore` | 迁移后 `PgVectorStore` |
| --- | --- | --- |
| 存储位置 | JVM 堆内存，靠 `vector-store.json` 快照兜底 | PostgreSQL `public.vector_store` 表 |
| 检索算法 | 全量暴力遍历（O(n)） | HNSW 近似最近邻索引（O(log n)） |
| 数据规模 | 万级片段即明显变慢 | 百万级可用 |
| 多实例部署 | 每实例一份内存副本，数据不一致 | 共享同一份数据 |
| 重启行为 | 从 JSON 反序列化全量到内存 | 无需加载，直接查库 |
| 元数据过滤 | 内存过滤 | SQL 过滤（`metadata ->> 'k'`），可加索引 |
| 增量更新 | 需整体重写 JSON | 单行 upsert / delete |

## 2. 准备工作

### 2.1 安装 PostgreSQL 15+ 与 pgvector

```bash
# macOS
brew install postgresql@18 pgvector
brew services start postgresql@18

# 或 Docker（自带 pgvector 的镜像）
docker run -d --name knowledge-pg -p 5432:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=knowledge_db \
  pgvector/pgvector:pg16
```

### 2.2 建库并启用扩展

```bash
createdb knowledge_db
psql -d knowledge_db -f scripts/pgvector-init.sql

# 手动确认
psql -d knowledge_db -c "SELECT extname, extversion FROM pg_extension WHERE extname='vector';"
```

脚本内容等价于：

```sql
CREATE EXTENSION IF NOT EXISTS vector;       -- 向量类型 + HNSW/IVFFlat 索引
CREATE EXTENSION IF NOT EXISTS hstore;       -- PgVectorStore 元数据过滤辅助
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";  -- uuid_generate_v4()

CREATE TABLE IF NOT EXISTS public.vector_store (
    id        uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(1024)
);
CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON public.vector_store USING hnsw (embedding vector_cosine_ops);
```

> `CREATE EXTENSION` 通常要求超级用户（pgvector 在 PG13+ 属 trusted extension，
> 库所有者即可执行）。生产环境一般由 DBA 预置，应用账号只需读写表权限。
> 若应用侧开启 `initialize-schema=true`，`PgVectorStore` 会自动执行上述扩展与建表语句。

## 3. 代码改造

### 3.1 依赖（`build.gradle`）

```gradle
implementation 'org.springframework.ai:spring-ai-starter-vector-store-pgvector'
```

starter 会传递引入 `spring-ai-pgvector-store`、`spring-boot-starter-jdbc`、
`postgresql` 驱动与 `com.pgvector:pgvector`。

### 3.2 数据源与向量库配置（`application.yml`）

```yaml
spring:
  datasource:
    url: ${PG_URL:jdbc:postgresql://localhost:5432/knowledge_db}
    username: ${PG_USERNAME:postgres}
    password: ${PG_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver
  ai:
    vectorstore:
      # pgvector（默认）/ simple（内存，离线或单测）/ none
      type: ${VECTOR_STORE_TYPE:pgvector}
      pgvector:
        dimensions: ${PGVECTOR_DIMENSIONS:1024}
        schema-name: ${PGVECTOR_SCHEMA:public}
        table-name: ${PGVECTOR_TABLE:vector_store}
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        initialize-schema: ${PGVECTOR_INIT_SCHEMA:true}
        schema-validation: ${PGVECTOR_SCHEMA_VALIDATION:true}
        max-document-batch-size: 10000
```

**注意两处易错点：**

| 易错写法 | 正确写法 | 说明 |
| --- | --- | --- |
| `dimension: 1536` | `dimensions: 1024` | 属性名是复数；且 `text-embedding-v3` 输出 1024 维，1536 是 OpenAI `text-embedding-3-small` |
| `schema: public` | `schema-name: public` | 属性名不同 |

维度写错的表现：启动时 `PgVectorSchemaValidator` 直接报错，或写入后检索恒为空。

### 3.3 Spring 装配的变化

Spring AI 2.0 的 `PgVectorStoreAutoConfiguration` 关键条件：

```java
@ConditionalOnClass({ PgVectorStore.class, DataSource.class, JdbcTemplate.class })
@ConditionalOnProperty(name = "spring.ai.vectorstore.type", havingValue = "pgvector", matchIfMissing = true)
```

它**没有** `@ConditionalOnMissingBean`，所以：

1. 默认情况（`spring.ai.vectorstore.type` 未配置或为 `pgvector`）由它自动创建 `PgVectorStore`，
   项目里**必须删掉**手写的 `SimpleVectorStore` Bean，否则容器内出现两个 `VectorStore`；
2. 需要内存实现时，把 `type` 改成 `simple`，自动配置让位，由项目里的
   `@ConditionalOnProperty(havingValue = "simple")` Bean 接管。

改造后的 `VectorStoreConfig`：

```java
@Bean
@ConditionalOnProperty(name = "spring.ai.vectorstore.type", havingValue = "simple")
public VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
    SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
    // ... 从 JSON 恢复，仅离线/单测场景使用
    return store;
}
```

其余注入点统一改为面向接口：

```java
private final VectorStore vectorStore;   // 原来是 SimpleVectorStore
```

落盘逻辑也要收窄，因为 PgVector 已经持久化：

```java
private void persist() {
    if (!(vectorStore instanceof SimpleVectorStore simple)) {
        return;      // PgVector 模式无需写 JSON
    }
    simple.save(file);
}
```

### 3.4 重启后的增量加载

`SimpleVectorStore` 时代，"已加载来源"只存在于内存中，重启必然清空；
换成持久化向量库后，如果不处理就会**每次启动都重新切分并重新向量化全部文档**。

解决办法：启动时从向量表回填来源集合，让增量逻辑继续成立。

```java
// VectorStoreMigrationService#distinctSources
SELECT DISTINCT metadata ->> 'source' FROM public.vector_store
    WHERE metadata ->> 'source' IS NOT NULL

// KnowledgeBaseInitializer#syncLoadedSources
loader.primeLoadedSources(vectorStoreService.distinctSources());
```

实测日志：

```
向量库已存在 2 个来源，登记为已加载（增量跳过，避免重复向量化）：
  [knowledge.txt, Git学习笔记_脱敏版.docx]
```

关闭开关 `app.rag.sync-loaded-sources=false` 则恢复"每次启动全量重载"。

## 4. 数据迁移

### 4.1 为什么不用 `vectorStore.add(...)`

Spring AI 2.0 的 `Document` **已不再携带 embedding 字段**，`add()` 会对每个片段重新调用
Embedding 接口。旧 JSON 里已经存好了 1024 维向量，直接写库可以：

- 保留原始向量，避免因模型版本差异导致检索结果漂移；
- 不消耗 Embedding 配额、不依赖外部网络；
- 批量提交，速度快几个数量级。

### 4.2 源数据格式

`data/vector-store.json`（`SimpleVectorStore` 的快照）结构：

```json
{
  "3f1c...-uuid": {
    "id": "3f1c...-uuid",
    "text": "片段原文",
    "metadata": { "source": "knowledge.txt", "type": "txt", "total_chunks": 4 },
    "embedding": [0.0123, -0.0456, "... 共 1024 维 ..."]
  }
}
```

### 4.3 迁移实现

`VectorStoreMigrationService` 用 Jackson 解析快照，再用 `JdbcTemplate` 批量写入，
SQL 与 `PgVectorStore` 自身的 doAdd 保持同构：

```sql
INSERT INTO public.vector_store (id, content, metadata, embedding)
VALUES (?::uuid, ?, ?::jsonb, ?)
ON CONFLICT (id) DO NOTHING
```

- 向量用 `com.pgvector.PGvector` 承载，与 `PgVectorStore` 内部写法完全一致，
  避免手拼 `[0.1,0.2,...]` 字面量在浮点格式上的兼容问题；
- `id` 沿用旧 UUID，保证幂等：重复执行只跳过、不产生重复行；
- `id-type` 非 UUID 时自动去掉 `::uuid` 转型；
- 无效记录（缺 `text` 或 `embedding`）单独计数跳过，不影响整批；
- 每 1000 条一批提交，避免超大 batch 撑爆 PG 参数。

### 4.4 触发方式

**方式一：启动参数（推荐）**

```bash
./gradlew bootRun --args='--app.rag.migrate.enabled=true --app.rag.load-on-startup=false'

# 或打包后
java -jar build/libs/knowledge-demo.jar --app.rag.migrate.enabled=true
```

**方式二：环境变量**

```bash
RAG_MIGRATE_ENABLED=true ./gradlew bootRun
```

**方式三：运行中调用管理接口**

```bash
curl -X POST http://localhost:8080/rag/admin/knowledge/migrate
```

返回：

```json
{
  "status": "SUCCESS",
  "message": "迁移完成：写入 6 条，跳过重复 0 条，跳过无效 0 条",
  "source": "/Users/xxx/data/vector-store.json",
  "table": "public.vector_store",
  "read": 6, "inserted": 6, "skipped": 0, "invalid": 0, "totalInTable": 6
}
```

再次调用（幂等验证）：

```json
{
  "status": "SKIPPED",
  "message": "向量表已有 6 条数据，按 app.rag.migrate.skip-if-not-empty=true 跳过迁移",
  "read": 0, "inserted": 0, "skipped": 6, "invalid": 0, "totalInTable": 6
}
```

### 4.5 迁移后校验

```sql
-- 条数与维度
SELECT count(*), min(vector_dims(embedding)), max(vector_dims(embedding))
FROM public.vector_store;

-- 来源分布
SELECT metadata ->> 'source' AS source, count(*)
FROM public.vector_store GROUP BY 1;

-- 向量是否可用：按余弦距离找最近邻
SELECT id, metadata ->> 'source' AS source,
       (1 - (embedding <=> (SELECT embedding FROM public.vector_store LIMIT 1)))::numeric(6,4) AS cosine_sim
FROM public.vector_store
ORDER BY embedding <=> (SELECT embedding FROM public.vector_store LIMIT 1)
LIMIT 5;

-- 索引是否建立
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'vector_store';
```

应用侧：

```bash
curl http://localhost:8080/rag/admin/knowledge/stats
curl "http://localhost:8080/rag/admin/knowledge/search?q=Git分支合并&topK=3"
```

`stats` 会返回 `vectorStoreType=pgvector`、`vectorStoreClass=PgVectorStore`、
`vectorStoreTable=public.vector_store`、`vectorCount=6`。

## 5. 回滚方案

迁移不改动旧 JSON 文件（`migrate.keep-source-file` 默认 true），回滚只需：

1. `spring.ai.vectorstore.type=simple`；
2. 去掉 PgVector starter（可选，留着也不影响 simple 模式）；
3. 重启应用，`data/vector-store.json` 会被原样加载回内存。

数据本身不受影响，随时可再次迁回 PgVector。

## 6. 上线检查清单

- [ ] PostgreSQL 版本 ≥ 15，`CREATE EXTENSION vector;` 已执行
- [ ] 应用账号对目标库具备建表/读写权限（或已由 DBA 预置表结构）
- [ ] `dimensions` 与实际 Embedding 模型输出维度一致（`text-embedding-v3` → 1024）
- [ ] 生产环境 `initialize-schema` 建议设为 `false`，表由 DBA 管理
- [ ] 生产环境 `schema-validation: true`，让维度不匹配在启动期就暴露
- [ ] 数据源密码走环境变量或密钥管理，不写进仓库
- [ ] `app.rag.auth-token` 已配置，管理接口不裸奔
- [ ] 先执行迁移并校验条数，再把 `migrate.enabled` 从启动参数移除

## 7. 后续可优化方向

| 方向 | 说明 |
| --- | --- |
| 表按环境拆分 | `table-name: vector_store_prod`，或用独立 schema，避免多环境串数据 |
| 元数据过滤索引 | `CREATE INDEX ON vector_store ((metadata ->> 'source'))`，按来源删除/检索更快 |
| 距离度量选择 | 归一化向量用 `COSINE_DISTANCE`；追求极致性能可改用 `NEGATIVE_INNER_PRODUCT` |
| 索引调参 | HNSW 的 `ef_construction` / `m` 可调，权衡召回率与构建耗时 |
| 分区 | 超大表可按来源或时间分区，缩小索引体积 |
| 备份 | 纳入常规 PG 备份策略；向量列体积大，注意备份窗口 |
