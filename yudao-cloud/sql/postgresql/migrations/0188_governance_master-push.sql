-- Migration: 0188 主数据维护和 REST 推送。
-- Purpose: 增加推送凭据密文、按记录增量检查点及执行统计，补齐主数据推送菜单名称。
-- Depends on: 0187_governance_metadata-management.sql；主数据派发持久运行协议和单租户迁移已完成。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；列和表按存在性补齐，索引和菜单更新具有幂等性。
-- Rollback: 失败时事务回滚；提交后回滚应用前须停用推送工作进程，导出密文及检查点备份。删除检查点会使下一次增量重新发送，不能直接删表回退。
-- 风险：执行前停止主数据派发工作进程；DDL 获取短暂表锁。迁移不接触业务源表，不转换旧配置中的明文凭据。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_governance_master_distribute ADD COLUMN IF NOT EXISTS request_secrets text;
ALTER TABLE dh_governance_master_distribute ALTER COLUMN description TYPE varchar(256);
COMMENT ON COLUMN dh_governance_master_distribute.request_secrets IS '请求凭据密文，由平台加密类型处理器读写，不向管理端返回明文';
COMMENT ON COLUMN dh_governance_master_distribute.description IS '主数据推送说明，最多256个字符';

ALTER TABLE dh_governance_master_distribute_log
    ADD COLUMN IF NOT EXISTS total_count bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS success_count bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS failure_count bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS unknown_count bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS push_scope varchar(16),
    ADD COLUMN IF NOT EXISTS push_mode varchar(16);
COMMENT ON COLUMN dh_governance_master_distribute_log.total_count IS '本次选入推送批次的记录总数';
COMMENT ON COLUMN dh_governance_master_distribute_log.success_count IS '已收到成功响应的记录数';
COMMENT ON COLUMN dh_governance_master_distribute_log.failure_count IS '已确认失败的记录数，不包含结果未知记录';
COMMENT ON COLUMN dh_governance_master_distribute_log.unknown_count IS '已发送但无法确认结果的记录数';
COMMENT ON COLUMN dh_governance_master_distribute_log.push_scope IS '执行时推送范围快照';
COMMENT ON COLUMN dh_governance_master_distribute_log.push_mode IS '执行时推送模式快照';

CREATE TABLE IF NOT EXISTS dh_governance_master_distribute_checkpoint (
    distribute_id bigint NOT NULL,
    row_key_hash varchar(64) NOT NULL,
    payload_hash varchar(64) NOT NULL,
    run_id bigint NOT NULL,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT pk_master_distribute_checkpoint PRIMARY KEY (distribute_id, row_key_hash),
    CONSTRAINT fk_master_distribute_checkpoint_task FOREIGN KEY (distribute_id)
        REFERENCES dh_governance_master_distribute(id),
    CONSTRAINT fk_master_distribute_checkpoint_run FOREIGN KEY (run_id)
        REFERENCES dh_governance_master_distribute_log(id),
    CONSTRAINT ck_master_distribute_checkpoint_hash CHECK (
        row_key_hash ~ '^[0-9a-f]{64}$' AND payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_master_distribute_checkpoint_deleted CHECK (deleted IN (0, 1))
);
COMMENT ON TABLE dh_governance_master_distribute_checkpoint IS '主数据推送已确认记录检查点，只保存摘要和执行引用，不保存源业务数据';
COMMENT ON COLUMN dh_governance_master_distribute_checkpoint.distribute_id IS '主数据推送任务编号';
COMMENT ON COLUMN dh_governance_master_distribute_checkpoint.row_key_hash IS '模型主键规范化后的摘要';
COMMENT ON COLUMN dh_governance_master_distribute_checkpoint.payload_hash IS '成功发送的映射后内容摘要';
COMMENT ON COLUMN dh_governance_master_distribute_checkpoint.run_id IS '最近确认该记录的执行日志编号';
COMMENT ON COLUMN dh_governance_master_distribute_checkpoint.creator IS '首次确认操作人';
COMMENT ON COLUMN dh_governance_master_distribute_checkpoint.create_time IS '首次确认时间';
COMMENT ON COLUMN dh_governance_master_distribute_checkpoint.updater IS '最近确认操作人';
COMMENT ON COLUMN dh_governance_master_distribute_checkpoint.update_time IS '最近确认时间';
COMMENT ON COLUMN dh_governance_master_distribute_checkpoint.deleted IS '逻辑删除标记';
CREATE INDEX IF NOT EXISTS idx_master_distribute_checkpoint_run
    ON dh_governance_master_distribute_checkpoint(run_id);

UPDATE system_menu SET name='主数据推送', updater='migration:0188', update_time=CURRENT_TIMESTAMP
WHERE type=2 AND deleted=0 AND trim(both '/' from coalesce(component,''))='governance/masterdistribute/index'
    AND name IS DISTINCT FROM '主数据推送';
COMMIT;
