#!/usr/bin/env python3
"""Resolve immutable inputs, verify the unchanged baseline, and build the comparison."""
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
PINS = json.loads((ROOT / "pins.json").read_text())


def run(*args, cwd=ROOT):
    subprocess.run(args, cwd=cwd, check=True)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare():
    inputs, artifacts = ROOT / ".inputs", ROOT / "artifacts"
    artifacts.mkdir(exist_ok=True)
    revision = PINS["candidateRevision"]
    if subprocess.run(["git", "cat-file", "-e", revision], cwd=REPO, capture_output=True).returncode:
        run("git", "fetch", "--no-tags", "https://github.com/teamziax/NetworkCompatible.git", revision, cwd=REPO)
    if not inputs.exists():
        inputs.mkdir()
        archive = artifacts / "candidate.tar"
        with archive.open("wb") as stream:
            subprocess.run(["git", "archive", revision], cwd=REPO, stdout=stream, check=True)
        with tarfile.open(archive) as tar:
            tar.extractall(inputs, filter="data")
    # Every original candidate file must still match its pinned Git blob.
    files = subprocess.check_output(["git", "ls-tree", "-rz", revision], cwd=REPO).split(b"\0")
    for entry in filter(None, files):
        metadata, name = entry.split(b"\t", 1)
        mode, kind, blob = metadata.split()
        if kind != b"blob":
            continue
        path = inputs / name.decode()
        actual = subprocess.check_output(["git", "hash-object", str(path)], cwd=REPO).strip()
        if actual != blob:
            raise RuntimeError("Candidate source changed: " + name.decode())

    artifact = PINS["raknetArtifact"].split(":")[-1]
    name = "netty-transport-raknet-" + artifact + "-sources.jar"
    source_jar = artifacts / name
    if not source_jar.exists():
        url = "https://repo.opencollab.dev/maven-snapshots/org/cloudburstmc/netty/netty-transport-raknet/1.1.0.CR1-SNAPSHOT/" + name
        with urllib.request.urlopen(url, timeout=60) as response:
            source_jar.write_bytes(response.read())
    if sha(source_jar) != PINS["raknetSourcesSha256"]:
        raise RuntimeError("RakNet source artifact checksum mismatch")
    baseline = PINS["raknetSourceRevision"]
    if subprocess.run(["git", "cat-file", "-e", baseline], cwd=REPO, capture_output=True).returncode:
        run("git", "fetch", "--no-tags", "https://github.com/CloudburstMC/Network.git", baseline, cwd=REPO)
    with zipfile.ZipFile(source_jar) as archive:
        sources = [name for name in archive.namelist() if name.endswith(".java")]
        for name in sources:
            expected = subprocess.check_output(["git", "show", baseline + ":transport-raknet/src/main/java/" + name], cwd=REPO)
            if archive.read(name) != expected:
                raise RuntimeError("RakNet source mismatch: " + name)

    native = inputs / ".native-deps" / ("libdatachannel-java-" + PINS["nativeBindingRevision"])
    maven = inputs / ".native-deps/maven/io/github/teamziax/libdatachannel-java" / ("0.24.5.0-dev." + PINS["nativeBindingRevision"])
    if not (maven / "provenance.json").exists():
        run("bash", "scripts/bootstrap-native-admission.sh", cwd=inputs)
    provenance = json.loads((maven / "provenance.json").read_text())
    for field, key in [("bindingRevision", "nativeBindingRevision"), ("libdatachannelRevision", "libdatachannelRevision"), ("libjuiceRevision", "libjuiceRevision")]:
        if provenance[field] != PINS[key]:
            raise RuntimeError("Native pin mismatch: " + field)
    for name, expected in provenance["sha256"].items():
        if sha(maven / name) != expected:
            raise RuntimeError("Native artifact checksum mismatch: " + name)
    run("cmake", "-S", "jni", "-B", "build/benchmark-release", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
        "-DCMAKE_BUILD_TYPE=Release", "-DPROJECT_VERSION=0.24.5.0-dev." + PINS["nativeBindingRevision"],
        "-DENABLE_LOCALHOST_ADDRESS=ON", "-DUSE_SYSTEM_JUICE=OFF",
        "-DLIBDATACHANNEL_SOURCE_DIR=" + str(native / "jni/libdatachannel"), cwd=native)
    run("cmake", "--build", "build/benchmark-release", "--target", "datachannel-java", "-j2", cwd=native)
    run(str(REPO / "gradlew"), "--no-daemon", "--max-workers=2", "test", "installDist")

    library = native / "build/benchmark-release/libdatachannel-java.so"
    jars = ROOT / "build/install/transport-comparison/lib"
    runtime_jars = {path.name: sha(path) for path in sorted(jars.glob("*.jar"))}
    raknet_name = "netty-transport-raknet-" + artifact + ".jar"
    if [name for name in runtime_jars if name.startswith("netty-transport-raknet-")] != [raknet_name]:
        raise RuntimeError("Unexpected RakNet runtime selection")
    if runtime_jars[raknet_name] != "24b72dd6e01e6707b91d759f7bf08bf0d3b166e392119632ce8430d8899d9971":
        raise RuntimeError("Published RakNet binary checksum mismatch")
    with zipfile.ZipFile(jars / raknet_name) as archive:
        baseline_classes = {name for name in archive.namelist() if name.endswith(".class")}
        forbidden = ("RakModelCongestionController", "FastWeightedFairQueue", "RakChildWriteHandoff", "RakBoundedRecovery")
        if any(any(part in name for part in forbidden) for name in archive.namelist()):
            raise RuntimeError("Benchmark-improved RakNet classes found")
    for name in runtime_jars:
        if name != raknet_name:
            with zipfile.ZipFile(jars / name) as archive:
                duplicates = baseline_classes.intersection(archive.namelist())
                if duplicates:
                    raise RuntimeError("Another runtime JAR shadows RakNet classes: " + name + " " + str(sorted(duplicates)))
    (artifacts / "raknet-classpath-audit.json").write_text(json.dumps({
        "raknetClassCount": len(baseline_classes), "otherRuntimeJarsDefiningRaknetClasses": {}
    }, indent=2) + "\n")
    result = dict(PINS, runtimeJars=runtime_jars, nativeReleaseSha256=sha(library),
                  raknetSourcesMatched=len(sources), raknetOptimizedClassesPresent=[],
                  nativeLdd=subprocess.check_output(["ldd", str(library)], text=True),
                  sourceSha256={str(path.relative_to(ROOT)): sha(path) for path in sorted((ROOT / "src").rglob("*.java"))})
    (artifacts / "artifact-provenance.json").write_text(json.dumps(result, indent=2) + "\n")
    identity = artifacts / "identity"
    identity.mkdir(exist_ok=True)
    if not (identity / "cert.pem").exists():
        run("openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
            "-keyout", str(identity / "key.pem"), "-out", str(identity / "cert.pem"), "-days", "2", "-subj", "/CN=local-transport-benchmark")
    print("Prepared and verified comparison inputs", flush=True)


if __name__ == "__main__":
    prepare()
