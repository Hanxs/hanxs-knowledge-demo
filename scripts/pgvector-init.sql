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

-- ---------------------------------------------------------------------------
-- 4. 校验
-- ---------------------------------------------------------------------------
-- SELECT extname, extversion FROM pg_extension WHERE extname IN ('vector', 'hstore', 'uuid-ossp');
-- SELECT count(*) FROM public.vector_store;
-- SELECT id, metadata ->> 'source' AS source, left(content, 40) FROM public.vector_store LIMIT 5;
