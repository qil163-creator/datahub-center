-- 仅测试：在 SharingPostgresSupport 每例新建的隔离数据库中补全实际 Mapper 使用的申请结构。
ALTER TABLE dh_market_resource_apply
 ADD apply_no text, ADD title text, ADD asset_id bigint, ADD asset_type integer,
 ADD api_id bigint, ADD app_id bigint, ADD demand_id bigint, ADD apply_dept_id bigint,
 ADD apply_user_id bigint, ADD phone text, ADD email text, ADD purpose text,
 ADD field_scope text, ADD apply_note text, ADD apply_start_time timestamp, ADD apply_end_time timestamp,
 ADD apply_use_type integer, ADD apply_use_times integer, ADD status integer, ADD if_auth integer,
 ADD check_user_id bigint, ADD check_time timestamp, ADD check_note text, ADD snapshot text,
 ADD catalog_version_id bigint, ADD duty_matter text, ADD legal_basis text, ADD supplement_note text,
 ADD received_time timestamp, ADD reply_deadline timestamp, ADD delivery_deadline timestamp,
 ADD delivery_status integer, ADD source_grant_id text, ADD first_used_time timestamp, ADD paused integer,
 ADD bpm_instance_id text, ADD remark text, ADD renewal_of_apply_id bigint, ADD renewal_operation_id text,
 ADD renewal_requested_end_time timestamp(6), ADD source_revision bigint, ADD creator text, ADD updater text,
 ADD create_time timestamp DEFAULT CURRENT_TIMESTAMP, ADD update_time timestamp DEFAULT CURRENT_TIMESTAMP;
CREATE SEQUENCE dh_market_apply_no_seq;
CREATE SEQUENCE dh_market_resource_apply_seq;
CREATE SEQUENCE dh_market_check_process_seq;
CREATE TABLE dh_market_check_process (
 id bigint PRIMARY KEY, resource_apply_id bigint, action text, from_status integer, to_status integer,
 operator_user_id bigint, note text, creator text, updater text,
 create_time timestamp DEFAULT CURRENT_TIMESTAMP, update_time timestamp DEFAULT CURRENT_TIMESTAMP,
 deleted smallint NOT NULL DEFAULT 0
);
