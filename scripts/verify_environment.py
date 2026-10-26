"""Check the selected toolchain before building; record only non-secret metadata."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def properties(path: Path) -> dict[str, str]:
    result = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, _, value = line.partition("=")
            result[key.strip()] = value.strip().strip('"')
    return result


def validate_release(release: dict[str, str], expected: str) -> None:
    if release.get("IMPLEMENTOR") != "Eclipse Adoptium":
        raise ValueError("JAVA_HOME must select Eclipse Temurin")
    if release.get("SEMANTIC_VERSION") != expected:
        raise ValueError(f"JAVA_HOME must select the pinned JDK {expected}")


def validate_sdk(platform: dict[str, str], tools: dict[str, str]) -> None:
    if platform.get("AndroidVersion.ApiLevel") != "34" or tools.get("Pkg.Revision") != "34.0.0":
        raise ValueError("Android platform 34 and build-tools 34.0.0 are required")


def inspect(root: Path, jdk: Path, sdk: Path) -> dict:
    expected = (root / ".java-version").read_text(encoding="utf-8-sig").strip()
    release = properties(jdk / "release")
    validate_release(release, expected)
    suffix = ".exe" if os.name == "nt" else ""
    version = subprocess.check_output([str(jdk / "bin" / ("java" + suffix)), "-version"],
                                      stderr=subprocess.STDOUT, text=True, encoding="utf-8")
    if f"Temurin-{expected}" not in version:
        raise ValueError("Java executable does not match the JDK release metadata")
    platform = properties(sdk / "platforms/android-34/source.properties")
    tools = properties(sdk / "build-tools/34.0.0/source.properties")
    validate_sdk(platform, tools)
    jar = sdk / "platforms/android-34/android.jar"
    with jar.open("rb") as stream:
        jar_hash = hashlib.file_digest(stream, "sha256").hexdigest()
    home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    init = [home / "init.gradle", home / "init.gradle.kts"]
    init.extend(sorted((home / "init.d").glob("*")))
    user_config = [path for path in [home / "gradle.properties", *init] if path.is_file()]
    return {"schema": 1, "jdk": {"vendor": release["IMPLEMENTOR"], "version": expected,
                                "home": str(jdk), "java_version": version.strip()},
            "sdk": {"home": str(sdk), "platform": platform, "build_tools": tools,
                    "android_jar_sha256": jar_hash,
                    "platform_tools": properties(sdk / "platform-tools/source.properties")},
            "gradle_user_home": str(home), "global_dependency_cache_reused": (home / "caches").exists(),
            "user_gradle_configuration_files": [str(path) for path in user_config]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        args.out.unlink(missing_ok=True)
        result = inspect(args.root.resolve(), Path(os.environ["JAVA_HOME"]),
                         Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"]))
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(f"Toolchain verified: Temurin {result['jdk']['version']}, Android 34, build-tools 34.0.0")
        return 0
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"ENVIRONMENT CHECK FAILED: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
