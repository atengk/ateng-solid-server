-- ============================================================================
-- V1.0.2__add_role_name_unique_index.sql
-- 为 sys_role 补齐 (tenant_id, role_name, deleted) 唯一索引，消除并发场景角色重名竞态
-- ============================================================================

CREATE UNIQUE INDEX uk_role_tenant_name_deleted ON sys_role (tenant_id, role_name, deleted);
