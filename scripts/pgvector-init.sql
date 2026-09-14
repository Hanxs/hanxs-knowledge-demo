-- ============================================================================
-- PgVector 初始化脚本（PostgreSQL 15+）
-- 用法：
--   createdb knowledge_db
--   psql -d knowledge_db -f scripts/pgvector-init.sql
--
-- 说明：
--   1. PgVectorStore 在 spring.ai.vectorstore.pgvector.initialize-schema=true 时
--      也会自动执行 CREATE EXTENSION / CREATE TABLE，本脚本用于"数据库侧先行初始化"
--      （生产环境通常由 DBA 预置，应用账号无 superuser 权限）。
--   2. 维度必须与 Embedding 模型一致：text-embedding-v3 -> 1024（不是 1536）。
--   3. 第 5 节的全文检索索引是「混合检索」的前提，只升级已有库时请单独执行该节。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 启用扩展
-- ---------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS vector;      -- 向量类型与 HNSW/IVFFlat 索引
CREATE EXTENSION IF NOT EXISTS hstore;      -- PgVectorStore 元数据过滤辅助
CREATE EXTENSION IF NOT EXISTS "uuid-ossp"; -- uuid_generate_v4()，id-type=UUID 时建表默认值会用到

-- ---------------------------------------------------------------------------
-- 2. 向量表（结构与 PgVectorStore 自动建表保持一致）
--    id        : 主键，UUID
--    content   : 片段原文
--    metadata  : 元数据（source / type / 页码等），jsonb 便于过滤
--    embedding : 文本向量，维度需与 Embedding 模型匹配
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.vector_store (
    id        uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(1024)
);

-- ---------------------------------------------------------------------------
-- 3. HNSW 索引（余弦距离）
--    维度 <= 2000 时可用 HNSW；更高维度请改用 IVFFlat 或降维
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON public.vector_store
    USING hnsw (embedding vector_cosine_ops);

-- 元数据过滤常用键（按需开启）
-- CREATE INDEX IF NOT EXISTS idx_vector_store_source ON public.vector_store ((metadata ->> 'source'));

-- ===========================================================================
-- 5. 全文检索索引（混合检索的「关键词路」依赖）
--
--    ⚠️ 重要认知：PG 没有内置中文分词器。
--    'simple' 配置按空格/标点切分，中文整句会变成一个大词元，例如
--    to_tsvector('simple', '为规范公司考勤管理...') -> '为规范公司考勤管理...'，
--    因此 to_tsvector @@ plainto_tsquery 对中文查询几乎必然返回 0 行。
--    实测：WHERE to_tsvector('simple', content) @@ plainto_tsquery('simple','考勤管理制度')
--          -> 0 行；改用 content ILIKE '%考勤管理制度%' -> 1 行。
--
--    因此本项目采取「双通道关键词召回」：
--      a) GIN(to_tsvector) 负责英文/数字/代码标识符等有空格的内容（Git/commit/Spring...）
--      b) pg_trgm GIN 负责加速 ILIKE 子串匹配，覆盖中文场景
--    若需真正的中文分词改写引 zhparser 或 pg_jieba 扩展，并在
--    app.rag.hybrid.fts-config 指定对应 text search configuration。
-- ===========================================================================
CREATE INDEX IF NOT EXISTS idx_vector_store_content_fts
    ON public.vector_store
    USING gin (to_tsvector('simple', content));

-- ILIKE 子串匹配的索引加速（中文场景的主要依赖）
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_vector_store_content_trgm
    ON public.vector_store
    USING gin (content gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- 6. 校验
-- ---------------------------------------------------------------------------
-- SELECT extname, extversion FROM pg_extension WHERE extname IN ('vector', 'hstore', 'uuid-ossp', 'pg_trgm');
-- SELECT count(*) FROM public.vector_store;
-- SELECT id, metadata ->> 'source' AS source, left(content, 40) FROM public.vector_store LIMIT 5;
-- 全文检索自查（英文应 >0，中文靠 ILIKE 兜底）：
--   SELECT count(*) FROM public.vector_store
--    WHERE to_tsvector('simple', content) @@ plainto_tsquery('simple', 'Updates were rejected');
--   SELECT count(*) FROM public.vector_store WHERE content ILIKE '%考勤管理制度%';
