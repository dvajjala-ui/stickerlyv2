"""Tiny adb UI driver for device checks (screenshots, UI tree, tap by text).

Usage (PowerShell):  python tools/droid.py shot out.png
                     python tools/droid.py texts
                     python tools/droid.py tap "Add"            # first node whose text/desc matches
                     python tools/droid.py tapx "Import"        # case-insensitive substring match
                     python tools/droid.py wait "Added" 20      # wait up to N seconds for text
Never use it to open chats or send messages in messaging apps.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", r"D:\dev\android-sdk\platform-tools\adb.exe")


def adb(*args: str, binary: bool = False) -> bytes | str:
    out = subprocess.run([ADB, *args], capture_output=True, timeout=60)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.decode(errors="replace") or f"adb {' '.join(args)} failed")
    return out.stdout if binary else out.stdout.decode(errors="replace")


def shot(path: str) -> None:
    data = adb("exec-out", "screencap", "-p", binary=True)
    with open(path, "wb") as f:
        f.write(data)  # type: ignore[arg-type]


def nodes() -> list[dict]:
    adb("shell", "uiautomator", "dump", "/sdcard/window_dump.xml")
    xml = adb("exec-out", "cat", "/sdcard/window_dump.xml")
    root = ET.fromstring(xml)  # type: ignore[arg-type]
    result = []
    for n in root.iter("node"):
        bounds = re.findall(r"\d+", n.get("bounds", ""))
        if len(bounds) != 4:
            continue
        x1, y1, x2, y2 = map(int, bounds)
        result.append({
            "text": n.get("text", ""), "desc": n.get("content-desc", ""), "id": n.get("resource-id", ""),
            "pkg": n.get("package", ""), "clickable": n.get("clickable") == "true",
            "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2, "bounds": (x1, y1, x2, y2),
        })
    return result


def find(label: str, exact: bool = True) -> dict | None:
    want = label.lower()
    for n in nodes():
        for value in (n["text"], n["desc"]):
            v = value.lower()
            if (v == want) if exact else (want in v):
                return n
    return None


def tap(label: str, exact: bool = True) -> dict:
    n = find(label, exact)
    if n is None:
        raise SystemExit(f"not found: {label}")
    adb("shell", "input", "tap", str(n["cx"]), str(n["cy"]))
    return n


def wait(label: str, seconds: float, exact: bool = False) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        if find(label, exact):
            return True
        time.sleep(1.0)
    return False


def main(argv: list[str]) -> int:
    cmd = argv[1] if len(argv) > 1 else "texts"
    if cmd == "shot":
        shot(argv[2]); print(argv[2])
    elif cmd == "texts":
        for n in nodes():
            label = n["text"] or n["desc"]
            if label:
                print(f'{n["pkg"]:<32} {"*" if n["clickable"] else " "} ({n["cx"]},{n["cy"]}) {label[:90]}')
    elif cmd in ("tap", "tapx"):
        n = tap(argv[2], exact=(cmd == "tap"))
        print(f'tapped {n["text"] or n["desc"]} at ({n["cx"]},{n["cy"]}) in {n["pkg"]}')
    elif cmd == "wait":
        ok = wait(argv[2], float(argv[3]) if len(argv) > 3 else 15)
        print("found" if ok else "timeout"); return 0 if ok else 1
    else:
        print(__doc__); return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
