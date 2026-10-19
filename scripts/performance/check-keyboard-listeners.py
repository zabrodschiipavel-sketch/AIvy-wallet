"""Build a small instrumentation probe and check repeated editor cancellation.

Requires an emulator with onboarding completed and an APK signed by the supplied
key. SDK/JDK paths can come from ANDROID_HOME and JAVA_HOME. Run --record-only
when collecting the unmodified baseline. Probe source: AnimationProbe.java.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--jdk", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--alias", required=True)
    parser.add_argument("--password-file", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--record-only", action="store_true")
    args = parser.parse_args()
    if not args.sdk or not args.jdk:
        parser.error("Supply --sdk and --jdk or set ANDROID_HOME and JAVA_HOME")
    if not args.serial.startswith("emulator-"):
        parser.error("This probe is intended for an isolated Android emulator")
    args.out = args.out.resolve()
    for folder in [args.out, args.out/"classes", args.out/"dex"]:
        folder.mkdir(parents=True, exist_ok=True)
    suffix = ".exe" if os.name == "nt" else ""
    bt = args.sdk/"build-tools/34.0.0"
    android = args.sdk/"platforms/android-34/android.jar"
    java = args.jdk/"bin"/("java"+suffix)

    def run(*parts):
        return subprocess.run(list(map(str, parts)), check=True, capture_output=True, text=True).stdout

    def adb(*parts):
        return run("adb", "-s", args.serial, *parts).strip()

    if adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Emulator required")
    manifest = args.out/"AndroidManifest.xml"
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.ivy.perfprobe"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="34"/><application android:label="Ivy animation probe"/><instrumentation android:name="com.ivy.perfprobe.AnimationProbe" android:targetPackage="com.ivy.wallet.debug" android:functionalTest="true"/></manifest>''', encoding="utf-8")
    run(bt/("aapt2"+suffix), "link", "-I", android, "--manifest", manifest, "-o", args.out/"unsigned.apk")
    run(args.jdk/"bin"/("javac"+suffix), "-source", "8", "-target", "8", "-classpath", android,
        "-d", args.out/"classes", Path(__file__).with_name("AnimationProbe.java"))
    run(java, "-cp", bt/"lib/d8.jar", "com.android.tools.r8.D8", "--lib", android, "--min-api", "28",
        "--output", args.out/"dex", *list((args.out/"classes").rglob("*.class")))
    with zipfile.ZipFile(args.out/"unsigned.apk", "a") as apk:
        apk.write(args.out/"dex/classes.dex", "classes.dex")
    run(bt/("zipalign"+suffix), "-f", "4", args.out/"unsigned.apk", args.out/"aligned.apk")
    run(java, "-jar", bt/"lib/apksigner.jar", "sign", "--ks", args.keystore,
        "--ks-key-alias", args.alias, "--ks-pass", "file:"+str(args.password_file.resolve()),
        "--out", args.out/"probe.apk", args.out/"aligned.apk")
    adb("install", "-r", args.out/"probe.apk")
    previous_policy = adb("shell", "settings", "get", "global", "hidden_api_policy")
    try:
        adb("shell", "settings", "put", "global", "hidden_api_policy", "1")
        output = adb("shell", "am", "instrument", "-w", "com.ivy.perfprobe/com.ivy.perfprobe.AnimationProbe")
    finally:
        if previous_policy == "null":
            adb("shell", "settings", "delete", "global", "hidden_api_policy")
        else:
            adb("shell", "settings", "put", "global", "hidden_api_policy", previous_policy)
    (args.out/"listeners.txt").write_text(output, encoding="utf-8")
    result = dict(re.findall(r"INSTRUMENTATION_RESULT: (\w+)=(.*)", output))
    (args.out/"summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    if "INSTRUMENTATION_CODE: -1" not in output or result.get("completed_cycles") != "12":
        raise RuntimeError("Probe did not complete all 12 cycles; see listeners.txt")
    if not args.record_only and result["listeners_initial"] != result["listeners_final"]:
        raise RuntimeError("Global layout listeners accumulated after editor cancellation")


if __name__ == "__main__":
    main()
