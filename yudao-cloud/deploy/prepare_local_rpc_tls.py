#!/usr/bin/env python3
"""为本机两个服务生成互信的专用 TLS 证书与 Spring 配置。"""

import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile


def run(*args):
    return subprocess.check_output(["openssl", *map(str, args)], stderr=subprocess.PIPE)


def issue(directory, name, usage):
    key, csr, crt = (directory / f"{name}.{suffix}" for suffix in ("key", "csr", "crt"))
    extension = directory / f"{name}.ext"
    extension.write_text("basicConstraints=critical,CA:FALSE\n"
                         "keyUsage=critical,digitalSignature,keyEncipherment\n"
                         f"extendedKeyUsage={usage}\n" +
                         ("subjectAltName=IP:127.0.0.1\n" if usage == "serverAuth" else ""))
    run("req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", f"/CN=DataHub local {name}",
        "-keyout", key, "-out", csr)
    run("x509", "-req", "-sha256", "-days", "365", "-in", csr, "-signkey", key,
        "-extfile", extension, "-out", crt)
    csr.unlink()
    extension.unlink()


def bundle(directory, name, peer, peer_directory=None):
    prefix = f"spring.ssl.bundle.pem.local-{name}"
    return [f"{prefix}.keystore.certificate={(directory / (name + '.crt')).as_uri()}",
            f"{prefix}.keystore.private-key={(directory / (name + '.key')).as_uri()}",
            f"{prefix}.truststore.certificate={((peer_directory or directory) / (peer + '.crt')).as_uri()}"]


def prepare(args):
    os.umask(0o077)
    directory = args.directory.expanduser().resolve()
    shared = args.share_server_with.expanduser().resolve() if args.share_server_with else None
    server_directory = shared or directory
    if shared:
        if shared == directory:
            raise SystemExit("客户端配置目录必须独立于现有服务端目录")
        if not all((shared / filename).is_file() for filename in
                   (args.server + ".crt", args.server + ".key", args.trusted_client + ".crt")):
            raise SystemExit("请先生成已有服务端和既有客户端的证书")
        properties = shared / f"{args.server}.properties"
        if not properties.is_file():
            raise SystemExit(f"找不到已有监听器配置：{properties}")
        ports = re.findall(r"^yudao\.web\.local-rpc-tls\.port=(\d+)$",
                           properties.read_text(), re.MULTILINE)
        if len(ports) != 1 or int(ports[0]) != args.port:
            raise SystemExit(f"--port 必须与 {properties} 中已有监听器的端口一致")
    directory.parent.mkdir(parents=True, exist_ok=True)
    if not directory.exists():
        with tempfile.TemporaryDirectory(dir=directory.parent) as staging:
            pending = Path(staging) / "certs"
            pending.mkdir(mode=0o700)
            issue(pending, args.client, "clientAuth")
            if not shared:
                issue(pending, args.server, "serverAuth")
            pending.rename(directory)
    if directory.stat().st_mode & 0o077:
        raise SystemExit(f"证书目录权限必须为 0700：{directory}")
    for name, source in [(args.client, directory), (args.server, server_directory)]:
        cert = source / f"{name}.crt"
        key = source / f"{name}.key"
        if key.stat().st_mode & 0o077:
            raise SystemExit(f"私钥权限必须为 0600：{key}")
        run("x509", "-in", cert, "-checkend", "3600", "-noout")
        if run("pkey", "-in", key, "-pubout") != run("x509", "-in", cert, "-pubkey", "-noout"):
            raise SystemExit(f"证书和私钥不匹配：{name}")
    fingerprint = hashlib.sha256(run("x509", "-in", directory / f"{args.client}.crt",
                                     "-outform", "DER")).hexdigest()
    client = bundle(directory, args.client, args.server, server_directory) + [
        f"{args.bundle_property}=local-{args.client}",
        f"spring.cloud.openfeign.client.config.{args.context}.url=https://127.0.0.1:{args.port}"]
    if shared:
        # 同一开发监听器可用于多个 RPC，业务端仍逐一验证角色与证书指纹。
        with tempfile.NamedTemporaryFile(dir=directory, delete=False) as out:
            for certificate in (shared / (args.trusted_client + ".crt"), directory / (args.client + ".crt")):
                out.write(certificate.read_bytes().strip() + b"\n")
        trust = directory / "trusted-clients.pem"
        Path(out.name).replace(trust)
        server = [f"spring.ssl.bundle.pem.local-{args.server}.truststore.certificate={trust.as_uri()}"]
    else:
        server = bundle(directory, args.server, args.client) + [
            f"yudao.web.local-rpc-tls.port={args.port}", f"yudao.web.local-rpc-tls.ssl-bundle=local-{args.server}"]
    server.append(f"{args.peer_property}[0]={fingerprint}")
    for name, lines in [(args.client, client), (args.server, server)]:
        with tempfile.NamedTemporaryFile(dir=directory, mode="w", delete=False) as output:
            output.write("\n".join(lines) + "\n")
        target = directory / f"{name}.properties"
        Path(output.name).replace(target)
        print(target)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--client", required=True)
    parser.add_argument("--server", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--context", required=True)
    parser.add_argument("--bundle-property", required=True)
    parser.add_argument("--peer-property", required=True)
    parser.add_argument("--share-server-with", type=Path, help="使用已有本机监听器的证书目录")
    parser.add_argument("--trusted-client", help="现有监听器已信任的客户端名称")
    options = parser.parse_args()
    if bool(options.share_server_with) != bool(options.trusted_client):
        parser.error("--share-server-with 和 --trusted-client 必须配套使用")
    if options.trusted_client and (not re.fullmatch(r"[a-z][a-z0-9-]*", options.trusted_client)
                                   or options.trusted_client in (options.client, options.server)):
        parser.error("现有客户端必须为不同的小写服务名称")
    if options.client == options.server or not all(re.fullmatch(r"[a-z][a-z0-9-]*", name)
                                                  for name in (options.client, options.server)):
        parser.error("客户端、服务端必须使用不同的小写服务名称")
    if not 1 <= options.port <= 65535:
        parser.error("无效端口")
    prepare(options)
