#!/usr/bin/env python3
"""为正在运行的本地 Worker 安装受管 SQL 监督器，不重启 DolphinScheduler。"""

import argparse
import json
import os
from pathlib import Path
import subprocess
import ssl
import urllib.request
import sys
import time

ROOT = Path(__file__).resolve().parent


def run(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def certificates(destination):
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(destination, 0o700)
    if (destination / "client-key.pem").exists():
        for name in ("ca.pem", "server.pem", "server-key.pem", "client.pem", "client-key.pem"):
            if not (destination / name).is_file():
                raise RuntimeError("TLS 文件不完整，拒绝覆盖现有材料")
        return
    run("openssl", "req", "-x509", "-newkey", "rsa:3072", "-nodes", "-days", "365", "-subj",
        "/CN=DataHub Native Local CA", "-keyout", str(destination / "ca-key.pem"), "-out", str(destination / "ca.pem"))
    for name, usage in (("server", "serverAuth"), ("client", "clientAuth")):
        run("openssl", "req", "-newkey", "rsa:3072", "-nodes", "-subj", "/CN=datahub-native-" + name,
            "-keyout", str(destination / (name + "-key.pem")), "-out", str(destination / (name + ".csr")))
        configuration = destination / (name + ".ext")
        configuration.write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=" + usage + "\n" +
                                 ("subjectAltName=IP:127.0.0.1,DNS:localhost\n" if name == "server" else ""))
        run("openssl", "x509", "-req", "-in", str(destination / (name + ".csr")), "-CA", str(destination / "ca.pem"),
            "-CAkey", str(destination / "ca-key.pem"), "-CAcreateserial", "-days", "365", "-extfile", str(configuration),
            "-out", str(destination / (name + ".pem")))
    for path in destination.iterdir():
        path.chmod(0o600)


def install(container, tls):
    execute = ["docker", "exec", "-u", "0", container]
    run(*execute, "mkdir", "-p", "/opt/datahub-native-runtime/tls")
    for name in ("local_runtime_observer.py", "local_runtime_cli.py"):
        run("docker", "cp", str(ROOT / "native-runtime" / name), container + ":/opt/datahub-native-runtime/" + name)
    for name in ("ca.pem", "server.pem", "server-key.pem"):
        run("docker", "cp", str(tls / name), container + ":/opt/datahub-native-runtime/tls/" + name)
    installer = '''import os,pathlib
base=pathlib.Path('/opt/datahub-native-runtime')
for path in base.rglob('*'):
 os.chown(path,0,0)
 if path.is_file(): path.chmod(0o600 if path.name.endswith('-key.pem') else 0o644)
for engine,target,original in [('FLINK','/opt/flink/bin/sql-client.sh','/opt/flink/bin/sql-client-managed-original.sh'),('SPARK','/opt/spark/bin/spark-sql','/opt/spark/bin/spark-sql-managed-original')]:
 target=pathlib.Path(target); original=pathlib.Path(original)
 if not original.exists(): os.rename(target,original)
 elif 'local_runtime_cli.py' not in target.read_text(): raise RuntimeError('已有入口发生变化，拒绝覆盖')
 target.write_text('#!/usr/bin/env bash\\nexec /usr/bin/python3 /opt/datahub-native-runtime/local_runtime_cli.py '+engine+' "$@"\\n')
 target.chmod(0o755)
'''
    run(*execute, "python3", "-c", installer)
    exists = subprocess.run([*execute, "test", "-S", "/run/datahub-native-runtime/launch.sock"], check=False,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not exists:
        run("docker", "exec", "-d", "-u", "0", container, "python3", "/opt/datahub-native-runtime/local_runtime_observer.py")
    for _ in range(50):
        result = subprocess.run([*execute, "test", "-S", "/run/datahub-native-runtime/launch.sock"], check=False,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode == 0:
            return
        time.sleep(0.1)
    raise RuntimeError("受管监督器未就绪")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="datahub-ds-local-standalone-1")
    parser.add_argument("--tls-dir", type=Path, default=ROOT / "runtime" / "native-tls")
    parser.add_argument("--connection", type=Path, default=Path("/tmp/datahub-native-runtime-connection.json"))
    args = parser.parse_args()
    certificates(args.tls_dir)
    install(args.container, args.tls_dir)
    context = ssl.create_default_context(cafile=args.tls_dir / "ca.pem")
    context.load_cert_chain(args.tls_dir / "client.pem", args.tls_dir / "client-key.pem")

    def healthy():
        try:
            with urllib.request.urlopen("https://127.0.0.1:19101/v1/executions?name=dh_native_990000000001&user=datahub", context=context, timeout=2) as response:
                return isinstance(json.load(response).get("executions"), list)
        except Exception:
            return False

    forward_pid = None
    if not healthy():
        forward_log = Path("/tmp/datahub-native-runtime-forward.log")
        fd = os.open(forward_log, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, "ab") as output:
            forward = subprocess.Popen([sys.executable, str(ROOT / "native-runtime" / "local_runtime_forward.py"),
                                        "--container", args.container], stdin=subprocess.DEVNULL, stdout=output,
                                       stderr=output, start_new_session=True)
        forward_pid = forward.pid
        for _ in range(50):
            if healthy():
                break
            if forward.poll() is not None:
                raise RuntimeError("环回 TLS 转发器启动失败")
            time.sleep(.1)
        else:
            raise RuntimeError("环回 TLS 转发器尚未就绪")
    elif args.connection.exists():
        forward_pid = json.loads(args.connection.read_text()).get("forwardPid")
    report = {"serverUrl": "https://127.0.0.1:19101", "caCertificate": str(args.tls_dir / "ca.pem"),
              "clientCertificate": str(args.tls_dir / "client.pem"), "clientKey": str(args.tls_dir / "client-key.pem"),
              "container": args.container, "forwardPid": forward_pid}
    args.connection.write_text(json.dumps(report, indent=2) + "\n")
    args.connection.chmod(0o600)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
