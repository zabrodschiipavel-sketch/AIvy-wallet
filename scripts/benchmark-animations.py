"""Repeat Ivy's animations and save Android frame timings without changing app data.

Use the same release-like APK variant, device, data and animation scales for both
runs. Start on the Home tab, with menus closed. Example:
python scripts/benchmark-animations.py --serial emulator-5570 --out build/before
"""
import argparse
import csv
import io
import json
from pathlib import Path
import re
import statistics
import subprocess
import time
import xml.etree.ElementTree as ET


def percentile(values, percent):
    values = sorted(values)
    return values[min(len(values) - 1, int((len(values) - 1) * percent / 100))]


def frame_summary(raw):
    frames = []
    header = None
    latest_reset = None
    table_reset = None
    seen_rows = set()
    excluded = {"flagged": 0, "before_reset": 0, "duplicate": 0, "invalid_timestamps": 0}
    for row in csv.reader(io.StringIO(raw)):
        if row and row[0] == "Applications Graphics Acceleration Info:":
            header = None
            latest_reset = None
        elif row and (reset := re.fullmatch(r"Stats since: (\d+)ns", row[0])):
            latest_reset = int(reset[1])
        elif row and row[0] == "Flags":
            header = row
            table_reset = latest_reset
        elif header and row and row[0].isdigit() and len(row) >= len(header) - 1:
            frame = dict(zip(header, row))
            if frame["Flags"] != "0":
                excluded["flagged"] += 1
                continue
            signature = tuple(row)
            if signature in seen_rows:
                excluded["duplicate"] += 1
                continue
            seen_rows.add(signature)
            timestamps = [int(frame[name]) for name in
                          ("IntendedVsync", "HandleInputStart", "SyncQueued", "SwapBuffers", "FrameCompleted")]
            if table_reset is not None and timestamps[0] < table_reset:
                excluded["before_reset"] += 1
                continue
            # Some emulator records have current UI timestamps but stale GPU /
            # completion fields. Flags=0 alone does not make those samples valid.
            if any(start > end for start, end in zip(timestamps, timestamps[1:])):
                excluded["invalid_timestamps"] += 1
                continue
            frames.append(frame)
    durations = [(int(f["FrameCompleted"]) - int(f["IntendedVsync"])) / 1e6 for f in frames]
    main = [(int(f["SyncQueued"]) - int(f["HandleInputStart"])) / 1e6 for f in frames]
    if not durations:
        raise RuntimeError("No frame timings; benchmark did not run")
    result = {"sampled_frames": len(frames), "excluded_frames": excluded,
              "frame_ms_p50": percentile(durations, 50),
              "frame_ms_p95": percentile(durations, 95), "frame_ms_p99": percentile(durations, 99),
              "main_thread_ms_p50": percentile(main, 50), "main_thread_ms_p95": percentile(main, 95),
              "main_thread_ms_mean": statistics.mean(main)}
    # Android prints both process-wide and per-window counters. Count the first
    # (process) block once per dump rather than counting the same frames twice.
    dumps = raw.split("Applications Graphics Acceleration Info:")[1:]
    for label, pattern in {"total_frames": r"Total frames rendered: (\d+)",
                           "janky_frames": r"Janky frames: (\d+)"}.items():
        matches = [re.search(pattern, dump) for dump in dumps]
        result[label] = sum(int(match[1]) for match in matches if match)
    if result.get("total_frames"):
        result["janky_percent"] = 100 * result["janky_frames"] / result["total_frames"]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.ivy.wallet.debug")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--cycles", type=int, default=12)
    parser.add_argument("--scenario", choices=["more", "fab", "period", "scroll", "all"], default="all")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    def adb(*parts):
        return subprocess.run(["adb", "-s", args.serial, *map(str, parts)],
                              check=True, capture_output=True, text=True, encoding="utf-8").stdout

    def shell(*parts):
        return adb("shell", *parts)

    size = re.search(r"Physical size: (\d+)x(\d+)", shell("wm", "size"))
    width, height = map(int, size.groups())
    density = shell("wm", "density")
    physical_density = re.search(r"Physical density: (\d+)", density)
    if ((width, height) != (720, 1280) or "Override size" in size.string
            or not physical_density or physical_density[1] != "320" or "Override density" in density):
        raise RuntimeError("This coordinate fixture requires 720x1280, density 320; use a matching AVD")

    def tap(x, y):
        shell("input", "tap", x, y)

    def tree():
        shell("uiautomator", "dump", "/sdcard/ivy-animation-check.xml")
        raw = shell("cat", "/sdcard/ivy-animation-check.xml")
        return raw, ET.fromstring(raw)

    def text_present(value):
        raw, root = tree()
        return any(value.lower() in n.get("text", "").lower() for n in root.iter("node")), raw

    present, raw = text_present("Home")
    if not present:
        raise RuntimeError("Start on Home with menus closed and onboarding completed")
    (args.out / "home.xml").write_text(raw, encoding="utf-8")
    metadata = {"serial": args.serial, "package": args.package, "resolution": [width, height], "density": 320,
                "fingerprint": shell("getprop", "ro.build.fingerprint").strip(),
                "cycles": args.cycles,
                "animation_scales": {name: shell("settings", "get", "global", name).strip()
                                     for name in ("animator_duration_scale", "transition_animation_scale", "window_animation_scale")}}
    (args.out / "device.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")

    def check_open(scenario, expected):
        present, xml = text_present(expected)
        (args.out / f"{scenario}-expanded.xml").write_text(xml, encoding="utf-8")
        if not present:
            raise RuntimeError(f"{scenario}: expected {expected!r}; check the coordinate fixture")

    def action(scenario, verify=False):
        if scenario == "more":
            tap(640, 220)
            time.sleep(0.75)
            if verify:
                check_open(scenario, "Search")
            tap(640, 1056)
        elif scenario == "fab":
            tap(360, 1068)
            time.sleep(0.55)
            if verify:
                check_open(scenario, "ADD EXPENSE")
            tap(360, 1068)
        elif scenario == "period":
            tap(450, 220)
            time.sleep(0.55)
            if verify:
                check_open(scenario, "Choose month")
            shell("input", "keyevent", "KEYCODE_BACK")
        elif scenario == "scroll":
            shell("input", "swipe", 360, 1000, 360, 350, 400)
        time.sleep(0.75)

    results = {}
    scenarios = ["more", "fab", "period", "scroll"] if args.scenario == "all" else [args.scenario]
    for scenario in scenarios:
        action(scenario, verify=True)
        for _ in range(3):
            action(scenario)
        samples = []
        for _ in range(args.cycles):
            shell("dumpsys", "gfxinfo", args.package, "reset")
            action(scenario)
            samples.append(shell("dumpsys", "gfxinfo", args.package, "framestats"))
        raw = "\n".join(samples)
        (args.out / f"{scenario}-framestats.txt").write_text(raw, encoding="utf-8")
        results[scenario] = frame_summary(raw)
        present, xml = text_present("Home")
        (args.out / f"{scenario}-after.xml").write_text(xml, encoding="utf-8")
        if not present or not any(n.get("text", "").lower() == "home" for n in ET.fromstring(xml).iter("node")):
            raise RuntimeError(f"{scenario}: did not return to the Home screen")
        print(scenario, json.dumps(results[scenario]), flush=True)
    (args.out / "summary.json").write_text(json.dumps(results, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
