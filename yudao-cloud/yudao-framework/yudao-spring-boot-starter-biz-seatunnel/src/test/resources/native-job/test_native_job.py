#!/usr/bin/env python3
"""使用独立 SeaTunnel 3.0 容器验收原生 JSON/HOCON，退出时只清理本次资源。"""

import argparse
import json
import os
import pathlib
import subprocess
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET


HERE = pathlib.Path(__file__).resolve().parent
ROOT = next(path for path in HERE.parents if (path / "pom.xml").is_file() and (path / "yudao-framework").is_dir())
MODULE = ROOT / "yudao-framework/yudao-spring-boot-starter-biz-seatunnel"
IMAGE = "apache/seatunnel:3.0.0"
LABEL = "codex-seatunnel-native-test"


def command(arguments, source=None, timeout=180):
    # 字节管道避免 Windows 把容器 JVM 配置中的换行改成 CRLF。
    result = subprocess.run(arguments, input=source.encode("utf-8") if source is not None else None,
                            capture_output=True, timeout=timeout, cwd=ROOT)
    output = result.stdout.decode("utf-8", errors="replace")
    error = result.stderr.decode("utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(error[-3000:] or output[-3000:] or "测试命令失败")
    return output.strip()


def classpath():
    reports = sorted((MODULE / "target/surefire-reports").glob("TEST-*.xml"),
                     key=lambda path: path.stat().st_mtime, reverse=True)
    for report in reports:
        for entry in ET.parse(report).getroot().findall("properties/property"):
            if entry.get("name") == "java.class.path" and entry.get("value"):
                return entry.get("value")
    raise RuntimeError("请先运行 SeaTunnel starter 组件测试，以取得当前依赖类路径")


def cleanup(context):
    """核对精确名称和随机归属标签后，仅删除本次新建资源。"""
    for resource, kind in ((context["container"], "container"), (context["network"], "network")):
        expected = "codex-seatunnel-native-" + ("engine-" if kind == "container" else "network-") + context["id"]
        if resource != expected:
            raise RuntimeError("资源名称不属于本次测试")
        result = subprocess.run(["docker", kind, "inspect", resource], capture_output=True, text=True,
                                encoding="utf-8", timeout=30)
        if result.returncode:
            if "no such" in result.stderr.lower():
                continue
            raise RuntimeError("无法确认测试资源是否已清理")
        details = json.loads(result.stdout)[0]
        labels = details["Config"]["Labels"] if kind == "container" else details["Labels"]
        if labels.get(LABEL) != context["id"]:
            raise RuntimeError("拒绝删除归属标签不符的资源")
        command(["docker", "rm", "-f", "-v", resource] if kind == "container"
                else ["docker", "network", "rm", resource])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", required=True)
    parser.add_argument("--directory", default="target/seatunnel-native")
    parser.add_argument("--cleanup", action="store_true", help="进程被强制退出后，按保存的归属信息清理")
    args = parser.parse_args()
    directory = (ROOT / args.directory).resolve()
    if not directory.is_relative_to((ROOT / "target").resolve()):
        raise RuntimeError("验收输出必须位于本仓库 target 内")
    directory.mkdir(parents=True, exist_ok=True)
    context_file = directory / "context.json"
    if args.cleanup:
        cleanup(json.loads(context_file.read_text(encoding="utf-8")))
        context_file.unlink()
        print("本次原生验收资源已清理")
        return
    if context_file.exists():
        raise RuntimeError("上下文已存在，请先使用 --cleanup 清理或选择新的输出目录")
    java_home = pathlib.Path(args.java_home)
    java = str(java_home / "bin" / ("java.exe" if os.name == "nt" else "java"))
    javac = str(java_home / "bin" / ("javac.exe" if os.name == "nt" else "javac"))
    dependencies = classpath()
    command([javac, "-encoding", "UTF-8", "-cp", dependencies, "-d", str(directory), str(HERE / "NativeJobSmoke.java")])
    run_id = uuid.uuid4().hex[:12]
    context = {"id": run_id, "container": "codex-seatunnel-native-engine-" + run_id,
               "network": "codex-seatunnel-native-network-" + run_id}
    context_file.write_text(json.dumps(context, indent=2), encoding="utf-8")
    report = {"image": IMAGE, "tests": [], "resourcesCleaned": False}
    try:
        report["imageDigests"] = json.loads(command(["docker", "image", "inspect", IMAGE, "--format", "{{json .RepoDigests}}"] ))
        command(["docker", "network", "create", "--label", LABEL + "=" + run_id, context["network"]])
        command(["docker", "run", "-d", "--name", context["container"], "--label", LABEL + "=" + run_id,
                 "--network", context["network"], "--entrypoint", "sh", "-p", "127.0.0.1::8080", IMAGE,
                 "-c", "sleep infinity"])
        port = command(["docker", "port", context["container"], "8080/tcp"]).split(":")[-1]
        upstream = "http://127.0.0.1:" + port
        command(["docker", "exec", "-i", context["container"], "sh", "-c", "cat >> /opt/seatunnel/config/jvm_options"],
                source="\n-Xms512m\n-Xmx1536m\n-XX:ActiveProcessorCount=4\n")
        command(["docker", "exec", context["container"], "bash", "/opt/seatunnel/bin/seatunnel-cluster.sh", "-d"])
        print("独立原生作业引擎启动中", flush=True)
        deadline = time.monotonic() + 100
        while True:
            try:
                with urllib.request.urlopen(upstream + "/running-jobs", timeout=3) as response:
                    if response.status == 200:
                        break
            except OSError:
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("隔离引擎 REST 启动超时")
            time.sleep(1)
        for format_name in ("json", "hocon"):
            job_report = directory / (run_id + "-" + format_name + ".json")
            output = command([java, "-cp", str(directory) + os.pathsep + dependencies, "NativeJobSmoke", upstream,
                              str(HERE / ("job." + format_name)), format_name, "native-query-" + format_name + "-" + run_id,
                              str(job_report)], timeout=160)
            print(output, flush=True)
            report["tests"].append(json.loads(job_report.read_text(encoding="utf-8")))
        report["passed"] = True
    except Exception as failure:
        report["passed"] = False
        report["failureType"] = type(failure).__name__
        # 固定夹具没有凭据；失败时保留本次引擎日志，便于区分提交链路和连接器错误。
        try:
            logs = command(["docker", "exec", context["container"], "sh", "-c",
                            "tail -n 150 /opt/seatunnel/logs/*"])
            (directory / "engine-failure.log").write_text(logs, encoding="utf-8")
        except RuntimeError:
            pass
        raise
    finally:
        cleanup(context)
        context_file.unlink(missing_ok=True)
        report["resourcesCleaned"] = True
        (directory / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print("本次测试容器及网络已清理；无密报告：" + str(directory / "report.json"), flush=True)


if __name__ == "__main__":
    main()
