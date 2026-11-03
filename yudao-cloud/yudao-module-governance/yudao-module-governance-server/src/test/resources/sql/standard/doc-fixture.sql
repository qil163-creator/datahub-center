INSERT INTO dh_governance_standard_doc (
        id, std_code, name_cn, std_type, implement_status, registry_time, status, sort, deleted)
VALUES (10, 'STD-10', '现行标准', 'LOCAL', 'CURRENT', CURRENT_TIMESTAMP, 0, 0, 0),
       (11, 'STD-11', '已删除标准', 'LOCAL', 'CURRENT', CURRENT_TIMESTAMP, 0, 0, 1);

INSERT INTO dh_governance_standard_element (
        id, code, name, document_id, sort, status, create_time, update_time, deleted)
VALUES (100, 'ACTIVE-A', '有效引用一', 10, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
       (101, 'DELETED', '已删除引用', 10, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1),
       (102, 'ACTIVE-B', '有效引用二', 10, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
