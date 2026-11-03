"""在本机 PostgreSQL 独立库验证 0132；源模板只读，保留测试库供检查。"""
from __future__ import annotations

import argparse
import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--source-db", required=True, help="完成 0131 的专用本机测试模板")
    parser.add_argument("--pg-bin", required=True, type=Path)
    parser.add_argument("--user", default="postgres")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,62}", args.source_db):
        parser.error("模板名称必须是普通 PostgreSQL 标识符")
    if not 1 <= args.port <= 65535:
        parser.error("端口超出范围")
    repo = next(p for p in Path(__file__).resolve().parents
                if (p / "pom.xml").is_file() and (p / "sql").is_dir())
    migration = repo / "sql/postgresql/migrations/0132_dataforge_database-password-ciphertext.sql"
    baseline = repo / "sql/postgresql/datahub-center.sql"
    hashes = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in (migration, baseline)}
    prefix = "dataforge_cipher_" + uuid.uuid4().hex[:10]
    databases: list[str] = []
    flags = ["-h", "127.0.0.1", "-p", str(args.port), "-U", args.user]
    env = dict(os.environ, PGCLIENTENCODING="UTF8", PGCONNECT_TIMEOUT="10")

    def binary(name: str) -> str:
        return str(args.pg_bin / (name + (".exe" if os.name == "nt" else "")))

    def run(command: list[str], expected: str | None = None) -> str:
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                                errors="replace", env=env)
        if expected is None and result.returncode:
            raise RuntimeError(result.stderr)
        if expected is not None and (result.returncode == 0 or expected not in result.stderr):
            raise RuntimeError("未按预期拒绝：" + result.stderr)
        return result.stdout.strip()

    def sql(db: str, statement: str = "", file: Path | None = None,
            expected: str | None = None) -> str:
        if db not in databases:
            raise RuntimeError("禁止访问本次测试库以外的数据库")
        prelude = '\\set VERBOSITY verbose\nSET search_path="datahub-center",pg_catalog;\n'
        with tempfile.TemporaryDirectory(prefix="dataforge-cipher-sql-") as temp:
            source = Path(temp) / "statement.sql"
            source.write_text(prelude + statement, encoding="utf-8")
            command = [binary("psql"), *flags, "-d", db, "-X", "-Atq", "-v", "ON_ERROR_STOP=1",
                       "-f", str(source)]
            if file is not None:
                command += ["-f", str(file)]
            return run(command, expected)

    def create(suffix: str, template: str) -> str:
        if template not in (args.source_db, "template0"):
            raise RuntimeError("未知只读模板")
        db = prefix + "_" + suffix
        run([binary("createdb"), *flags, "-T", template, db])
        databases.append(db)
        print("DATABASE " + db, flush=True)
        return db

    def digest(db: str) -> str:
        return sql(db, "SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'[]')) "
                       "FROM dh_dataforge_database t;")

    def column_type(db: str) -> str:
        return sql(db, "SELECT data_type || ':' || COALESCE(character_maximum_length::text,'none') "
                       "FROM information_schema.columns WHERE table_schema='datahub-center' "
                       "AND table_name='dh_dataforge_database' AND column_name='password';")

    # 不是真实账号或密码；仅生成不输出的随机 Base64 载荷，验证不透明密文的完整存取。
    ciphertexts = [base64.b64encode(os.urandom(n)).decode("ascii") for n in (48, 1536)]

    def insert_statement(value: str, fixture_id: int = 993200000001) -> str:
        return ("INSERT INTO dh_dataforge_database(id,name,database_type,database_ip,"
                "database_port,database_name,password,secret_key) VALUES "
                f"({fixture_id},'ciphertext-storage-fixture',1,'127.0.0.1','5432',"
                f"'fixture_only',{value},'fixture-placeholder');")

    def roundtrip(db: str) -> None:
        before = digest(db)
        for index, value in enumerate(ciphertexts):
            fixture_id = 993200000100 + index
            result = sql(db, "BEGIN;" + insert_statement("'" + value + "'", fixture_id)
                         + f"SELECT length(password)={len(value)} AND password='{value}' "
                           f"FROM dh_dataforge_database WHERE id={fixture_id};"
                         + f"UPDATE dh_dataforge_database SET password='{value}{value}' WHERE id={fixture_id};"
                         + f"SELECT length(password)={len(value) * 2} AND password='{value}{value}' "
                           f"FROM dh_dataforge_database WHERE id={fixture_id};ROLLBACK;")
            assert result.splitlines() == ["t", "t"], "密文新增或更新发生变化"
        assert digest(db) == before, "事务回滚没有保持原记录"

    upgraded = create("upgrade", args.source_db)
    assert column_type(upgraded) == "character varying:50"
    sql(upgraded, "BEGIN;" + insert_statement("'" + ciphertexts[0] + "'") + "ROLLBACK;",
        expected="22001")
    print("PASS 原 varchar(50) 拒绝 64 字符密文（SQLSTATE 22001）", flush=True)
    # 已有 NULL、空串和短密文必须原样保留；连同全部原行做摘要对比。
    sql(upgraded, "BEGIN;" + insert_statement("NULL") + insert_statement("''", 993200000002)
        + insert_statement("'Zml4dHVyZQ=='", 993200000003) + "COMMIT;")
    before = digest(upgraded)
    sql(upgraded, file=migration)
    assert column_type(upgraded) == "text:none"
    assert digest(upgraded) == before
    print("PASS 升级扩容为 text，所有原始行含 NULL/空串/短密文保持不变", flush=True)
    roundtrip(upgraded)
    print("PASS 升级后 64/2048 字符新增及 128/4096 字符更新完整往返", flush=True)
    sql(upgraded, file=migration)
    assert column_type(upgraded) == "text:none" and digest(upgraded) == before
    print("PASS 0132 重放保真", flush=True)

    fresh = create("fresh", "template0")
    sql(fresh, file=baseline)
    assert column_type(fresh) == "text:none"
    print("PASS 新库完整基线导入且密码密文列为 text", flush=True)
    before = digest(fresh)
    sql(fresh, file=migration)
    assert column_type(fresh) == "text:none" and digest(fresh) == before
    roundtrip(fresh)
    print("PASS 新库重放及长密文完整往返", flush=True)
    assert all(hashlib.sha256(p.read_bytes()).hexdigest() == expected for p, expected in hashes.items())
    print("RESULT 6 checks passed; source template read-only; SQL files unchanged", flush=True)


if __name__ == "__main__":
    main()
