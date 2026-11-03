#!/usr/bin/env python3
"""生成仅供本机文件字段验收的短期双向TLS材料；私钥和密码只写0600文件。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess


def run(*args):
    subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--private-directory", default="/tmp/datahub-file-schema-tls")
    parser.add_argument("--base-properties", default="/tmp/datahub-integration-private.properties")
    args = parser.parse_args()
    os.umask(0o077)
    folder = Path(args.private_directory).resolve()
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    manifest = folder / "paths.json"
    if manifest.exists():
        print(json.dumps({"privateDirectory": str(folder), "status": "existing; certificate not rotated"}))
        return
    password = secrets.token_hex(24)
    (folder / "password").write_text(password)
    run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "7", "-subj", "/CN=DataHub local file schema CA", "-keyout", str(folder / "ca.key"), "-out", str(folder / "ca.crt"))
    for name, subject, extension in [("server", "localhost", "subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n"),
                                     ("client", "dataforge-file-schema", "extendedKeyUsage=clientAuth\n")]:
        (folder / f"{name}.ext").write_text(extension)
        run("openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=" + subject,
            "-keyout", str(folder / f"{name}.key"), "-out", str(folder / f"{name}.csr"))
        run("openssl", "x509", "-req", "-in", str(folder / f"{name}.csr"), "-CA", str(folder / "ca.crt"), "-CAkey", str(folder / "ca.key"),
            "-CAcreateserial", "-days", "7", "-extfile", str(folder / f"{name}.ext"), "-out", str(folder / f"{name}.crt"))
        run("openssl", "pkcs12", "-export", "-name", name, "-inkey", str(folder / f"{name}.key"), "-in", str(folder / f"{name}.crt"),
            "-certfile", str(folder / "ca.crt"), "-out", str(folder / f"{name}.p12"), "-passout", "file:" + str(folder / "password"))
    run("keytool", "-importcert", "-noprompt", "-alias", "local-ca", "-file", str(folder / "ca.crt"), "-keystore", str(folder / "trust.p12"),
        "-storetype", "PKCS12", "-storepass:file", str(folder / "password"))
    certificate = subprocess.check_output(["openssl", "x509", "-in", str(folder / "client.crt"), "-outform", "DER"])
    fingerprint = hashlib.sha256(certificate).hexdigest()
    base = Path(args.base_properties).read_text().splitlines()
    selected = [line for line in base if line.startswith(("spring.datasource.", "spring.data.redis.", "spring.cloud.openfeign.client.config.system-server."))]
    common = ["spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.discovery.register-enabled=false", "spring.cloud.nacos.config.enabled=false",
              "xxl.job.enabled=false", "spring.boot.admin.client.enabled=false"]
    server = selected + common + ["server.port=48085", "server.address=127.0.0.1", "server.ssl.enabled=true", "server.ssl.client-auth=need",
        f"server.ssl.key-store=file:{folder}/server.p12", "server.ssl.key-store-type=PKCS12", f"server.ssl.key-store-password={password}",
        f"server.ssl.trust-store=file:{folder}/trust.p12", "server.ssl.trust-store-type=PKCS12", f"server.ssl.trust-store-password={password}",
        f"yudao.rpc.peers.dataforge[0]={fingerprint}", "logging.file.name=/tmp/datahub-file-schema-infra.log"]
    (folder / "infra.properties").write_text("\n".join(server) + "\n")
    client = ["spring.cloud.openfeign.client.config.managedFileApi.url=https://localhost:48085", "yudao.rpc.client.ssl-bundle=file-schema-client",
        f"spring.ssl.bundle.jks.file-schema-client.keystore.location=file:{folder}/client.p12",
        f"spring.ssl.bundle.jks.file-schema-client.keystore.password={password}", "spring.ssl.bundle.jks.file-schema-client.keystore.type=PKCS12",
        f"spring.ssl.bundle.jks.file-schema-client.truststore.location=file:{folder}/trust.p12",
        f"spring.ssl.bundle.jks.file-schema-client.truststore.password={password}", "spring.ssl.bundle.jks.file-schema-client.truststore.type=PKCS12"]
    (folder / "dataforge.properties").write_text("\n".join(client) + "\n")
    manifest.write_text(json.dumps({"infra": str(folder / "infra.properties"), "dataforge": str(folder / "dataforge.properties"),
                                  "ca": str(folder / "ca.crt"), "clientCertificate": str(folder / "client.crt"), "clientKey": str(folder / "client.key")}, indent=2))
    print(json.dumps({"privateDirectory": str(folder), "manifest": str(manifest), "status": "created"}))


if __name__ == "__main__":
    main()
