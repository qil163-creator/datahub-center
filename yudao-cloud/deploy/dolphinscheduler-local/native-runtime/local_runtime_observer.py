#!/usr/bin/env python3
"""以 root 监督受管本地 SQL 引擎，提供不含日志的 mTLS 状态查询。"""

import argparse
import base64
import ctypes
import grp
import hashlib
import http.server
import json
import os
from pathlib import Path
import pwd
import re
import select
import signal
import socket
import socketserver
import ssl
import stat
import subprocess
import sys
import threading
import time
import urllib.parse

NAME = re.compile(r"dh_native_[1-9][0-9]{0,18}\Z")
USER = "datahub"
ENGINE_PATHS = {
    "FLINK": "/opt/flink/bin/sql-client-managed-original.sh",
    "SPARK": "/opt/spark/bin/spark-sql-managed-original",
}
PUBLIC_KEYS = ("name", "user", "engine", "state", "exitCode", "started", "finished", "processTreeAlive")
SPARK_RESOURCE_VALUES = {
    "spark.driver.cores": {"1"},
    "spark.executor.cores": {"1"},
    "spark.executor.instances": {"1"},
    "spark.driver.memory": {"512m", "768m", "1024m", "1g"},
    "spark.executor.memory": {"512m", "768m", "1024m", "1g"},
}


class RequestRejected(ValueError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def validate_spark_configuration(entry, name):
    key, separator, value = entry.partition("=")
    if key in SPARK_RESOURCE_VALUES:
        if separator and value in SPARK_RESOURCE_VALUES[key]:
            return
        raise RequestRejected("SPARK_RESOURCE_OUT_OF_BOUNDS", "Spark 资源配置超出本地执行预算")
    allowed = {"spark.app.name=" + name, "spark.ui.enabled=false",
               "spark.driver.host=127.0.0.1", "spark.driver.bindAddress=127.0.0.1",
               "spark.sql.shuffle.partitions=1", "spark.default.parallelism=1"}
    if entry not in allowed:
        raise RequestRejected("SPARK_CONFIG_NOT_ALLOWED", "Spark 配置未获许可")


def atomic_json(path, data):
    temporary = path.with_suffix(".tmp")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as output:
        json.dump(data, output, separators=(",", ":"))
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)
    directory = os.open(path.parent, os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def state_for(manifest, **values):
    return {"name": manifest["name"], "user": USER, "engine": manifest["engine"],
            "state": "UNKNOWN", "exitCode": None, "started": False,
            "finished": False, "processTreeAlive": False, **values}


def sql_sources(arguments, cwd):
    result = []
    counts = {"file": 0, "init": 0}
    for index, value in enumerate(arguments):
        if value not in ("-f", "--file", "-i", "--init"):
            continue
        if index + 1 >= len(arguments):
            raise ValueError("SQL 文件参数缺少取值")
        counts["file" if value in ("-f", "--file") else "init"] += 1
        source = Path(arguments[index + 1])
        if not source.is_absolute():
            source = Path(cwd) / source
        source = source.resolve(strict=True)
        info = source.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != pwd.getpwnam(USER).pw_uid or info.st_size > 1024 * 1024:
            raise ValueError("SQL 文件不属于执行用户或超过限制")
        result.append((index + 1, source))
    if counts["file"] != 1 or counts["init"] > 1:
        raise ValueError("必须指定唯一 SQL 文件及可选初始化文件")
    return result


def validate_request(request):
    if set(request) != {"name", "engine", "arguments", "cwd"} or not NAME.fullmatch(str(request["name"])):
        raise ValueError("执行标识无效")
    engine = request["engine"]
    if engine not in ENGINE_PATHS or not isinstance(request["arguments"], list) or len(request["arguments"]) > 64:
        raise ValueError("不支持的执行命令")
    arguments = request["arguments"]
    if any(not isinstance(value, str) or len(value) > 2048 or "\x00" in value for value in arguments):
        raise ValueError("参数无效")
    cwd = Path(request["cwd"]).resolve(strict=True)
    allowed_root = Path("/tmp/dolphinscheduler").resolve()
    if not cwd.is_relative_to(allowed_root) or not cwd.is_dir():
        raise ValueError("工作目录不属于调度器")
    sources = sql_sources(arguments, cwd)
    allowed_values = {
        "--master": {"local[1]"}, "--deploy-mode": {"client", "local"},
        "--driver-memory": {"512m", "768m", "1024m", "1g"},
        "--executor-memory": {"512m", "768m", "1024m", "1g"},
        "--driver-cores": {"1"}, "--executor-cores": {"1"}, "--num-executors": {"1"},
        "--name": {request["name"]}, "--database": {"default"},
    }
    index = 0
    while index < len(arguments):
        value = arguments[index]
        if engine == "FLINK" and value == "embedded" and index == 0:
            index += 1
            continue
        if value in ("-f", "--file") or engine == "FLINK" and value in ("-i", "--init"):
            index += 2
            continue
        if engine == "SPARK" and value in ("-S", "--silent"):
            index += 1
            continue
        if engine == "SPARK" and value in allowed_values and index + 1 < len(arguments):
            if arguments[index + 1] not in allowed_values[value]:
                raise ValueError("参数超出本地执行预算")
            index += 2
            continue
        if engine == "SPARK" and value == "--conf" and index + 1 < len(arguments):
            validate_spark_configuration(arguments[index + 1], request["name"])
            index += 2
            continue
        if engine == "FLINK" and value == "-Dpipeline.name=" + request["name"]:
            index += 1
            continue
        raise ValueError("命令包含未获许可的参数")
    if engine == "SPARK" and "--master" not in arguments:
        raise ValueError("Spark 必须显式使用 local[1]")
    return sources


def process_rows():
    rows = {}
    for path in Path("/proc").glob("[0-9]*/stat"):
        try:
            text = path.read_text()
            rest = text[text.rfind(")") + 2:].split()
            rows[int(path.parent.name)] = (int(rest[1]), rest[0])
        except (OSError, ValueError, IndexError):
            continue
    return rows


def descendants(root_pid):
    rows = process_rows()
    selected = {root_pid}
    while True:
        additions = {pid for pid, (parent, _) in rows.items() if parent in selected}
        previous = len(selected)
        selected.update(additions)
        if len(selected) == previous:
            return [pid for pid in selected if pid != root_pid and rows.get(pid, (0, "Z"))[1] != "Z"]


def signal_descendants(root_pid, signum):
    for pid in descendants(root_pid):
        try:
            descriptor = os.pidfd_open(pid)
            try:
                signal.pidfd_send_signal(descriptor, signum)
            finally:
                os.close(descriptor)
        except (OSError, ProcessLookupError):
            continue


def supervise(directory):
    manifest = json.loads((directory / "manifest.json").read_text())
    result_path = directory / "state.json"
    cancellation = [False]
    signal_sent = False
    signal.signal(signal.SIGTERM, lambda *_: cancellation.__setitem__(0, True))
    signal.signal(signal.SIGINT, lambda *_: cancellation.__setitem__(0, True))
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(36, 1, 0, 0, 0) != 0 or libc.prctl(1, signal.SIGTERM, 0, 0, 0) != 0:
        raise RuntimeError("无法建立受管子进程监督")
    if os.getppid() != manifest["daemonPid"]:
        cancellation[0] = True
    account = pwd.getpwnam(USER)
    work = directory / "work"
    work.mkdir(mode=0o700)
    os.chown(work, account.pw_uid, account.pw_gid)
    temporary = work / "tmp"
    temporary.mkdir(mode=0o700)
    os.chown(temporary, account.pw_uid, account.pw_gid)
    environment = {"JAVA_HOME": "/opt/java17", "PATH": "/opt/java17/bin:/usr/bin:/bin",
                   "HOME": account.pw_dir, "USER": USER, "LOGNAME": USER, "LANG": "C.UTF-8",
                   "TZ": "Asia/Shanghai", "TMPDIR": str(temporary), "FLINK_HOME": "/opt/flink",
                   "SPARK_HOME": "/opt/spark", "SPARK_HOME1": "/opt/spark", "SPARK_HOME2": "/opt/spark",
                   "SPARK_LOCAL_IP": "127.0.0.1"}

    def lower_identity():
        os.setgroups([])
        os.setgid(account.pw_gid)
        os.setuid(account.pw_uid)

    try:
        with os.fdopen(os.open(directory / "output.log", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb", buffering=0) as output:
            child = subprocess.Popen([ENGINE_PATHS[manifest["engine"]], *manifest["arguments"]],
                                     cwd=work, env=environment, preexec_fn=lower_identity,
                                     start_new_session=True, stdout=output, stderr=subprocess.STDOUT)
            atomic_json(result_path, state_for(manifest, state="RUNNING", started=True, processTreeAlive=True))
            primary_exit = None
            cancel_started = None
            while True:
                has_children = False
                while True:
                    try:
                        pid, status = os.waitpid(-1, os.WNOHANG)
                    except ChildProcessError:
                        break
                    if pid == 0:
                        has_children = True
                        break
                    if pid == child.pid:
                        primary_exit = os.waitstatus_to_exitcode(status)
                        child.returncode = primary_exit
                if not has_children and primary_exit is not None:
                    break
                if cancellation[0]:
                    if cancel_started is None:
                        cancel_started = time.monotonic()
                    signum = signal.SIGKILL if time.monotonic() - cancel_started >= 5 else signal.SIGTERM
                    signal_descendants(os.getpid(), signum)
                    signal_sent = True
                time.sleep(0.05)
            state = "CANCELLED" if signal_sent else ("SUCCEEDED" if primary_exit == 0 else "FAILED")
            atomic_json(result_path, state_for(manifest, state=state, exitCode=primary_exit,
                                               started=True, finished=True, processTreeAlive=False,
                                               supervisorFinished=True))
    except Exception:
        signal_descendants(os.getpid(), signal.SIGKILL)
        while True:
            try:
                os.waitpid(-1, 0)
            except ChildProcessError:
                break
        atomic_json(result_path, state_for(manifest, state="UNKNOWN", supervisorFinished=True,
                                           launcherExitCode=127))


class Runtime:
    def __init__(self, directory):
        self.directory = directory
        self.lock = threading.Lock()
        self.processes = {}

    def audit_rejection(self, request, uid, error):
        if not isinstance(request, dict) or not NAME.fullmatch(str(request.get("name", ""))) or request.get("engine") not in ENGINE_PATHS:
            return
        evidence = {"event": "LAUNCH_REQUEST_REJECTED", "name": request["name"],
                    "user": USER, "peerUid": uid, "engine": request["engine"],
                    "observedAtUnixNs": time.time_ns(), "accepted": False,
                    "started": False, "reasonCode": getattr(error, "code", "INVALID_REQUEST"),
                    "requestSha256": hashlib.sha256(json.dumps(request, sort_keys=True,
                                                               separators=(",", ":")).encode()).hexdigest()}
        with self.lock:
            directory = self.directory / "rejections"
            directory.mkdir(mode=0o700, exist_ok=True)
            descriptor = os.open(directory / (request["name"] + ".jsonl"),
                                 os.O_WRONLY | os.O_APPEND | os.O_CREAT | os.O_NOFOLLOW, 0o600)
            with os.fdopen(descriptor, "w") as output:
                output.write(json.dumps(evidence, separators=(",", ":")) + "\n")
                output.flush()
                os.fsync(output.fileno())

    def launch(self, request):
        sources = validate_request(request)
        with self.lock:
            for process in self.processes.values():
                if process.poll() is None:
                    raise ValueError("本地引擎正在执行，请串行提交")
            target = self.directory / request["name"]
            target.mkdir(mode=0o750)
            os.chown(target, 0, grp.getgrnam(USER).gr_gid)
            manifest = {**request, "daemonPid": os.getpid()}
            arguments = list(request["arguments"])
            contents = []
            for ordinal, (argument_index, source) in enumerate(sources):
                descriptor = os.open(source, os.O_RDONLY | os.O_NOFOLLOW)
                with os.fdopen(descriptor, "rb") as stream:
                    information = os.fstat(stream.fileno())
                    if information.st_uid != pwd.getpwnam(USER).pw_uid or not stat.S_ISREG(information.st_mode):
                        raise ValueError("SQL 文件身份发生变化")
                    content = stream.read(1024 * 1024 + 1)
                if len(content) > 1024 * 1024:
                    raise ValueError("SQL 文件超出限制")
                contents.append(content)
                script = target / ("input-" + str(ordinal) + ".sql")
                script.write_bytes(content)
                script.chmod(0o440)
                os.chown(script, 0, grp.getgrnam(USER).gr_gid)
                arguments[argument_index] = str(script)
            if request["engine"] == "FLINK":
                names = re.findall(r"(?im)\bSET\s+['\"]pipeline\.name['\"]\s*=\s*['\"]([^'\"]+)['\"]\s*;", b"\n".join(contents).decode("utf-8"))
                if not names or set(names) != {request["name"]}:
                    raise ValueError("Flink SQL 执行标识不匹配")
            manifest["arguments"] = arguments
            manifest["sourceSha256"] = hashlib.sha256(b"\n".join(contents)).hexdigest()
            atomic_json(target / "manifest.json", manifest)
            atomic_json(target / "state.json", state_for(manifest))
            process = subprocess.Popen([sys.executable, __file__, "--supervise", str(target)], start_new_session=True)
            self.processes[request["name"]] = process
            return target, process

    def query(self, name):
        path = self.directory / name / "state.json"
        if not path.exists():
            return []
        result = json.loads(path.read_text())
        return [{key: result[key] for key in PUBLIC_KEYS}]


class LaunchHandler(socketserver.StreamRequestHandler):
    def handle(self):
        credentials = self.request.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12)
        uid = int.from_bytes(credentials[4:8], sys.byteorder)
        if uid != pwd.getpwnam(USER).pw_uid:
            return
        process = None
        request = None
        try:
            line = self.rfile.readline(16385)
            if len(line) > 16384:
                raise ValueError("请求过大")
            request = json.loads(line)
            directory, process = self.server.runtime.launch(request)
            self.send({"type": "accepted"})
            position = 0
            while True:
                log = directory / "output.log"
                if log.exists():
                    with log.open("rb") as stream:
                        stream.seek(position)
                        chunk = stream.read(16384)
                        position += len(chunk)
                    if chunk:
                        self.send({"type": "output", "data": base64.b64encode(chunk).decode()})
                        continue
                state = json.loads((directory / "state.json").read_text())
                if state.get("supervisorFinished"):
                    self.send({"type": "result", "exitCode": state.get("exitCode", state.get("launcherExitCode", 127)),
                               "state": state["state"]})
                    return
                if process.poll() is not None:
                    self.send({"type": "result", "exitCode": 127, "state": "UNKNOWN"})
                    return
                ready, _, _ = select.select([self.request], [], [], 0.1)
                if ready:
                    control = self.rfile.readline(1025)
                    if not control or json.loads(control) != {"operation": "cancel"}:
                        self.cancel(process)
                        return
                    self.cancel(process)
        except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
            if process is not None:
                self.cancel(process)
            else:
                self.server.runtime.audit_rejection(request, uid, error)
            try:
                self.send({"type": "error", "message": "受管本地引擎请求失败"})
            except OSError:
                pass

    def send(self, value):
        self.wfile.write(json.dumps(value, separators=(",", ":")).encode() + b"\n")
        self.wfile.flush()

    @staticmethod
    def cancel(process):
        if process.poll() is None:
            process.send_signal(signal.SIGTERM)


class UnixServer(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True


class QueryHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(parsed.query)
        name = params.get("name", [""])
        user = params.get("user", [""])
        if parsed.path != "/v1/executions" or set(params) != {"name", "user"} or len(name) != 1 or len(user) != 1 or not NAME.fullmatch(name[0]) or user[0] != USER:
            self.respond(400, {"error": "invalid_query"})
            return
        self.respond(200, {"executions": self.server.runtime.query(name[0])})

    def respond(self, status, value):
        payload = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--supervise", type=Path)
    parser.add_argument("--state-dir", type=Path, default=Path("/var/lib/datahub-native-runtime"))
    parser.add_argument("--socket", default="/run/datahub-native-runtime/launch.sock")
    parser.add_argument("--tls-dir", type=Path, default=Path("/opt/datahub-native-runtime/tls"))
    parser.add_argument("--port", type=int, default=19101)
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit("监督进程必须以 root 运行")
    if args.supervise:
        supervise(args.supervise)
        return
    args.state_dir.mkdir(parents=True, exist_ok=True, mode=0o750)
    os.chmod(args.state_dir, 0o750)
    os.chown(args.state_dir, 0, grp.getgrnam(USER).gr_gid)
    runtime = Runtime(args.state_dir)
    socket_path = Path(args.socket)
    socket_path.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
    if socket_path.exists():
        raise SystemExit("受管套接字已存在，拒绝覆盖")
    unix = UnixServer(args.socket, LaunchHandler)
    unix.runtime = runtime
    os.chown(args.socket, 0, grp.getgrnam(USER).gr_gid)
    os.chmod(args.socket, 0o660)
    threading.Thread(target=unix.serve_forever, daemon=True).start()
    server = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), QueryHandler)
    server.runtime = runtime
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.tls_dir / "server.pem", args.tls_dir / "server-key.pem")
    context.load_verify_locations(args.tls_dir / "ca.pem")
    context.verify_mode = ssl.CERT_REQUIRED
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
