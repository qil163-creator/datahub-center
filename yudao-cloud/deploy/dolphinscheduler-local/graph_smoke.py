#!/usr/bin/env python3
"""验证平台编译产物的图语义、UUID、Join 预算和 CSV 非覆盖行为。"""

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess
import uuid

from engines_smoke import native_operations
from smoke import Client


def spark_params(sql, label):
    return {"programType": "SQL", "rawScript": sql, "master": "local[1]", "deployMode": "local",
            "driverCores": 1, "driverMemory": "512m", "numExecutors": 1, "executorCores": 1,
            "executorMemory": "512m", "yarnQueue": "", "others": "", "sqlExecutionType": "SCRIPT",
            "sparkVersion": "SPARK2", "appName": label, "localParams": [], "resourceList": []}


def flink_params(sql, label):
    return {"programType": "SQL", "rawScript": sql, "deployMode": "LOCAL", "flinkVersion": ">=1.13",
            "slot": 1, "taskManager": 1, "parallelism": 1, "jobManagerMemory": "512m",
            "taskManagerMemory": "768m", "yarnQueue": "", "others": "", "appName": label,
            "localParams": [], "resourceList": [], "initScript": "\n".join([
                "SET 'execution.runtime-mode' = 'BATCH';", "SET 'execution.target' = 'local';",
                "SET 'table.dml-sync' = 'true';", "SET 'execution.attached' = 'true';",
                "SET 'jobmanager.memory.process.size' = '512m';",
                "SET 'taskmanager.memory.process.size' = '768m';", "SET 'taskmanager.numberOfTaskSlots' = '1';",
                "SET 'local.number-taskmanager' = '1';", "SET 'parallelism.default' = '1';",
            ]) + "\n"}


def graph_cases(client, connection, report, directory, selected_case=None):
    environments = {key: int(value) for key, value in connection["environmentCodes"].items()}
    create, log, bounded = native_operations(client, connection, environments, report)
    for case in ["branch", "join", "split"]:
        for engine in ["SPARK", "FLINK"]:
            if selected_case and selected_case != case.upper() + "_" + engine:
                continue
            sql = (directory / (case + "-" + engine.lower() + ".sql")).read_bytes().decode("utf-8")
            source = "CREATE TEMPORARY VIEW source AS SELECT * FROM (VALUES (CAST(1 AS BIGINT), ' a|b '), (CAST(2 AS BIGINT), 'c'), (CAST(NULL AS BIGINT), CAST(NULL AS STRING))) AS fixture_rows(id,name);"
            right = "CREATE TEMPORARY VIEW right_source AS SELECT * FROM (VALUES (CAST(1 AS BIGINT), 'right', 'match'), (CAST(NULL AS BIGINT), 'null-right', 'null-match')) AS fixture_rows(id,name,extra);"
            targets = ({"target_high": ["id", "name"], "target_low": ["id", "name"]} if case == "branch"
                       else {"join_target": ["id", "name", "extra"]} if case == "join"
                       else {"split_target": ["id", "first", "second"]})
            expected_rows = ({"target_high": [["2", "c"]], "target_low": [["1", " a|b "], ["NULL", "NULL"]]}
                             if case == "branch" else {"join_target": [["1", " a|b ", "match"], ["2", "c", "NULL"], ["NULL", "null-right", "null-match"]]}
                             if case == "join" else {"split_target": [["1", "a", "b "], ["2", "c", "NULL"], ["NULL", "", "NULL"]]})
            fixture = source + "\n" + (right if case == "join" else "") + "\n"
            epilogue = ""
            expected = []
            for target, columns in targets.items():
                fields = ", ".join("`" + name + "` " + ("BIGINT" if name == "id" else "STRING") for name in columns)
                marker = "RESULT_" + target.upper()
                if engine == "SPARK":
                    fixture += "CREATE TABLE `" + target + "` (" + fields + ") USING parquet;\n"
                    parts = ["COALESCE(CAST(`" + name + "` AS STRING), 'NULL')" for name in columns]
                    epilogue += "SELECT CONCAT('" + marker + "|', " + ", '|', ".join(parts) + ") FROM `" + target + "`;\n"
                    expected.extend(marker + "|" + "|".join(row) for row in expected_rows[target])
                else:
                    fixture += "CREATE TABLE `" + target + "` (" + fields + ") WITH ('connector'='print','print-identifier'='" + marker + "');\n"
                    expected.extend(marker + "> +I[" + ", ".join("null" if value == "NULL" else value for value in row) + "]" for row in expected_rows[target])
            script = fixture + sql + "\n" + epilogue
            label = case.upper() + "_" + engine
            output = bounded(engine, spark_params(script, label) if engine == "SPARK" else flink_params(script, label), expected, label)
            if engine == "SPARK":
                actual = [line.split(" -> ", 1)[1] for line in output.splitlines()
                          if " -> RESULT_" in line]
            else:
                actual = re.findall(r"RESULT_[A-Z_]+> \+I\[[^\r\n]*\]", output)
            if Counter(actual) != Counter(expected):
                raise RuntimeError(label + " 的完整结果行不一致")
            report["checks"][label]["expectedRows"] = expected
            report["checks"][label]["exactRowsMatched"] = True


def container_python(script):
    result = subprocess.run(["docker", "exec", "--user", "datahub", "datahub-ds-local-standalone-1",
                             "python3", "-c", script], text=True, capture_output=True, check=True)
    return json.loads(result.stdout) if result.stdout else None


def csv_case(client, connection, report, artifact):
    environments = {key: int(value) for key, value in connection["environmentCodes"].items()}
    create, log, bounded = native_operations(client, connection, environments, report)
    container_python("""import csv,json
from pathlib import Path
base=Path('/home/datahub/fixtures');base.mkdir(exist_ok=True)
out=base/'out';out.mkdir(exist_ok=True)
assert not list(out.iterdir()), '本次验收需要新的空输出目录，拒绝清理现有文件'
with (base/'source.csv').open('w',encoding='utf-8',newline='') as f:
 w=csv.writer(f);w.writerow(['id','名称']);w.writerows([[1,'河道A'],[2,'河道,乙']])
print(json.dumps({'prepared':True}))
""")
    params = json.loads(artifact.read_text())
    bounded("DATAX", params, "读出记录总数", "CSV_READ_WRITE")
    read_output = """import csv,hashlib,json
from pathlib import Path
files=sorted(Path('/home/datahub/fixtures/out').glob('replica*'))
rows=[];digests={}
for p in files:
 if not p.is_file():continue
 digests[p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
 with p.open(encoding='utf-8',newline='') as f:rows.extend(list(csv.reader(f)))
print(json.dumps({'rows':rows,'digests':digests},ensure_ascii=False))
"""
    before = container_python(read_output)
    if before["rows"] != [["id", "名称"], ["1", "河道A"], ["2", "河道,乙"]]:
        raise RuntimeError("CSV 输出未保留中文、逗号或表头")
    report["checks"]["CSV_READ_WRITE"]["exactRowsMatched"] = True
    report["checks"]["CSV_READ_WRITE"]["rows"] = before["rows"]
    failure = bounded("DATAX", params, ["nonConflict", "Code:[TxtFileWriter-02]", "目录不为空"], "CSV_NO_OVERWRITE", "FAILURE")
    after = container_python(read_output)
    if before != after:
        raise RuntimeError("nonConflict 失败后输出发生覆盖")
    report["checks"]["CSV_NO_OVERWRITE"]["outputHashesUnchanged"] = True


def failure_case(client, connection, report):
    environments = {key: int(value) for key, value in connection["environmentCodes"].items()}
    create, log, bounded = native_operations(client, connection, environments, report)
    bounded("FLINK", flink_params("SELECT DATAHUB_MISSING_FUNCTION(1);", "SQL_ERROR_PROPAGATION"),
            ["[ERROR] Could not execute SQL statement.", "Flink SQL 执行失败，拒绝向调度器报告成功。"],
            "SQL_ERROR_PROPAGATION", "FAILURE")
    report["checks"]["SQL_ERROR_PROPAGATION"]["expectedErrorExitPropagated"] = True


def mixed_case(client, connection, report, directory):
    environments = {key: int(value) for key, value in connection["environmentCodes"].items()}
    create, log, bounded = native_operations(client, connection, environments, report)
    source = "CREATE TEMPORARY VIEW source AS SELECT * FROM (VALUES (CAST(1 AS BIGINT), ' a|b '), (CAST(2 AS BIGINT), 'c'), (CAST(NULL AS BIGINT), CAST(NULL AS STRING))) AS fixture_rows(id,name);\n"
    expected = [(1, "name", " a|b "), (1, "copy", " a|b "), (2, "name", "c"),
                (2, "copy", "c"), (None, "name", None), (None, "copy", None)]
    for engine in ["SPARK", "FLINK"]:
        artifact = (directory / ("unpivot-" + engine.lower() + ".sql")).read_bytes().decode("utf-8")
        fixture = source + "CREATE TABLE unpivot_target (`id` BIGINT, `key` STRING, `value` STRING, `guid` STRING) "
        fixture += "USING parquet;\n" if engine == "SPARK" else "WITH ('connector'='print','print-identifier'='RESULT_UUID');\n"
        epilogue = "\nSELECT CONCAT('RESULT_UUID|', TO_JSON(NAMED_STRUCT('id',`id`,'key',`key`,'value',`value`,'guid',`guid`))) FROM unpivot_target;" if engine == "SPARK" else ""
        label = "UNPIVOT_UUID_" + engine
        script = fixture + artifact + epilogue
        output = bounded(engine, spark_params(script, label) if engine == "SPARK" else flink_params(script, label),
                         "RESULT_UUID|{" if engine == "SPARK" else "RESULT_UUID> +I[", label)
        rows = []
        if engine == "SPARK":
            for line in output.splitlines():
                if " -> RESULT_UUID|{" in line:
                    item = json.loads(line.split(" -> RESULT_UUID|", 1)[1])
                    rows.append((item.get("id"), item.get("key"), item.get("value"), item.get("guid")))
        else:
            for raw in re.findall(r"RESULT_UUID> \+I\[([^\r\n]*)\]", output):
                values = raw.split(", ", 3)
                rows.append((None if values[0] == "null" else int(values[0]), values[1],
                             None if values[2] == "null" else values[2], values[3]))
        if Counter(row[:3] for row in rows) != Counter(expected):
            raise RuntimeError(label + " 展开后的全部值与 NULL 语义不一致")
        guids = [row[3] for row in rows]
        if len(guids) != 6 or len(set(guids)) != 6 or any(str(uuid.UUID(value)).lower() != value.lower() for value in guids):
            raise RuntimeError(label + " UUID 格式或每展开行唯一性不成立")
        report["checks"][label].update({"expandedRows": 6, "exactRowsMatched": True,
                                        "validUuidFormat": True, "distinctUuidCount": 6})


def budget_case(client, connection, report, directory):
    environments = {key: int(value) for key, value in connection["environmentCodes"].items()}
    create, log, bounded = native_operations(client, connection, environments, report)
    for engine in ["SPARK", "FLINK"]:
        graph_cases(client, connection, report, directory, "JOIN_" + engine)
        source = "CREATE TEMPORARY VIEW source AS SELECT * FROM (VALUES (CAST(1 AS BIGINT), ' a|b '), (CAST(2 AS BIGINT), 'c'), (CAST(NULL AS BIGINT), CAST(NULL AS STRING))) AS fixture_rows(id,name);\n"
        right = "CREATE TEMPORARY VIEW right_source AS SELECT * FROM (VALUES (CAST(1 AS BIGINT), 'right', 'match'), (CAST(NULL AS BIGINT), 'null-right', 'null-match')) AS fixture_rows(id,name,extra);\n"
        target = "CREATE TABLE join_target (`id` BIGINT, `name` STRING, `extra` STRING) "
        target += "USING parquet;\n" if engine == "SPARK" else "WITH ('connector'='print','print-identifier'='RESULT_JOIN_LIMIT');\n"
        sql = (directory / ("join-limit-" + engine.lower() + ".sql")).read_bytes().decode("utf-8")
        label = "JOIN_LIMIT_" + engine
        script = source + right + target + sql
        bounded(engine, spark_params(script, label) if engine == "SPARK" else flink_params(script, label),
                "Join 最大输出行数超限", label, "FAILURE")
        report["checks"][label].update({"fixtureRows": 3, "maxOutputRows": 2,
                                         "expectedErrorExitPropagated": True})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private-config", type=Path, required=True)
    parser.add_argument("--checks", choices=["graphs", "csv", "failure", "mixed", "budget"], required=True)
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--only-case", choices=[case + "_" + engine for case in ("BRANCH", "JOIN", "SPLIT")
                                               for engine in ("SPARK", "FLINK")])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    connection = json.loads(args.private_config.read_text())
    client = Client(connection["server"])
    client.token = connection["token"]
    report = {"projectCode": connection["projectCode"], "scope": args.checks, "checks": {}, "status": "RUNNING"}
    try:
        if args.checks == "graphs":
            if not args.artifact:
                raise ValueError("图语义验收需要 --artifact SQL目录")
            graph_cases(client, connection, report, args.artifact, args.only_case)
        elif args.checks == "csv":
            if not args.artifact:
                raise ValueError("CSV验收需要 --artifact 参数文件")
            csv_case(client, connection, report, args.artifact)
        elif args.checks == "failure":
            failure_case(client, connection, report)
        elif args.checks == "mixed":
            if not args.artifact:
                raise ValueError("UUID/列转行验收需要 --artifact SQL目录")
            mixed_case(client, connection, report, args.artifact)
        else:
            if not args.artifact:
                raise ValueError("Join预算验收需要 --artifact SQL目录")
            budget_case(client, connection, report, args.artifact)
        report["status"] = "PASS"
    except Exception:
        report["status"] = "FAIL"
        raise
    finally:
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
