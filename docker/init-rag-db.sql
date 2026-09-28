-- 只在容器首次初始化（数据卷为空）时执行一次。
-- pgvector 镜像虽然自带 vector 扩展，但每个数据库仍需显式启用。
CREATE EXTENSION IF NOT EXISTS vector;

-- 自检：能看到 vector 版本说明扩展装好了
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
