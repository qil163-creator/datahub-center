-- Run after forward 0015 + 0016 twice against the isolated fixture.
DO $$ BEGIN
  IF current_user <> 'modeling_verify' THEN RAISE EXCEPTION 'Use isolated test cluster'; END IF;
END $$;
SET search_path TO "datahub-center",public;
DO $test$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='datahub-center'
      AND table_name='dh_modeling_logic_model' AND column_name='theme_id') THEN
    RAISE EXCEPTION 'obsolete theme_id remains';
  END IF;
  IF (SELECT task_status FROM dh_modeling_model_check WHERE id=1) <> 'FAILED' THEN
    RAISE EXCEPTION 'unleased old RUNNING job not recovered';
  END IF;
END $test$;
INSERT INTO dh_modeling_data_domain(id,tenant_id) VALUES (1,1),(2,2),(3,1);
INSERT INTO dh_modeling_dw_layer(id,tenant_id) VALUES (1,1);
INSERT INTO dh_modeling_business_process(id,tenant_id,biz_category_id,data_domain_id,code,name)
  VALUES(1,1,1,1,'ORDER','订单'),(2,1,1,3,'ORDER','订单');
INSERT INTO dh_modeling_physical_model(id,tenant_id,layer_id,data_domain_id,business_process_id)
  VALUES (1,1,1,1,1);
INSERT INTO dh_governance_standard_naming_policy(id,scope_type) VALUES(1,'DATA_DOMAIN'),(2,'THEME');
INSERT INTO dh_modeling_model_check(id,tenant_id,task_status) VALUES(2,1,'QUEUED'),(3,2,'QUEUED');
DO $test$ BEGIN
  BEGIN
    INSERT INTO dh_modeling_logic_model(id,tenant_id,data_domain_id) VALUES(10,1,2);
    RAISE EXCEPTION 'cross-tenant reference accepted';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO dh_modeling_logic_model(id,tenant_id,data_domain_id,business_process_id) VALUES(11,1,3,1);
    RAISE EXCEPTION 'mismatched process domain accepted';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    UPDATE dh_modeling_data_domain SET deleted=1 WHERE id=1;
    RAISE EXCEPTION 'referenced data domain deleted';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    DELETE FROM dh_modeling_business_process WHERE id=1;
    RAISE EXCEPTION 'referenced process deleted';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    UPDATE dh_modeling_business_process SET data_domain_id=3 WHERE id=1;
    RAISE EXCEPTION 'bound process moved to another domain';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO dh_modeling_business_process(id,tenant_id,biz_category_id,data_domain_id,code,name)
      VALUES(3,1,99,1,' order ','另一个名称');
    RAISE EXCEPTION 'same-domain code collision accepted';
  EXCEPTION WHEN unique_violation THEN NULL; END;
  BEGIN
    INSERT INTO dh_modeling_model_check(id,tenant_id,task_status) VALUES(4,1,'RUNNING');
    RAISE EXCEPTION 'two active jobs in same tenant accepted';
  EXCEPTION WHEN unique_violation THEN NULL; END;
END $test$;
UPDATE dh_modeling_model_check SET task_status='FAILED' WHERE id=2;
INSERT INTO dh_modeling_model_check(id,tenant_id,task_status) VALUES(4,1,'QUEUED');
-- Drafts can omit ownership and unresolved legacy processes can be explicitly confirmed.
INSERT INTO dh_modeling_logic_model(id,tenant_id) VALUES(20,1);
INSERT INTO dh_modeling_business_process(id,tenant_id,code,name) VALUES(4,1,'LEGACY','待确认');
INSERT INTO dh_modeling_logic_model(id,tenant_id,business_process_id) VALUES(21,1,4);
UPDATE dh_modeling_business_process SET data_domain_id=3 WHERE id=4;
INSERT INTO dh_modeling_subject_domain(id,tenant_id) VALUES(1,1);
INSERT INTO dh_modeling_subject_domain_process_rel(id,tenant_id,subject_domain_id,business_process_id)
  VALUES(1,1,1,1);
UPDATE dh_modeling_physical_model SET subject_domain_id=1 WHERE id=1;
DO $relations$ BEGIN
  BEGIN
    DELETE FROM dh_modeling_subject_domain_process_rel WHERE id=1;
    RAISE EXCEPTION 'model-bound subject-process link removed';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO dh_modeling_business_process_supporting_domain_rel(id,tenant_id,business_process_id,data_domain_id)
      VALUES(1,1,1,1);
    RAISE EXCEPTION 'primary/supporting overlap accepted';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO dh_modeling_business_process_supporting_domain_rel(id,tenant_id,business_process_id,data_domain_id)
      VALUES(2,1,1,2);
    RAISE EXCEPTION 'cross-tenant supporting domain accepted';
  EXCEPTION WHEN check_violation THEN NULL; END;
END $relations$;
SELECT '0016 ownership / queue / naming / relationship assertions passed' AS result;
