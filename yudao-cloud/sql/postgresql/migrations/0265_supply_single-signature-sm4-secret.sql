-- Migration: 0265 数据服务签名统一为单一 HMAC-SM3 协议，应用密钥落库改为可配置的 SM4/AES-GCM 密文。
-- Purpose: 删除应用级签名协议选择列 sign_protocol（平台只保留一种签名协议）；把 secret 列放宽到 256，
--          容纳 "SM4:"/"AES:" 前缀的 GCM 密文（原 varchar(64) 连 40 位密钥的 AES 密文都放不下）。
-- Depends on: 平台库已升级至 0264；dh_supply_app 存在 secret 列。
-- Target: 已有 PostgreSQL 平台库；新库使用同步后的 datahub-center.sql。
-- Repeatable: 是；列类型修改幂等，删除列带 IF EXISTS。
-- Rollback: 事务失败自动回滚。提交后 sign_protocol 列不可恢复（仅控制已退役的 v1/v2 协商，无需恢复）；
--           secret 放宽为加法变更，回滚应用版本时可保留。
-- 说明：平台尚未正式使用，不迁移历史应用密钥。新版本只解密带已注册算法前缀的密文，历史明文或旧 ENC: 值
--       将无法通过签名校验，如存在测试数据请在管理端“重置密钥”。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_supply_app ALTER COLUMN secret TYPE varchar(256);

ALTER TABLE dh_supply_app DROP COLUMN IF EXISTS sign_protocol;

COMMENT ON COLUMN dh_supply_app.secret IS '应用秘钥密文：<算法>:<载荷>，算法为 SM4 或 AES（GCM）';

COMMIT;
