"""Check the packaged launcher contract. This does not run an Android device."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile


def verify(apk: Path, aapt2: Path) -> dict:
    badging = subprocess.run(
        [str(aapt2), "dump", "badging", str(apk)],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120,
    )
    if badging.returncode != 0:
        raise RuntimeError(badging.stdout + badging.stderr)
    with zipfile.ZipFile(apk) as archive:
        entries = set(archive.namelist())
        dex = b"".join(archive.read(name) for name in entries if name.endswith(".dex"))
        checks = {
            "launcher": "launchable-activity: name='com.rustedwarfare.reforge.android.AndroidLauncher'" in badging.stdout,
            "android_entry_dex": b"Lcom/rustedwarfare/reforge/android/AndroidLauncher;" in dex,
            "shared_screen_dex": b"Lcom/rustedwarfare/reforge/client/BootstrapScreen;" in dex,
            "core_dex": b"Lcom/rustedwarfare/reforge/core/CoreRuntime;" in dex,
            "font_asset": "com/badlogic/gdx/utils/lsans-15.fnt" in entries,
            "font_texture": "com/badlogic/gdx/utils/lsans-15.png" in entries,
            "license_asset": "assets/licenses/Apache-2.0.txt" in entries,
            "third_party_notices": "assets/licenses/THIRD-PARTY-NOTICES.txt" in entries,
        }
        for abi in ("armeabi-v7a", "arm64-v8a", "x86", "x86_64"):
            checks[f"native_{abi}"] = f"lib/{abi}/libgdx.so" in entries
    return {
        "apk": str(apk.resolve()), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "size": apk.stat().st_size, "checks": checks,
        "passed": all(checks.values()), "badging": badging.stdout,
        "boundary": "Packaged launcher contract only; no device launch or gameplay proof.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt2", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = verify(args.apk, args.aapt2)
    rendered = json.dumps(result, indent=2)
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered)
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
