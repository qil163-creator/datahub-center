"""Export the fixed ETL worker's physical classpath from a trusted Spring Boot release.

Creates a NEW directory; never expands application classes or arbitrary ZIP paths.
The result is administrator configuration, not a user-supplied pipeline option.
"""
import argparse
import hashlib
import io
import json
import pathlib
import re
import zipfile


LIBRARIES = {
    "yudao-spring-boot-starter-biz-etl": (
        "cn/iocoder/yudao/framework/etl/core/script/EtlScriptWorker.class",
        "cn/iocoder/yudao/framework/etl/core/script/EtlScriptProtocol.class",
        "cn/iocoder/yudao/framework/etl/core/script/EtlJavaCompiler.class",
        "cn/iocoder/yudao/framework/etl/core/script/EtlJavaScript.class",
    ),
    "yudao-dbswitch-common": (
        "cn/iocoder/yudao/framework/dbswitch/common/util/BoundedRowCodec.class",
    ),
    "hutool-all": ("cn/hutool/json/JSONUtil.class",),
    "nashorn-core": ("org/openjdk/nashorn/api/scripting/NashornScriptEngineFactory.class",),
    "asm": ("org/objectweb/asm/ClassReader.class",),
    "asm-commons": ("org/objectweb/asm/commons/InstructionAdapter.class",),
    "asm-tree": ("org/objectweb/asm/tree/ClassNode.class",),
    "asm-analysis": ("org/objectweb/asm/tree/analysis/Analyzer.class",),
    "asm-util": ("org/objectweb/asm/util/Printer.class",),
}
MAX_JAR_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024


def sha256_file(path: pathlib.Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def export(source: pathlib.Path, destination: pathlib.Path) -> dict:
    source = source.resolve(strict=True)
    destination = destination.resolve()
    if not source.is_file() or destination.exists():
        raise ValueError("Use a trusted release JAR and a new output directory")
    source_hash = sha256_file(source)
    payloads = []
    total = 0
    with zipfile.ZipFile(source) as release:
        names = release.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate entries in release archive")
        for artifact, required_classes in LIBRARIES.items():
            pattern = re.compile(r"BOOT-INF/lib/" + re.escape(artifact)
                                 + r"-[0-9][A-Za-z0-9._+-]*\.jar\Z")
            matches = [name for name in names if pattern.fullmatch(name)]
            if len(matches) != 1:
                raise ValueError(f"Expected exactly one release dependency: {artifact}")
            entry = release.getinfo(matches[0])
            total += entry.file_size
            if entry.file_size > MAX_JAR_BYTES or total > MAX_TOTAL_BYTES:
                raise ValueError("Worker dependencies exceed export budget")
            payload = release.read(entry)
            with zipfile.ZipFile(io.BytesIO(payload)) as library:
                entries = library.namelist()
                if len(entries) != len(set(entries)):
                    raise ValueError(f"Duplicate entries in dependency: {artifact}")
                if any(name not in entries for name in required_classes):
                    raise ValueError(f"Missing fixed worker class in: {artifact}")
                manifests = [name for name in entries if name.lower() == "meta-inf/manifest.mf"]
                if len(manifests) > 1:
                    raise ValueError(f"Ambiguous dependency manifest: {artifact}")
                if manifests:
                    manifest = manifests[0]
                    if library.getinfo(manifest).file_size > 64 * 1024:
                        raise ValueError("Oversized dependency manifest")
                    normalized = library.read(manifest).replace(b"\r\n", b"\n").replace(b"\r", b"\n").lower()
                    if any(line.startswith(b"class-path:") for line in normalized.split(b"\n")):
                        raise ValueError(f"External manifest classpath in: {artifact}")
            payloads.append((pathlib.PurePosixPath(entry.filename).name, payload))
    if sha256_file(source) != source_hash:
        raise ValueError("Release JAR changed during export")
    # 创建输出前先验证全部依赖，不复用已有目录。
    destination.mkdir(parents=False, exist_ok=False)
    artifacts = []
    for name, payload in payloads:
        path = destination / name
        with path.open("xb") as output:
            output.write(payload)
        artifacts.append({"file": name, "sha256": hashlib.sha256(payload).hexdigest(),
                          "bytes": len(payload)})
    result = {"formatVersion": 1, "sourceSha256": source_hash, "artifacts": artifacts,
              "classpathEntries": [str(destination / name) for name, _ in payloads]}
    with (destination / "worker-classpath.json").open("x", encoding="utf-8") as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write("\n")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=pathlib.Path)
    parser.add_argument("destination", type=pathlib.Path)
    args = parser.parse_args()
    result = export(args.source, args.destination)
    print(f"Exported {len(result['artifacts'])} worker dependencies; release SHA-256: "
          f"{result['sourceSha256']}")
    print(f"Classpath manifest: {args.destination / 'worker-classpath.json'}")
