"""在隔离回环 PostgreSQL 比较 0187 新库与升级重放结果，并验证真实索引和约束。"""
import os
import unittest
import uuid

import psycopg2


class MetadataMigrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        options = dict(host="127.0.0.1", port=int(os.environ["METADATA_MIGRATION_PG_PORT"]),
                       user=os.environ["METADATA_WRITE_TEST_JDBC_USER"],
                       password=os.environ.get("METADATA_WRITE_TEST_JDBC_PASSWORD", ""))
        cls.fresh = psycopg2.connect(dbname=os.environ["METADATA_MIGRATION_FRESH_DB"], **options)
        cls.upgraded = psycopg2.connect(dbname=os.environ["METADATA_MIGRATION_UPGRADE_DB"], **options)
        cls.fresh.autocommit = cls.upgraded.autocommit = True

    @classmethod
    def tearDownClass(cls):
        cls.fresh.close()
        cls.upgraded.close()

    def query(self, connection, query):
        with connection.cursor() as cursor:
            cursor.execute(query)
            return cursor.fetchall()

    def test_fresh_and_replayed_upgrade_have_identical_metadata_structure(self):
        queries = [
            "SELECT table_name,column_name,data_type,character_maximum_length,is_nullable,column_default "
            "FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name LIKE 'dh_governance_meta_%' "
            "ORDER BY table_name,ordinal_position",
            "SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='datahub-center' "
            "AND tablename LIKE 'dh_governance_meta_%' ORDER BY tablename,indexname",
            "SELECT c.relname,x.conname,pg_get_constraintdef(x.oid) FROM pg_constraint x "
            "JOIN pg_class c ON c.oid=x.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace "
            "WHERE n.nspname='datahub-center' AND c.relname LIKE 'dh_governance_meta_%' ORDER BY c.relname,x.conname",
        ]
        for query in queries:
            self.assertEqual(self.query(self.fresh, query), self.query(self.upgraded, query))

    def test_permissions_are_unique_and_have_stable_parent_after_replay(self):
        query = "SELECT permission,parent_id,type FROM \"datahub-center\".system_menu WHERE deleted=0 " \
                "AND permission IN ('governance:meta-file:query','governance:meta-file:update'," \
                "'governance:meta-file:delete','governance:meta-collect-instance:delete') ORDER BY permission"
        rows = self.query(self.fresh, query)
        self.assertEqual(4, len(rows))
        self.assertEqual(4, len(set(row[0] for row in rows)))
        self.assertTrue(all(row[2] == 3 for row in rows))
        self.assertEqual(rows, self.query(self.upgraded, query))

    def test_no_business_tenant_or_task_owned_lease_columns_survive(self):
        rows = self.query(self.upgraded, "SELECT column_name FROM information_schema.columns "
                          "WHERE table_schema='datahub-center' AND table_name LIKE 'dh_governance_meta_%' "
                          "AND (column_name='tenant_id' OR (table_name='dh_governance_meta_collect_task' "
                          "AND column_name IN ('execution_token','lease_until','running_instance_id')))")
        self.assertEqual([], rows)

    def test_long_unicode_paths_use_hash_index_and_enforce_real_constraints(self):
        schema = "metadata_migration_verify_" + uuid.uuid4().hex
        with self.upgraded.cursor() as cursor:
            cursor.execute(f'CREATE SCHEMA "{schema}"')
            try:
                cursor.execute(f'CREATE TABLE "{schema}".files '
                               '(LIKE "datahub-center".dh_governance_meta_file INCLUDING ALL)')
                path = "/" + "中" * 4095
                sql = f'INSERT INTO "{schema}".files(datasource_id,path,parent_path,name,size) VALUES(%s,%s,%s,%s,%s)'
                cursor.execute(sql, (1, path, "/", "长路径.txt", 1))
                with self.assertRaises(psycopg2.errors.UniqueViolation):
                    cursor.execute(sql, (1, path, "/", "相同路径.txt", 1))
                cursor.execute(sql, (2, path, "/", "其他连接.txt", 1))
                with self.assertRaises(psycopg2.errors.CheckViolation):
                    cursor.execute(sql, (3, "/bad", "/", "错误大小", -1))
            finally:
                cursor.execute(f'DROP SCHEMA "{schema}" CASCADE')


if __name__ == "__main__":
    unittest.main(verbosity=2)
