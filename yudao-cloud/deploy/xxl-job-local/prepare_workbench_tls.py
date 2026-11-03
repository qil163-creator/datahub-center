#!/usr/bin/env python3
"""准备规则工作台 Governance → Asset 的本机双向 TLS，不修改共享配置或系统信任库。"""

import hashlib
import json
import os
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parent
# 保留已有目录和证书身份，升级脚本不轮换客户端 pin。
TLS = ROOT / "runtime" / "sync-tls"


def run(*args):
    subprocess.run(args, cwd=TLS, check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.PIPE)


def pem_bundle(name, identity):
    return {
        "spring": {"ssl": {"bundle": {"pem": {name: {
            "keystore": {
                "certificate": (TLS / f"{identity}.crt").as_uri(),
                "private-key": (TLS / f"{identity}.key").as_uri(),
            },
            "truststore": {"certificate": (TLS / "ca.crt").as_uri()},
        }}}}}
    }


def identity_exists(identity):
    paths = [TLS / f"{identity}.{suffix}" for suffix in ("key", "crt")]
    if any(path.exists() for path in paths) and not all(path.exists() for path in paths):
        raise SystemExit(f"{identity} 证书材料不完整；不会自动覆盖已有密钥。")
    return all(path.exists() for path in paths)


def ensure_identity(identity, usage):
    if identity_exists(identity):
        return
    ext = TLS / f"{identity}.ext"
    ext.write_text("basicConstraints=critical,CA:FALSE\n"
                   "keyUsage=critical,digitalSignature,keyEncipherment\n"
                   f"extendedKeyUsage={usage}\n"
                   + ("subjectAltName=DNS:localhost,IP:127.0.0.1\n" if identity == "server" else ""))
    run("openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes",
        "-subj", f"/CN=DataHub local {identity}", "-keyout", f"{identity}.key",
        "-out", f"{identity}.csr")
    run("openssl", "x509", "-req", "-sha256", "-days", "30",
        "-in", f"{identity}.csr", "-CA", "ca.crt", "-CAkey", "ca.key",
        "-CAcreateserial", "-extfile", ext.name, "-out", f"{identity}.crt")


def check_identity(identity):
    certificate = TLS / f"{identity}.crt"
    run("openssl", "verify", "-CAfile", "ca.crt", certificate.name)
    run("openssl", "x509", "-checkend", "3600", "-noout", "-in", certificate.name)
    public_key = subprocess.check_output(
        ["openssl", "pkey", "-in", str(TLS / f"{identity}.key"), "-pubout"], stderr=subprocess.PIPE)
    certified_key = subprocess.check_output(
        ["openssl", "x509", "-in", str(certificate), "-pubkey", "-noout"], stderr=subprocess.PIPE)
    if public_key != certified_key:
        raise SystemExit(f"{identity} 的证书与私钥不匹配；不会自动覆盖材料。")


def fingerprint(identity):
    der = subprocess.check_output(["openssl", "x509", "-in", str(TLS / f"{identity}.crt"),
                                   "-outform", "DER"])
    return hashlib.sha256(der).hexdigest()


def main():
    os.umask(0o077)
    TLS.mkdir(parents=True, exist_ok=True, mode=0o700)
    ca_exists = identity_exists("ca")
    server_exists = identity_exists("server")
    client_exists = identity_exists("governance")
    if not ca_exists:
        if server_exists or client_exists:
            raise SystemExit("已有服务证书但缺少 CA；不会创建替代 CA 或覆盖现有身份。")
        run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256",
            "-days", "30", "-subj", "/CN=DataHub local workbench CA",
            "-keyout", "ca.key", "-out", "ca.crt")
    check_identity("ca")
    for identity, usage in (("server", "serverAuth"), ("governance", "clientAuth")):
        ensure_identity(identity, usage)
        check_identity(identity)

    client = pem_bundle("localassetclient", "governance")
    client["spring"]["cloud"] = {"openfeign": {"client": {"config": {
        "assetSensitivePolicyApi": {"url": "https://localhost:55403"}}}}}
    client["yudao"] = {"asset": {"sensitive-policy": {"client": {
        "ssl-bundle": "localassetclient"}}}}

    server = pem_bundle("localassetserver", "server")
    server["server"] = {"address": "127.0.0.1", "port": 55403,
                        "ssl": {"enabled": True, "bundle": "localassetserver", "client-auth": "need"}}
    server["spring"]["cloud"] = {"nacos": {"discovery": {"register-enabled": False}}}
    server["xxl"] = {"job": {"enabled": False}}
    server["yudao"] = {"asset": {"sensitive-policy": {"rpc": {"peers": {
        "governance": [fingerprint("governance")]}}}}}
    server["logging"] = {"file": {"name": str(TLS / "asset-tls.log")}}

    # JSON 是 YAML 的子集，配置仍由 Spring 标准加载器读取。
    for name, value in (("governance-client-tls.yaml", client), ("asset-tls.yaml", server)):
        path = TLS / name
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
        path.chmod(0o600)
    print("已准备规则工作台 TLS 配置；新证书有效期 30 天，现有证书及 pin 保持不变。")
    print("Governance 客户端配置：", TLS / "governance-client-tls.yaml")
    print("Asset 服务端配置：", TLS / "asset-tls.yaml")


if __name__ == "__main__":
    main()
