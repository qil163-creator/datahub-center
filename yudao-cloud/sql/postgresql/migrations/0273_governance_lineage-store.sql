-- Migration: 0273 数据血缘存储：来源、节点、表级边与字段级边
-- Purpose: 新增 SQL 自动血缘的持久化结构。dataforge 按来源对象（开发任务、数据同步、集成任务、可视化 ETL）经
--          governance-api 整体上报表级与字段级血缘：dh_governance_lineage_source 记录每个来源的修订、解析器版本、
--          内容摘要和状态；dh_governance_lineage_node 保存规范化去重后的表/外部数据集节点；两张边表为派生数据，
--          每次接受上报时按来源物理删除后重建。
-- Depends on: 平台库已升级至 0272。0180 已删除旧版同名 dh_governance_lineage_node 及其序列，本迁移重新创建新结构，
--             与旧结构无数据继承关系。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；序列、表、约束和索引均带存在性保护，单事务执行。
-- Rollback: 加法迁移，无不可逆操作。按 dh_governance_lineage_column_edge、dh_governance_lineage_table_edge、
--           dh_governance_lineage_source、dh_governance_lineage_node 的顺序删除表及同名 _seq 序列即可；
--           血缘为派生数据，回滚后可由 dataforge 重新上报重建。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE SEQUENCE IF NOT EXISTS dh_governance_lineage_source_seq;
CREATE SEQUENCE IF NOT EXISTS dh_governance_lineage_node_seq;
CREATE SEQUENCE IF NOT EXISTS dh_governance_lineage_table_edge_seq;
CREATE SEQUENCE IF NOT EXISTS dh_governance_lineage_column_edge_seq;

CREATE TABLE IF NOT EXISTS dh_governance_lineage_source (
    id int8 NOT NULL DEFAULT nextval('dh_governance_lineage_source_seq'::regclass),
    source_type varchar(32) NOT NULL,
    source_id int8 NOT NULL,
    source_name varchar(255) NOT NULL DEFAULT '',
    source_revision int8 NOT NULL DEFAULT -1,
    analyzer_version int4 NOT NULL DEFAULT 0,
    content_hash varchar(64) NOT NULL DEFAULT '',
    status varchar(16) NOT NULL,
    issues jsonb NOT NULL DEFAULT '[]'::jsonb,
    table_edge_count int4 NOT NULL DEFAULT 0,
    column_edge_count int4 NOT NULL DEFAULT 0,
    reported_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_governance_lineage_source PRIMARY KEY (id),
    CONSTRAINT uk_dh_governance_lineage_source UNIQUE (source_type, source_id),
    CONSTRAINT ck_lineage_source_type CHECK (source_type IN ('DEV_TASK', 'DATA_ACCESS', 'INTEGRATION_TASK', 'ETL_PIPELINE')),
    CONSTRAINT ck_lineage_source_status CHECK (status IN ('SUCCESS', 'PARTIAL', 'FAILED', 'UNSUPPORTED', 'REMOVED')),
    CONSTRAINT ck_lineage_source_issues CHECK (jsonb_typeof(issues) = 'array'),
    CONSTRAINT ck_lineage_source_counts CHECK (table_edge_count >= 0 AND column_edge_count >= 0)
);

COMMENT ON TABLE dh_governance_lineage_source IS '数据血缘-上报来源（每个来源对象一行，记录最新接受的修订与解析结果）';
COMMENT ON COLUMN dh_governance_lineage_source.id IS '来源记录编号';
COMMENT ON COLUMN dh_governance_lineage_source.source_type IS '来源类型：DEV_TASK开发任务、DATA_ACCESS数据同步、INTEGRATION_TASK集成任务、ETL_PIPELINE可视化ETL';
COMMENT ON COLUMN dh_governance_lineage_source.source_id IS '来源对象在 dataforge 中的编号';
COMMENT ON COLUMN dh_governance_lineage_source.source_name IS '来源名称快照';
COMMENT ON COLUMN dh_governance_lineage_source.source_revision IS '已接受的来源修订号；-1 表示同一事务内刚创建、尚未写入';
COMMENT ON COLUMN dh_governance_lineage_source.analyzer_version IS '已接受的解析器版本；与修订号按字典序判定新旧';
COMMENT ON COLUMN dh_governance_lineage_source.content_hash IS '已接受血缘内容的 SHA-256（64 位小写十六进制）';
COMMENT ON COLUMN dh_governance_lineage_source.status IS '解析状态：SUCCESS成功、PARTIAL部分、FAILED失败、UNSUPPORTED不支持、REMOVED来源已移除';
COMMENT ON COLUMN dh_governance_lineage_source.issues IS '解析问题 JSON 数组：[{code,message,statementIndex}]，最多 200 条';
COMMENT ON COLUMN dh_governance_lineage_source.table_edge_count IS '当前表级边数量';
COMMENT ON COLUMN dh_governance_lineage_source.column_edge_count IS '当前字段级边数量';
COMMENT ON COLUMN dh_governance_lineage_source.reported_time IS '最近一次接受上报的时间';
COMMENT ON COLUMN dh_governance_lineage_source.creator IS '创建者';
COMMENT ON COLUMN dh_governance_lineage_source.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_lineage_source.updater IS '更新者';
COMMENT ON COLUMN dh_governance_lineage_source.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_lineage_source.deleted IS '逻辑删除：0未删除、1已删除（来源行不做逻辑删除，移除时状态记为 REMOVED）';

CREATE TABLE IF NOT EXISTS dh_governance_lineage_node (
    id int8 NOT NULL DEFAULT nextval('dh_governance_lineage_node_seq'::regclass),
    node_key varchar(512) NOT NULL,
    node_type varchar(16) NOT NULL,
    datasource_id int8,
    schema_name varchar(128),
    table_name varchar(255),
    external_kind varchar(16),
    external_key varchar(512),
    display_name varchar(255),
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_governance_lineage_node PRIMARY KEY (id),
    CONSTRAINT uk_dh_governance_lineage_node_key UNIQUE (node_key),
    CONSTRAINT ck_lineage_node_shape CHECK (
        (node_type = 'TABLE' AND datasource_id IS NOT NULL AND table_name IS NOT NULL
            AND external_kind IS NULL AND external_key IS NULL)
        OR (node_type = 'EXTERNAL' AND external_kind IN ('KAFKA', 'FILE', 'HDFS', 'API', 'MONGODB', 'OTHER')
            AND external_key IS NOT NULL AND datasource_id IS NULL AND schema_name IS NULL AND table_name IS NULL))
);

CREATE INDEX IF NOT EXISTS idx_dh_governance_lineage_node_datasource
    ON dh_governance_lineage_node (datasource_id) WHERE node_type = 'TABLE';

COMMENT ON TABLE dh_governance_lineage_node IS '数据血缘-节点（库表或外部数据集，按规范化键全局去重）';
COMMENT ON COLUMN dh_governance_lineage_node.id IS '节点编号';
COMMENT ON COLUMN dh_governance_lineage_node.node_key IS '规范化节点键：TABLE|数据源|小写schema|小写表名 或 EXTERNAL|种类|标识；超过 512 字符时为 SHA256:摘要';
COMMENT ON COLUMN dh_governance_lineage_node.node_type IS '节点类型：TABLE库表、EXTERNAL外部数据集';
COMMENT ON COLUMN dh_governance_lineage_node.datasource_id IS '数据源编号（TABLE 节点）';
COMMENT ON COLUMN dh_governance_lineage_node.schema_name IS 'schema 名（TABLE 节点，可空；保留首次上报的写法）';
COMMENT ON COLUMN dh_governance_lineage_node.table_name IS '表名（TABLE 节点；保留首次上报的写法）';
COMMENT ON COLUMN dh_governance_lineage_node.external_kind IS '外部数据集种类：KAFKA、FILE、HDFS、API、MONGODB、OTHER（EXTERNAL 节点）';
COMMENT ON COLUMN dh_governance_lineage_node.external_key IS '外部数据集标识（EXTERNAL 节点，不含凭据）';
COMMENT ON COLUMN dh_governance_lineage_node.display_name IS '展示名（可空，仅在为空时由后续上报补齐）';
COMMENT ON COLUMN dh_governance_lineage_node.creator IS '创建者';
COMMENT ON COLUMN dh_governance_lineage_node.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_lineage_node.updater IS '更新者';
COMMENT ON COLUMN dh_governance_lineage_node.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_lineage_node.deleted IS '逻辑删除：0未删除、1已删除（节点不做删除，无边的节点不参与检索）';

CREATE TABLE IF NOT EXISTS dh_governance_lineage_table_edge (
    id int8 NOT NULL DEFAULT nextval('dh_governance_lineage_table_edge_seq'::regclass),
    source_ref_id int8 NOT NULL,
    upstream_node_id int8 NOT NULL,
    downstream_node_id int8 NOT NULL,
    origin varchar(16) NOT NULL,
    statement_index int4,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_governance_lineage_table_edge PRIMARY KEY (id),
    CONSTRAINT uk_dh_governance_lineage_table_edge UNIQUE (source_ref_id, upstream_node_id, downstream_node_id, origin),
    CONSTRAINT fk_lineage_table_edge_source FOREIGN KEY (source_ref_id) REFERENCES dh_governance_lineage_source (id),
    CONSTRAINT fk_lineage_table_edge_upstream FOREIGN KEY (upstream_node_id) REFERENCES dh_governance_lineage_node (id),
    CONSTRAINT fk_lineage_table_edge_downstream FOREIGN KEY (downstream_node_id) REFERENCES dh_governance_lineage_node (id),
    CONSTRAINT ck_lineage_table_edge_origin CHECK (origin IN ('PARSED', 'MANUAL', 'RUNTIME')),
    CONSTRAINT ck_lineage_table_edge_statement CHECK (statement_index IS NULL OR statement_index >= 0)
);

CREATE INDEX IF NOT EXISTS idx_dh_governance_lineage_table_edge_upstream
    ON dh_governance_lineage_table_edge (upstream_node_id, downstream_node_id);
CREATE INDEX IF NOT EXISTS idx_dh_governance_lineage_table_edge_downstream
    ON dh_governance_lineage_table_edge (downstream_node_id, upstream_node_id);

COMMENT ON TABLE dh_governance_lineage_table_edge IS '数据血缘-表级边（派生数据：按来源整体替换，物理删除）';
COMMENT ON COLUMN dh_governance_lineage_table_edge.id IS '边编号';
COMMENT ON COLUMN dh_governance_lineage_table_edge.source_ref_id IS '所属上报来源编号（dh_governance_lineage_source.id）';
COMMENT ON COLUMN dh_governance_lineage_table_edge.upstream_node_id IS '上游节点编号';
COMMENT ON COLUMN dh_governance_lineage_table_edge.downstream_node_id IS '下游节点编号';
COMMENT ON COLUMN dh_governance_lineage_table_edge.origin IS '边来源：PARSED解析、MANUAL人工、RUNTIME运行时';
COMMENT ON COLUMN dh_governance_lineage_table_edge.statement_index IS '所在语句序号（从 0 开始，同一来源内重复边取最小值）';
COMMENT ON COLUMN dh_governance_lineage_table_edge.creator IS '创建者';
COMMENT ON COLUMN dh_governance_lineage_table_edge.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_lineage_table_edge.updater IS '更新者';
COMMENT ON COLUMN dh_governance_lineage_table_edge.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_lineage_table_edge.deleted IS '逻辑删除：固定为 0，边只做物理删除';

CREATE TABLE IF NOT EXISTS dh_governance_lineage_column_edge (
    id int8 NOT NULL DEFAULT nextval('dh_governance_lineage_column_edge_seq'::regclass),
    source_ref_id int8 NOT NULL,
    upstream_node_id int8 NOT NULL,
    upstream_column varchar(255) NOT NULL,
    downstream_node_id int8 NOT NULL,
    downstream_column varchar(255) NOT NULL,
    transform_type varchar(16) NOT NULL,
    transform_subtype varchar(16) NOT NULL,
    ambiguous bool NOT NULL DEFAULT false,
    masking bool NOT NULL DEFAULT false,
    statement_index int4,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted int2 NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_governance_lineage_column_edge PRIMARY KEY (id),
    CONSTRAINT fk_lineage_column_edge_source FOREIGN KEY (source_ref_id) REFERENCES dh_governance_lineage_source (id),
    CONSTRAINT fk_lineage_column_edge_upstream FOREIGN KEY (upstream_node_id) REFERENCES dh_governance_lineage_node (id),
    CONSTRAINT fk_lineage_column_edge_downstream FOREIGN KEY (downstream_node_id) REFERENCES dh_governance_lineage_node (id),
    CONSTRAINT ck_lineage_column_edge_transform CHECK (
        (transform_type = 'DIRECT' AND transform_subtype IN ('IDENTITY', 'TRANSFORMATION', 'AGGREGATION'))
        OR (transform_type = 'INDIRECT'
            AND transform_subtype IN ('JOIN', 'FILTER', 'GROUP_BY', 'SORT', 'WINDOW', 'CONDITIONAL'))),
    CONSTRAINT ck_lineage_column_edge_statement CHECK (statement_index IS NULL OR statement_index >= 0)
);

CREATE INDEX IF NOT EXISTS idx_dh_governance_lineage_column_edge_upstream
    ON dh_governance_lineage_column_edge (upstream_node_id, upstream_column);
CREATE INDEX IF NOT EXISTS idx_dh_governance_lineage_column_edge_downstream
    ON dh_governance_lineage_column_edge (downstream_node_id, downstream_column);
CREATE INDEX IF NOT EXISTS idx_dh_governance_lineage_column_edge_source
    ON dh_governance_lineage_column_edge (source_ref_id);

COMMENT ON TABLE dh_governance_lineage_column_edge IS '数据血缘-字段级边（派生数据：按来源整体替换，物理删除）';
COMMENT ON COLUMN dh_governance_lineage_column_edge.id IS '边编号';
COMMENT ON COLUMN dh_governance_lineage_column_edge.source_ref_id IS '所属上报来源编号（dh_governance_lineage_source.id）';
COMMENT ON COLUMN dh_governance_lineage_column_edge.upstream_node_id IS '上游节点编号';
COMMENT ON COLUMN dh_governance_lineage_column_edge.upstream_column IS '上游字段名（小写规范化，* 表示整行）';
COMMENT ON COLUMN dh_governance_lineage_column_edge.downstream_node_id IS '下游节点编号';
COMMENT ON COLUMN dh_governance_lineage_column_edge.downstream_column IS '下游字段名（小写规范化）';
COMMENT ON COLUMN dh_governance_lineage_column_edge.transform_type IS '变换大类：DIRECT直接、INDIRECT间接';
COMMENT ON COLUMN dh_governance_lineage_column_edge.transform_subtype IS '变换子类：DIRECT 含 IDENTITY/TRANSFORMATION/AGGREGATION，INDIRECT 含 JOIN/FILTER/GROUP_BY/SORT/WINDOW/CONDITIONAL';
COMMENT ON COLUMN dh_governance_lineage_column_edge.ambiguous IS '是否存在歧义（无法唯一确定字段来源）';
COMMENT ON COLUMN dh_governance_lineage_column_edge.masking IS '是否经过脱敏/哈希等遮蔽处理';
COMMENT ON COLUMN dh_governance_lineage_column_edge.statement_index IS '所在语句序号（从 0 开始，同一来源内重复边取最小值）';
COMMENT ON COLUMN dh_governance_lineage_column_edge.creator IS '创建者';
COMMENT ON COLUMN dh_governance_lineage_column_edge.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_lineage_column_edge.updater IS '更新者';
COMMENT ON COLUMN dh_governance_lineage_column_edge.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_lineage_column_edge.deleted IS '逻辑删除：固定为 0，边只做物理删除';

COMMIT;
