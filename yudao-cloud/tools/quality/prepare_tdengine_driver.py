"""Build a pinned standalone TDengine 3.9.0 driver from the official dist artifact.

The official assembly embeds an obsolete netty-all JAR index referring to an external
file, although the dependency classes are included. Remove only that verified index
in a NEW artifact; never weaken ConfiguredJdbcDrivers or overwrite the vendor JAR.
All classes, licenses and other resources are preserved byte-for-byte.
"""
import argparse
import hashlib
import pathlib
import zipfile

SOURCE_SHA256 = "456f080303883e6b0c754c1f854e610023f46c506e86e6208d12571e7336612c"
EXPECTED_INDEX = ("JarIndex-Version: 1.0\n\nnetty-all-4.2.15.Final.jar\nMETA-INF\n"
                  "META-INF/maven\nMETA-INF/maven/io.netty\nMETA-INF/maven/io.netty/netty-all\n\n")


def prepare(source: pathlib.Path, destination: pathlib.Path) -> str:
    source = source.resolve(strict=True)
    destination = destination.resolve()
    if source == destination or destination.exists():
        raise ValueError("Use a new output path; existing files are never overwritten")
    if hashlib.sha256(source.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError("Expected the official, pinned taos-jdbcdriver-3.9.0-dist.jar")
    with zipfile.ZipFile(source) as original:
        if original.read("META-INF/INDEX.LIST").decode("ascii").replace("\r\n", "\n") != EXPECTED_INDEX:
            raise ValueError("Unexpected vendor index")
        if b"Class-Path:" in original.read("META-INF/MANIFEST.MF"):
            raise ValueError("Unexpected external manifest dependencies")
        for entry in ("com/taosdata/jdbc/rs/RestfulDriver.class", "io/netty/bootstrap/Bootstrap.class",
                      "org/apache/http/impl/client/HttpClientBuilder.class",
                      "com/fasterxml/jackson/databind/ObjectMapper.class"):
            original.getinfo(entry)
        if any(name.upper().endswith((".SF", ".RSA", ".DSA")) for name in original.namelist()):
            raise ValueError("Signed archives must not be repackaged")
        with zipfile.ZipFile(destination, "x") as output:
            for item in original.infolist():
                if item.filename != "META-INF/INDEX.LIST":
                    output.writestr(item, original.read(item))
    return hashlib.sha256(destination.read_bytes()).hexdigest()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=pathlib.Path)
    parser.add_argument("destination", type=pathlib.Path)
    args = parser.parse_args()
    print("Standalone driver SHA-256:", prepare(args.source, args.destination))
