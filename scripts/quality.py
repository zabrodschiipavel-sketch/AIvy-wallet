"""Small, dependency-free CI checks. Missing evidence is a failure, never a pass."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile
import xml.etree.ElementTree as ET


BASELINES = {
    "lint": "app/lint-baseline.xml",
    "detekt": "config/detekt/baseline.yml",
    "compose": "ci-actions/compose-stability/ivy-compose-stability-baseline.txt",
}
LEGACY_REFERENCE = re.compile(
    r"\bcom\.ivy\.(?:legacy|design|domain\.db)(?:\.[\w*]+)*"
    r"|\bcom\.ivy\.data\.db\.(?:dao(?:\.[\w*]+)*|IvyRoomDatabase)\b"
)
LEGACY_PROJECT = re.compile(
    r"\bprojects\.temp\.(?:legacyCode|oldDesign)\b|[\"'](:temp:(?:legacy-code|old-design))[\"']"
)


def read_xml(text: str, expected: str) -> ET.Element:
    root = ET.fromstring(text)
    if root.tag != expected:
        raise ValueError(f"Expected <{expected}>, found <{root.tag}>")
    return root


def lint_errors(text: str) -> list[str]:
    root = read_xml(text, "issues")
    if root.get("type") == "baseline":
        raise ValueError("A lint baseline is not an execution report")
    errors = []
    for issue in root.findall("issue"):
        severity = issue.get("severity")
        if severity not in {"Fatal", "Error", "Warning", "Information", "Informational", "Ignore"}:
            raise ValueError(f"Missing or unknown lint severity: {severity!r}")
        if severity in {"Fatal", "Error"}:
            errors.append(f"{issue.get('id')}: {issue.get('message', '')}")
    return errors


def baseline_entries(kind: str, text: str) -> Counter:
    if kind == "compose":
        return Counter(line.strip() for line in text.splitlines() if line.strip())
    if kind == "detekt":
        root = read_xml(text, "SmellBaseline")
        return Counter(node.text for node in root.findall(".//ID"))
    root = read_xml(text, "issues")
    # Line numbers change when code moves. Keep issue identity, source and message.
    return Counter(json.dumps({
        "id": node.get("id"),
        "message": node.get("message"),
        "source": node.get("errorLine1"),
        "locations": sorted(location.get("file", "").replace("\\", "/")
                            for location in node.findall("location")),
    }, sort_keys=True) for node in root.findall("issue"))


def feature_edges(files: dict[str, str]) -> Counter:
    edges = Counter()
    for path, text in files.items():
        if not path.startswith("feature/") or "/build/" in path:
            continue
        if path.endswith(".kt") and "/src/main/" in path:
            # Strip comments so documentation does not create a dependency. Match
            # qualified references as well as imports (including aliases/wildcards).
            code = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
            for reference in set(LEGACY_REFERENCE.findall(code)):
                edges[f"{path}: {reference}"] = 1
        elif path.endswith("build.gradle.kts"):
            for match in LEGACY_PROJECT.finditer(text):
                reference = match.group(1) or match.group(0)
                edges[f"{path}: {reference}"] = 1
    return edges


def git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.PIPE)


def source_suppressions(files: dict[str, str]) -> Counter:
    entries = Counter()
    pattern = r"@(?:\w+:)?(?:[\w.]+\.)?(?:Suppress|SuppressLint|SuppressWarnings)\s*\(([^)]*)\)"
    for path, code in files.items():
        code = re.sub(r"/\*.*?\*/|//[^\n]*", "", code, flags=re.S)
        for annotation in re.finditer(pattern, code, flags=re.S):
            for rule in re.findall(r'"([^"]+)"', annotation.group(1)):
                entries[f"{path}: {rule}"] += 1
    return entries


def ratchet(root: Path) -> dict:
    policy = json.loads((root / "config/quality/ratchet.json").read_text(encoding="utf-8"))
    commit = policy["baseline_commit"]
    if policy.get("schema") != 1 or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Invalid ratchet policy")
    failures = []
    counts = {}
    for kind, path in BASELINES.items():
        allowed = baseline_entries(kind, git(root, "show", f"{commit}:{path}").decode("utf-8"))
        current = baseline_entries(kind, (root / path).read_text(encoding="utf-8"))
        additions = current - allowed
        counts[kind] = {"initial": sum(allowed.values()), "current": sum(current.values())}
        failures.extend(f"New {kind} baseline entry: {entry}" for entry in additions)
    with tarfile.open(fileobj=io.BytesIO(git(root, "archive", commit))) as archive:
        original_files = {
            entry.name: archive.extractfile(entry).read().decode("utf-8")
            for entry in archive.getmembers()
            if entry.isfile() and entry.name.endswith((".kt", "build.gradle.kts"))
        }
    paths = git(root, "ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", "*.kt", "*.kts").decode().split("\0")
    current_files = {path: (root / path).read_text(encoding="utf-8") for path in paths
                     if path.endswith((".kt", "build.gradle.kts")) and (root / path).is_file()}
    allowed_edges, current_edges = feature_edges(original_files), feature_edges(current_files)
    allowed_edges.update(policy.get("pre_roadmap_edges", []))
    counts["feature_legacy_or_dao_edges"] = {"initial": len(allowed_edges), "current": len(current_edges)}
    failures.extend(f"New feature dependency: {edge}" for edge in current_edges - allowed_edges)
    original_suppressions = source_suppressions(original_files)
    current_suppressions = source_suppressions(current_files)
    counts["source_suppressions"] = {
        "initial": sum(original_suppressions.values()), "current": sum(current_suppressions.values())}
    failures.extend(f"New source suppression: {entry}"
                    for entry in current_suppressions - original_suppressions)
    if failures:
        raise ValueError("\n".join(failures[:25]) + f"\n{len(failures)} new exceptions/dependencies")
    return {"baseline_commit": commit, "counts": counts}


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inspect_badging(badging: str) -> str:
    package = re.search(r"^package: name='([^']+)'", badging, re.M)
    if not package or package.group(1) != "com.ivy.wallet.optimized":
        raise ValueError("Candidate APK has unexpected or missing package")
    if re.search(r"^application-debuggable(?:\s|$)", badging, re.M):
        raise ValueError("Candidate APK is debuggable")
    return package.group(1)


def candidate(apk: Path, root: Path, output: Path, aapt: Path) -> dict:
    if not apk.is_file() or apk.stat().st_size == 0:
        raise ValueError("Candidate APK is missing or empty")
    package = inspect_badging(subprocess.check_output(
        [str(aapt), "dump", "badging", str(apk)], text=True, encoding="utf-8"))
    metadata = {"schema": 1, "commit": git(root, "rev-parse", "HEAD").decode().strip(),
                "apk": apk.name, "sha256": sha256(apk), "bytes": apk.stat().st_size,
                "variant": "release", "package": package}
    output.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    commands = parser.add_subparsers(dest="command", required=True)
    lint = commands.add_parser("lint")
    lint.add_argument("report", type=Path)
    commands.add_parser("ratchet")
    package = commands.add_parser("candidate")
    package.add_argument("apk", type=Path)
    package.add_argument("--out", type=Path, required=True)
    package.add_argument("--aapt", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "lint":
            errors = lint_errors(args.report.read_text(encoding="utf-8"))
            if errors:
                raise ValueError("\n".join(errors))
            print("Lint report is present and contains no Error/Fatal issues.")
        elif args.command == "ratchet":
            print(json.dumps(ratchet(args.root.resolve()), indent=2))
        else:
            print(json.dumps(candidate(args.apk, args.root, args.out, args.aapt), indent=2))
        return 0
    except (OSError, ValueError, KeyError, ET.ParseError, subprocess.CalledProcessError) as error:
        print(f"QUALITY CHECK FAILED: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
