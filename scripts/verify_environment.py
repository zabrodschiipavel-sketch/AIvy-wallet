"""Check the selected toolchain before building; record only non-secret metadata."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tomllib

BUILD_TOOLS_VERSION = "34.0.0"


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


def sdk_api(root: Path) -> str:
    catalog = tomllib.loads((root / "gradle/libs.versions.toml").read_text(encoding="utf-8-sig"))
    api = catalog["versions"]["compile-sdk"]
    if not isinstance(api, str) or not api.isascii() or not api.isdigit() or int(api) <= 0:
        raise ValueError("compile-sdk must be a fixed numeric Android API")
    return api


def validate_sdk(platform: dict[str, str], tools: dict[str, str], expected_api: str) -> None:
    if platform.get("AndroidVersion.ApiLevel") != expected_api or tools.get("Pkg.Revision") != BUILD_TOOLS_VERSION:
        raise ValueError(f"Android platform {expected_api} and build-tools {BUILD_TOOLS_VERSION} are required")


def inspect(root: Path, jdk: Path, sdk: Path) -> dict:
    expected = (root / ".java-version").read_text(encoding="utf-8-sig").strip()
    release = properties(jdk / "release")
    validate_release(release, expected)
    suffix = ".exe" if os.name == "nt" else ""
    version = subprocess.check_output([str(jdk / "bin" / ("java" + suffix)), "-version"],
                                      stderr=subprocess.STDOUT, text=True, encoding="utf-8")
    if f"Temurin-{expected}" not in version:
        raise ValueError("Java executable does not match the JDK release metadata")
    api = sdk_api(root)
    platform_dir = sdk / "platforms" / f"android-{api}"
    platform = properties(platform_dir / "source.properties")
    tools = properties(sdk / "build-tools" / BUILD_TOOLS_VERSION / "source.properties")
    validate_sdk(platform, tools, api)
    jar = platform_dir / "android.jar"
    with jar.open("rb") as stream:
        jar_hash = hashlib.file_digest(stream, "sha256").hexdigest()
    home = Path(os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle")
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
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument("--out", type=Path)
    actions.add_argument("--sdk-packages", action="store_true")
    args = parser.parse_args()
    try:
        if args.sdk_packages:
            print(f"platforms;android-{sdk_api(args.root.resolve())}")
            print(f"build-tools;{BUILD_TOOLS_VERSION}")
            return 0
        args.out.unlink(missing_ok=True)
        result = inspect(args.root.resolve(), Path(os.environ["JAVA_HOME"]),
                         Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"]))
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        api = result["sdk"]["platform"]["AndroidVersion.ApiLevel"]
        print(f"Toolchain verified: Temurin {result['jdk']['version']}, Android {api}, build-tools {BUILD_TOOLS_VERSION}")
        return 0
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"ENVIRONMENT CHECK FAILED: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
