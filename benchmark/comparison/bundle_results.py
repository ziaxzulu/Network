#!/usr/bin/env python3
"""Package completed comparisons, analysis source and per-file integrity hashes."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile

from summarize import summarize

ROOT = Path(__file__).resolve().parent


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify_bundle(path):
    with tarfile.open(path, "r:gz") as archive:
        members = archive.getmembers()
        names = [member.name for member in members]
        if len(names) != len(set(names)) or any(not member.isfile() for member in members):
            raise ValueError("Archive contains duplicate or non-file members")
        manifest = json.load(archive.extractfile("manifest.json"))
        expected = set(manifest["files"]) | {"manifest.json"}
        if set(names) != expected:
            raise ValueError("Archive member list differs from the manifest")
        for name, expected_hash in manifest["files"].items():
            if digest(archive.extractfile(name).read()) != expected_hash:
                raise ValueError("Archive member checksum mismatch: " + name)
    return manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("full", type=Path)
    parser.add_argument("controls", type=Path)
    parser.add_argument("out", type=Path)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError("Refusing to overwrite an existing evidence bundle")
    counts = {}
    for name, folder in [("full", args.full), ("controls", args.controls)]:
        _, _, completed, planned = summarize(folder)
        counts[name] = {"completed": completed, "planned": planned}
    provenance = json.loads((args.full / "artifact-provenance.json").read_text())
    if json.loads((args.controls / "artifact-provenance.json").read_text()) != provenance:
        raise ValueError("Full and control campaigns used different artifact provenance")

    files = {}
    campaign_directories = {}
    allowed = {".json", ".jsonl", ".csv", ".md", ".log", ".txt", ".png", ".svg"}
    for name, folder in [("full", args.full), ("controls", args.controls)]:
        # Preserve the repository-relative layout so report/source links work
        # after extraction and the harness can be copied into the fork.
        campaign_directories[name] = "benchmark/comparison/artifacts/" + folder.name
        for path in sorted(folder.rglob("*")):
            if path.is_file() and not path.is_symlink() and path.suffix in allowed:
                files[campaign_directories[name] + "/" + path.relative_to(folder).as_posix()] = path
    for path in sorted(ROOT.iterdir()):
        if path.is_file() and (path.suffix in {".py", ".sh", ".md", ".kts", ".json"} or path.name == ".gitignore"):
            files["benchmark/comparison/" + path.name] = path
    for path in sorted((ROOT / "src").rglob("*.java")):
        files["benchmark/comparison/" + path.relative_to(ROOT).as_posix()] = path
    for path in sorted((ROOT.parent / "docs").glob("*.md")):
        files["benchmark/docs/" + path.name] = path
    for name in ["raknet-classpath-audit.json", "warden-live-discovery-20260907.json", "resume-tests.log"]:
        path = ROOT / "artifacts" / name
        if path.exists():
            files["benchmark/comparison/artifacts/" + name] = path
    for path in sorted((ROOT / "build/test-results/test").glob("TEST-*.xml")):
        files["benchmark/comparison/artifacts/validation/" + path.name] = path
    native_provenance = ROOT / ".inputs/.native-deps/maven/io/github/teamziax/libdatachannel-java" / (
        "0.24.5.0-dev." + provenance["nativeBindingRevision"]) / "provenance.json"
    if native_provenance.exists():
        files["benchmark/comparison/artifacts/validation/native-bootstrap-provenance.json"] = native_provenance
    license_file = ROOT.parent.parent / "LICENSE"
    if license_file.exists():
        files["LICENSE"] = license_file

    manifest = {"kind": "single-host-raknet-nethernet-comparison",
        "campaigns": counts, "campaignDirectories": campaign_directories, "artifactProvenance": provenance,
        "files": {name: digest(path.read_bytes()) for name, path in files.items()},
        "scope": "Completed development experiments including failed rows, not a production capacity or stock-client gameplay certificate.",
        "reproduction": "Use the included comparison source in the repository fork; prepare.py retrieves the immutable transport/native inputs. JARs, native binaries, caches and identity private keys are excluded."}
    manifest_bytes = (json.dumps(manifest, indent=2) + "\n").encode()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(args.out, "w:gz", compresslevel=6) as archive:
        for name, path in files.items():
            data = path.read_bytes()
            if digest(data) != manifest["files"][name]:
                raise ValueError("Evidence changed during packaging: " + str(path))
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
        info = tarfile.TarInfo("manifest.json")
        info.size = len(manifest_bytes)
        archive.addfile(info, io.BytesIO(manifest_bytes))
    verified = verify_bundle(args.out)
    checksum = digest(args.out.read_bytes())
    args.out.with_suffix(args.out.suffix + ".sha256").write_text(checksum + "  " + args.out.name + "\n")
    print(f"Verified {len(verified['files'])} files in {args.out}; SHA-256 {checksum}")


if __name__ == "__main__":
    main()
