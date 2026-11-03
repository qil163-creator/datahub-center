"""在独立临时 PostgreSQL 集群验证政务标准数据元类目重置；不连接业务库。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
BASELINE_PATH = "sql/postgresql/datahub-center.sql"
MIGRATION = ROOT / "sql/postgresql/migrations/0195_governance_government-datameta-categories.sql"
CATEGORY = "dh_governance_category_datameta"
SEQUENCE = "dh_foundation_category_datameta_seq"
REFERENCES = (
    "dh_governance_standard_element",
    "dh_governance_standard_code_set",
    "dh_governance_standard_term",
)
PRESERVED = (
    "dh_governance_standard_element_version",
    "dh_governance_standard_element_approval",
    "dh_governance_standard_element_rule",
    "dh_governance_standard_code_item",
    "dh_governance_standard_code_value_mapping",
    "dh_governance_standard_mapping",
    "dh_governance_standard_doc",
    "system_menu",
    "system_role_menu",
)
TREE = {
    "人员类": ("身份标识", "人口特征", "教育与专业", "从业与任职", "联系信息"),
    "机构类": ("机构标识", "机构名称", "机构类型", "行业与经营", "设立与注销"),
    "位置类": ("行政区划", "地理位置", "地址信息"),
    "时间类": ("日期", "时刻", "日期时间"),
    "公文类": ("公文标识", "标题与主题", "行文主体", "密级与紧急程度", "印发与流转"),
    "金融类": ("币种与货币代码", "金额与资金", "银行账户", "汇率"),
    "其他类": ("物品与商品", "计量单位", "通用描述"),
}
PREFIX = 'SET search_path="datahub-center",pg_catalog;\n'


def previous_baseline() -> str:
    """选择尚未包含本迁移的最近完整基线，提交后仍可复现升级。"""
    revisions = subprocess.run(["git", "rev-list", "HEAD", "--", BASELINE_PATH], cwd=ROOT,
                               check=True, capture_output=True, text=True).stdout.splitlines()
    for revision in revisions:
        result = subprocess.run(["git", "show", f"{revision}:{BASELINE_PATH}"], cwd=ROOT,
                                check=True, capture_output=True)
        content = result.stdout.decode("utf-8-sig")
        if "-- Included migrations through: 0194" in content:
            return content
    raise AssertionError("Git 历史中找不到包含 0194 且尚未包含 0195 的完整基线")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--postgres-bin", type=Path, help="含 initdb、pg_ctl、psql 的 PostgreSQL bin 目录")
    args = parser.parse_args()
    suffix = ".exe" if os.name == "nt" else ""
    binary = args.postgres_bin or Path(shutil.which("initdb") or "").parent
    for name in ("initdb", "pg_ctl", "psql"):
        if not (binary / (name + suffix)).is_file():
            raise SystemExit(f"未找到 PostgreSQL {name}；请指定 --postgres-bin")
    environment = {**os.environ, "PGCLIENTENCODING": "UTF8"}
    # 清除外部连接配置；实例地址、账号及数据库始终由本脚本控制。
    for key in ("PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGSERVICE", "PGSERVICEFILE", "PGOPTIONS"):
        environment.pop(key, None)
    creation_flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    old_baseline = previous_baseline()
    baseline = (ROOT / BASELINE_PATH).read_text(encoding="utf-8-sig")
    migration = MIGRATION.read_text(encoding="utf-8-sig")

    with tempfile.TemporaryDirectory(prefix="datahub-government-categories-verify-") as work:
        directory = Path(work)
        assert directory.resolve().parent == Path(tempfile.gettempdir()).resolve()

        def run(name: str, *arguments, input_text: str | None = None, required: bool = True):
            command = [str(binary / (name + suffix)), *map(str, arguments)]
            if name == "pg_ctl":
                # Windows 后台进程使用文件接收输出，避免继承管道阻止父进程结束。
                output = directory / "pg_ctl.log"
                with output.open("w", encoding="utf-8") as log:
                    result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=environment,
                                            creationflags=creation_flags, timeout=60)
                result.stdout = output.read_text(encoding="utf-8", errors="replace")
                result.stderr = ""
            else:
                result = subprocess.run(command, input=input_text, text=True, encoding="utf-8", errors="replace",
                                        capture_output=True, env=environment, creationflags=creation_flags, timeout=180)
            if required and result.returncode:
                raise AssertionError(f"{name} 失败：\n{result.stdout[-1500:]}\n{result.stderr[-4000:]}")
            return result

        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        data = directory / "data"
        run("initdb", "-D", data, "-U", "government_categories_test", "-A", "trust", "--no-locale", "-E", "UTF8")
        started = False
        try:
            run("pg_ctl", "-D", data, "-l", directory / "postgres.log", "-o", f"-h 127.0.0.1 -p {port}", "-w", "start")
            started = True

            def sql(statement: str, database: str, required: bool = True):
                return run("psql", "-X", "-qAt", "-h", "127.0.0.1", "-p", port, "-U", "government_categories_test",
                           "-d", database, "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=verbose",
                           input_text=PREFIX + statement, required=required)

            def value(statement: str, database: str):
                return json.loads(sql(statement, database).stdout.strip())

            def rows(table: str, database: str):
                assert table in (CATEGORY,) + REFERENCES + PRESERVED
                return value(f"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]') FROM {table} t", database)

            def sequence(database: str):
                return value(f"SELECT jsonb_build_object('last_value',last_value,'is_called',is_called) FROM {SEQUENCE}", database)

            def snapshot(database: str):
                return {"rows": {table: rows(table, database) for table in (CATEGORY,) + REFERENCES + PRESERVED},
                        "sequence": sequence(database)}

            checks = 0

            def passed(description: str) -> None:
                nonlocal checks
                checks += 1
                print(f"通过 {checks:02d}：{description}", flush=True)

            def cloned(database: str) -> None:
                assert database.isidentifier()
                sql(f"CREATE DATABASE {database} TEMPLATE original", "postgres")

            def assert_tree(database: str, previous_high: int) -> None:
                categories = rows(CATEGORY, database)
                assert len(categories) == 35, "必须只有 7 个根类和 28 个平台子类"
                roots = sorted((row for row in categories if row["parent_id"] == 0), key=lambda row: row["sort"])
                assert [row["category_name"] for row in roots] == list(TREE)
                assert [row["sort"] for row in roots] == list(range(1, 8))
                root_ids = {row["id"] for row in roots}
                for root in roots:
                    assert root["category_code"] == f"0,{root['id']}"
                    assert "GB/T 19488.2-2008" in root["remark"]
                    children = sorted((row for row in categories if row["parent_id"] == root["id"]), key=lambda row: row["sort"])
                    assert [row["category_name"] for row in children] == list(TREE[root["category_name"]])
                    assert [row["sort"] for row in children] == list(range(1, len(children) + 1))
                    for child in children:
                        assert child["category_code"] == f"0,{root['id']},{child['id']}"
                        assert "平台管理子类" in child["remark"]
                assert all(row["parent_id"] in root_ids | {0} for row in categories)
                assert all(row["id"] > previous_high for row in categories), "不得复用任何已分配的旧类目编号"
                assert len({row["id"] for row in categories}) == 35
                assert all(row["status"] == 0 and row["deleted"] == 0 for row in categories)
                assert all(row["creator"] == "migration:0195" and row["updater"] == "migration:0195" for row in categories)
                state = sequence(database)
                assert state["is_called"] and state["last_value"] == max(row["id"] for row in categories)

            def assert_preserved(before: dict, database: str, fresh: bool = False) -> None:
                after = snapshot(database)
                for table in PRESERVED:
                    old_rows, new_rows = before["rows"][table], after["rows"][table]
                    if fresh and table in ("system_menu", "system_role_menu"):
                        # 新库分别执行旧迁移时生成的菜单审计时间天然不同，其余字段必须完全一致。
                        old_rows = [{key: item for key, item in row.items() if key not in ("create_time", "update_time")} for row in old_rows]
                        new_rows = [{key: item for key, item in row.items() if key not in ("create_time", "update_time")} for row in new_rows]
                    assert new_rows == old_rows, f"{table} 被无关修改"
                for table in REFERENCES:
                    old_rows = before["rows"][table]
                    new_rows = after["rows"][table]
                    assert [row["id"] for row in old_rows] == [row["id"] for row in new_rows]
                    for old, new in zip(old_rows, new_rows):
                        if old["category_id"] is None:
                            assert new == old, f"{table} 的未分类记录被修改"
                            continue
                        expected = {**old, "category_id": None, "updater": "migration:0195", "update_time": new["update_time"]}
                        if table != "dh_governance_standard_term":
                            expected["revision"] = old["revision"] + 1
                        assert new == expected, f"{table} 的分类之外内容变化：{old['id']}"

            def assert_rejected(database: str, expected: str = "P0001") -> None:
                before = snapshot(database)
                result = sql(migration, database, required=False)
                assert result.returncode and expected in result.stderr, result.stderr
                assert snapshot(database) == before, "拒绝或失败后数据及序列没有完整回滚"

            sql("CREATE DATABASE original; CREATE DATABASE fresh;", "postgres")
            sql(old_baseline, "original")
            original_snapshot = snapshot("original")
            cloned("upgrade")
            before = snapshot("upgrade")
            old_high = max(before["sequence"]["last_value"], *(row["id"] for row in before["rows"][CATEGORY]))
            sql(migration, "upgrade")
            assert_tree("upgrade", old_high)
            assert_preserved(before, "upgrade")
            assert_rejected("upgrade")
            passed("完整旧基线升级得到 35 项正确类目，原分类引用清空且其他内容保留，重复执行拒绝并保持不变")

            def fixture(database: str, sequence_value: int, called: bool = True) -> dict:
                cloned(database)
                sql(f"""INSERT INTO {CATEGORY}(id,parent_id,category_name,category_code,sort,status,deleted) VALUES
                    (6000,0,'待替换根类','0,6000',1,0,0),
                    (6001,6000,'待替换子类','0,6000,6001',1,0,0),
                    (9000,0,'已删除旧分类','0,9000',2,1,1);
                    SELECT setval('{SEQUENCE}',{sequence_value},{str(called).lower()});
                    INSERT INTO dh_governance_standard_element(id,name,eng_name,category_id,description,deleted,revision) VALUES
                    (800001,'保留数据元','verify_element_live',6000,'保留全部定义',0,12),
                    (800002,'历史数据元','verify_element_deleted',6001,'保留历史内容',1,24),
                    (800003,'未分类数据元','verify_element_none',NULL,'原本未分类',0,36),
                    (800004,'历史悬空数据元','verify_element_dangling',999999,'保留历史悬空内容',1,48);
                    INSERT INTO dh_governance_standard_code_set(id,name,type,category_id,description,deleted,revision) VALUES
                    (800001,'保留代码表','verify_codeset_live',6000,'保留全部定义',0,13),
                    (800002,'历史代码表','verify_codeset_deleted',6001,'保留历史内容',1,25),
                    (800003,'未分类代码表','verify_codeset_none',NULL,'原本未分类',0,37),
                    (800004,'历史悬空代码表','verify_codeset_dangling',999999,'保留历史悬空内容',1,49);
                    INSERT INTO dh_governance_standard_term(id,name,definition,category_id,deleted) VALUES
                    (800001,'保留业务术语','保留全部定义',6000,0),
                    (800002,'历史业务术语','保留历史内容',6001,1),
                    (800003,'未分类业务术语','原本未分类',NULL,0),
                    (800004,'历史悬空术语','保留历史悬空内容',999999,1);
                    INSERT INTO dh_governance_standard_element_version(id,element_id,version,snapshot,constraint_snapshot,
                        constraint_snapshot_hash,constraint_effective_at,rule_bindings_snapshot) VALUES
                    (800001,800001,1,'{{"category_id":6000,"name":"保留发布快照"}}',
                        '{{"category_id":6000,"value":"不可重写"}}',repeat('a',64),'2026-10-01 00:00:00','{{"category_id":6000}}');
                """, database)
                return snapshot(database)

            for name, seq_value, called in (("ahead", 12000, True), ("behind", 50, True), ("uncalled", 12000, False)):
                before = fixture(name, seq_value, called)
                old_high = max(seq_value, *(row["id"] for row in before["rows"][CATEGORY]))
                sql(migration, name)
                assert_tree(name, old_high)
                assert_preserved(before, name)
                assert_rejected(name)
                maximum = max(row["id"] for row in rows(CATEGORY, name))
                assert value(f"SELECT nextval('{SEQUENCE}')", name) == maximum + 1
                passed(f"{name}：覆盖正常/已删除/悬空/空引用，保留发布快照和版本，序列与旧编号均不回退，下一值正确")

            before = fixture("rollback", 12000)
            sql(f"CREATE TABLE external_category_reference(id bigint PRIMARY KEY, category_id bigint REFERENCES {CATEGORY}(id)); "
                "INSERT INTO external_category_reference VALUES (1,6000);", "rollback")
            assert_rejected("rollback", "23503")
            assert value("SELECT jsonb_agg(to_jsonb(t)) FROM external_category_reference t", "rollback") == [{"id": 1, "category_id": 6000}]
            passed("陌生外键阻止删除时三类引用、编辑版本、全部旧类目与序列完整回滚，外部引用保留")

            fixture("increment", 12000)
            sql(f"ALTER SEQUENCE {SEQUENCE} INCREMENT BY 2", "increment")
            assert_rejected("increment")
            assert value(f"SELECT seqincrement FROM pg_sequence WHERE seqrelid='{SEQUENCE}'::regclass", "increment") == 2
            passed("非标准序列步长拒绝执行且数据、版本、序列配置不变")

            fixture("overflow", 9223372036854775800)
            assert_rejected("overflow")
            passed("编号空间不足时提前拒绝执行，旧类目、引用及序列高水位不变")

            sql(baseline, "fresh")
            assert_tree("fresh", 0)
            assert all(row["category_id"] is None for table in REFERENCES for row in rows(table, "fresh"))
            assert_preserved(original_snapshot, "fresh", fresh=True)
            assert_rejected("fresh")
            passed("新库完整基线可执行并得到同一 35 项正式分类，旧分类引用清空，再次执行拒绝且不变")
            print(f"完成：{checks} 项真实 PostgreSQL 回归，跳过 0 项；临时集群将停止并清理。", flush=True)
        finally:
            if started:
                run("pg_ctl", "-D", data, "-m", "fast", "-w", "stop")


if __name__ == "__main__":
    main()
