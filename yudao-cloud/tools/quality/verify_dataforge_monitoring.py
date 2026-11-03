"""Opt-in, isolated Prometheus -> Alertmanager -> local webhook verification.

Two synthetic endpoints model the exported metric contract, NOT two DataForge apps.
Production expressions/thresholds are unchanged; only interval/for are accelerated
in a temporary copy. Run promtool fixtures separately for production timing.
No host ports, host socket, business data or external notification receiver.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote

PROMETHEUS = "prom/prometheus:v3.5.0@sha256:63805ebb8d2b3920190daf1cb14a60871b16fd38bed42b857a3182bc621f4996"
ALERTMANAGER = "prom/alertmanager:v0.28.1@sha256:27c475db5fb156cab31d5c18a4251ac7ed567746a2483ff264516437a39b15ba"
PYTHON = "python:3.13-alpine@sha256:79e7a9b9ff1cbceff819f856fb374477792a5967759d94df266de7b7b4120e6f"
TASK = "datahub-capacity-7"
SCENARIOS = {
    "business": {"DataForgeRunCapacityConfigMismatch", "DataForgeRunQueueDelayed", "DataForgeRunUnknownUnresolved",
                 "DataForgeRunReviewRequired", "DataForgeRunStopDelayed", "DataForgeRunLeaseRecoveryDelayed",
                 "DataForgeRunScheduleDelayed"},
    "health": {"DataForgeRunMetricsMissing", "DataForgeRunSampleUnavailable"},
    "down": {"DataForgeRunTargetDown"},
    "scan": {"DataForgeRunScanFailures"},
}


def transport_rules(source: str) -> str:
    """Keep every production condition; time compression applies only to this fixture."""
    alerts = re.findall(r"^      - alert: (\w+)$", source, re.M)
    if set(alerts) != set().union(*SCENARIOS.values()) or len(alerts) != len(set(alerts)):
        raise ValueError("Reconcile transport scenarios with the changed production alert set")
    result = re.sub(r"^(    interval:) .+$", r"\1 1s", source, flags=re.M)
    return re.sub(r"^(        for:) .+$", r"\1 2s", result, flags=re.M)


def metric_lines(phase: str, node_b: bool, scan_failures: int) -> list[str]:
    lines = [
        f'dataforge_run_capacity_limit{{scope="tenant"}} {64 if node_b and phase == "business" else 32}',
        'dataforge_run_capacity_limit{scope="datasource"} 8',
    ]
    for domain in ("ETL", "ACCESS"):
        values = {"metrics_available": 1, "metrics_age_seconds": 1, "queued": 0,
                  "queue_oldest_age_seconds": 0, "slots": 0, "unknown": 0,
                  "review_required": 0, "stop_pending": 0, "stop_oldest_age_seconds": 0,
                  "lease_expired": 0, "schedule_overdue_age_seconds": 0}
        if phase == "business" and domain == "ETL":
            values.update(queued=12, queue_oldest_age_seconds=600, unknown=1, review_required=1,
                          stop_pending=1, stop_oldest_age_seconds=180, lease_expired=1,
                          schedule_overdue_age_seconds=200)
        if phase == "health" and domain == "ETL":
            if node_b:
                values["metrics_available"] = 0
            else:
                del values["metrics_age_seconds"]
        lines.extend(f'dataforge_run_{name}{{domain="{domain}"}} {value}' for name, value in values.items())
        # 计数器单调递增；恢复表示没有新增错误，不能用计数重置或时间序列缺失表示恢复。
        lines.append(f'dataforge_run_scan_failures_total{{domain="{domain}",phase="DISPATCH"}} {scan_failures}')
    return lines


def fixture() -> None:
    state = {"phase": "healthy", "events": [], "scan_failures": 0}
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, value, status=200, content_type="application/json"):
            body = value.encode() if isinstance(value, str) else json.dumps(value).encode()
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            with lock:
                phase = state["phase"]
                scan_failures = state["scan_failures"]
                if self.server.server_port == 9103:
                    self.reply(state)
                    return
            node_b = self.server.server_port == 9102
            if phase == "down" and node_b:
                self.reply("unavailable", 503, "text/plain")
                return
            lines = metric_lines(phase, node_b, scan_failures)
            self.reply("\n".join(lines) + "\n", content_type="text/plain; version=0.0.4")

        def do_POST(self):
            if self.server.server_port != 9103:
                self.reply({}, 404)
                return
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 65536:
                self.reply({}, 413)
                return
            payload = json.loads(self.rfile.read(length))
            with lock:
                if self.path == "/phase" and payload.get("phase") in {"healthy", *SCENARIOS}:
                    if payload["phase"] == "scan" and state["phase"] != "scan":
                        state["scan_failures"] += 1
                    state["phase"] = payload["phase"]
                elif self.path == "/alerts" and isinstance(payload.get("alerts"), list):
                    for alert in payload["alerts"]:
                        state["events"].append({"name": alert["labels"]["alertname"], "status": alert["status"],
                                                "labels": alert["labels"]})
                    state["events"] = state["events"][-1000:]
                else:
                    self.reply({}, 400)
                    return
            self.reply({"ok": True})

    servers = [ThreadingHTTPServer(("0.0.0.0", port), Handler) for port in (9101, 9102, 9103)]
    # 监听端口仅存在于私有 Docker 网络中，不向外发布。
    for server in servers:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    threading.Event().wait()


def docker(*args: str, timeout=60) -> str:
    completed = subprocess.run(["docker", *args], text=True, capture_output=True, timeout=timeout, check=False)
    if completed.returncode:
        raise RuntimeError(f"docker {args[0]} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def run() -> dict:
    root = Path(__file__).resolve().parents[2]
    prefix = "codex-q7-monitor-" + uuid.uuid4().hex[:12]
    network = prefix + "-network"
    owned = {"io.codex.task": TASK, "io.codex.disposable": "true", "io.codex.run": prefix}
    labels = [argument for key, value in owned.items() for argument in ("--label", f"{key}={value}")]
    names = []
    network_created = False
    report = {"production_timing_accelerated": True, "synthetic_endpoints": 2,
              "scan_increase_window_seconds": 300, "scenarios": []}
    with tempfile.TemporaryDirectory(prefix=prefix + "-") as temporary:
        config = Path(temporary).resolve()
        (config / "rules.yml").write_text(transport_rules(
            (root / "script/monitoring/dataforge-run.rules.yml").read_text(encoding="utf-8")), encoding="utf-8")
        (config / "prometheus.yml").write_text("""global:
  scrape_interval: 1s
  evaluation_interval: 1s
rule_files: [/work/rules.yml]
alerting:
  alertmanagers:
    - static_configs:
        - targets: ['alerts:9093']
scrape_configs:
  - job_name: dataforge-run
    scrape_timeout: 1s
    static_configs:
      - targets: ['fixture:9101', 'fixture:9102']
        labels: {cluster: isolated-q7}
""", encoding="utf-8")
        (config / "alertmanager.yml").write_text("""route:
  receiver: isolated-fixture
  group_by: [alertname, cluster, job, domain, instance, scope, phase]
  group_wait: 0s
  group_interval: 1s
  repeat_interval: 1h
receivers:
  - name: isolated-fixture
    webhook_configs:
      - url: http://fixture:9103/alerts
        send_resolved: true
""", encoding="utf-8")
        # 只复制此处构造的测试夹具，不挂载仓库或 Docker 套接字。
        (config / "fixture.py").write_text(Path(__file__).read_text(encoding="utf-8"), encoding="utf-8")

        def start(role, image, *arguments, tmpfs=None):
            name = prefix + "-" + role
            names.append(name)  # 清理范围也应包含只完成部分创建流程的容器。
            options = ["run", "-d", "--name", name, "--network", network, "--network-alias", role,
                       *labels, "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                       "--memory", "256m", "--cpus", "1", "--pids-limit", "128",
                       "--mount", f"type=bind,source={config},target=/work,readonly"]
            if tmpfs:
                options += ["--tmpfs", tmpfs]
            docker(*options, image, *arguments)

        def request(url, payload=None):
            code = ("import json,sys,urllib.request; "
                    "data=None if sys.argv[2]=='null' else sys.argv[2].encode(); "
                    "r=urllib.request.Request(sys.argv[1],data=data,headers={'Content-Type':'application/json'}); "
                    "print(urllib.request.urlopen(r,timeout=3).read().decode())")
            return json.loads(docker("exec", prefix + "-fixture", "python", "-c", code, url, json.dumps(payload), timeout=10))

        def wait(description, check, budget=60):
            deadline = time.monotonic() + budget
            last_error = None
            while time.monotonic() < deadline:
                try:
                    result = check()
                    if result:
                        return result
                except (RuntimeError, KeyError, ValueError) as failure:
                    last_error = type(failure).__name__
                time.sleep(0.5)
            raise AssertionError(f"Timed out: {description}; last_error={last_error}")

        def statuses():
            return {(event["name"], event["status"]) for event in request("http://fixture:9103/")["events"]}

        def scan_delivered(status):
            events = request("http://fixture:9103/")["events"]
            domains = {event["labels"].get("domain") for event in events
                       if event["name"] == "DataForgeRunScanFailures" and event["status"] == status
                       and event["labels"].get("phase") == "DISPATCH"
                       and event["labels"].get("cluster") == "isolated-q7"}
            return domains == {"ETL", "ACCESS"}

        def query(expression):
            return request("http://prometheus:9090/api/v1/query?query=" + quote(expression, safe=""))["data"]["result"]

        try:
            for image in (PROMETHEUS, ALERTMANAGER, PYTHON):
                docker("image", "inspect", image)  # 显式检查前置条件，不在后台隐式获取镜像。
            docker("network", "create", "--internal", *labels, network)
            network_created = True
            start("fixture", PYTHON, "python", "-B", "/work/fixture.py", "fixture")
            start("alerts", ALERTMANAGER, "--config.file=/work/alertmanager.yml", "--storage.path=/alertmanager",
                  "--cluster.listen-address=", tmpfs="/alertmanager:rw,noexec,nosuid,size=32m,uid=65534,gid=65534")
            start("prometheus", PROMETHEUS, "--config.file=/work/prometheus.yml", "--storage.tsdb.path=/prometheus",
                  "--storage.tsdb.retention.time=1h", tmpfs="/prometheus:rw,noexec,nosuid,size=64m,uid=65534,gid=65534")

            def both_up():
                targets = request("http://prometheus:9090/api/v1/targets")["data"]["activeTargets"]
                return len(targets) == 2 and all(t["health"] == "up" for t in targets)

            wait("both synthetic targets healthy", both_up)
            for phase, alerts in SCENARIOS.items():
                if phase == "scan":
                    wait("four scan counters have a baseline", lambda: query(
                        'count(count_over_time(dataforge_run_scan_failures_total[5m]) >= 2) == 4'))
                request("http://fixture:9103/phase", {"phase": phase})
                wait(phase + " firing delivery", lambda: scan_delivered("firing") if phase == "scan"
                     else {(name, "firing") for name in alerts} <= statuses())
                if phase == "business":
                    samples = request("http://prometheus:9090/api/v1/query?query=dataforge:run_queued")["data"]["result"]
                    assert any(s["metric"].get("domain") == "ETL" and float(s["value"][1]) == 12 for s in samples), samples
                request("http://fixture:9103/phase", {"phase": "healthy"})
                recovering = time.monotonic()
                if phase == "scan":
                    print("WAIT scan: firing received for ETL/ACCESS; preserving the real 5-minute increase window", flush=True)
                wait(phase + " resolved delivery", lambda: scan_delivered("resolved") if phase == "scan"
                     else {(name, "resolved") for name in alerts} <= statuses(), budget=360 if phase == "scan" else 60)
                report["scenarios"].append({"phase": phase, "fired_and_resolved": sorted(alerts),
                                            "recovery_seconds": round(time.monotonic() - recovering, 2)})
                print(f"PASS {phase}: firing and resolved received ({len(alerts)} alerts)", flush=True)
            wait("targets recovered", both_up)
            groups = request("http://prometheus:9090/api/v1/rules")["data"]["groups"]
            assert all(rule["health"] == "ok" and not rule.get("lastError") for group in groups for rule in group["rules"])
            report["events"] = request("http://fixture:9103/")["events"]
            report["images"] = [PROMETHEUS, ALERTMANAGER, PYTHON]
        finally:
            cleanup_errors = []
            for name in reversed(names):
                try:
                    listing = docker("ps", "-a", "--filter", f"name=^/{name}$", "--format", "{{.Names}}")
                    if not listing:
                        continue
                    item = json.loads(docker("inspect", name))[0]
                    if item["Name"] != "/" + name or any(item["Config"]["Labels"].get(k) != v for k, v in owned.items()):
                        raise RuntimeError("Refusing cleanup without exact task ownership")
                    if item["State"]["Running"]:
                        docker("stop", "--time", "5", name, timeout=20)
                    docker("rm", "-v", name)
                except (RuntimeError, subprocess.TimeoutExpired) as failure:
                    cleanup_errors.append(f"{name}: {failure}")
            if network_created:
                try:
                    item = json.loads(docker("network", "inspect", network))[0]
                    if item["Name"] != network or any(item["Labels"].get(k) != v for k, v in owned.items()):
                        raise RuntimeError("Refusing network cleanup without exact task ownership")
                    docker("network", "rm", network)
                except (RuntimeError, subprocess.TimeoutExpired) as failure:
                    cleanup_errors.append(f"{network}: {failure}")
            if cleanup_errors:
                raise RuntimeError("Cleanup incomplete: " + "; ".join(cleanup_errors))
        report["cleanup"] = "owned containers, disposable data and private network removed; images retained"
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("run", "fixture"))
    args = parser.parse_args()
    if args.mode == "fixture":
        fixture()
    else:
        result = run()
        path = Path(__file__).resolve().parents[2] / "target/q7-monitoring-verification.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(f"PASS report: {path}")
