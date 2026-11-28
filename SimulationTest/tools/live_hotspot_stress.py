#!/usr/bin/env python3
"""Unattended background real-OpenGL 350x350 4x hotspot stress runner (ENG-P017-003 / 011).

Drives the packaged Windows LWJGL3 client in hidden background mode (`--background-input true
--diagnostics false`) so zero foreground OS focus is stolen and zero 600MB+ per-frame JSONL
files are produced, while exercising real OpenGL rendering, 4x simulation pacing, multi-factory
continuous production, repeated box-select group moves (normal + Alt), camera pan/zoom, F3+H
hotspot overlay screenshots, and structured `hotspot-summary.json` assertions.
"""
from __future__ import annotations

import argparse
import ctypes
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_STEAM_RW = Path(r"E:\SteamLibrary\steamapps\common\Rusted Warfare")
DEFAULT_MAP_REL = Path("assets") / "maps" / "skirmish" / "[z;p10]Many Islands Large (10p).tmx"

# LibGDX Input.Keys constants
KEY_ENTER = 66
KEY_ESCAPE = 111
KEY_SLASH = 76
LIBGDX_KEY_F3 = 246
LIBGDX_KEY_H = 36

REQUIRED_STAGES = (
    "nav.flow",
    "nav.plan",
    "nav.avoid",
    "sim.contact",
    "sim.vision",
    "sim.combat",
    "sim.forkCommit",
    "client.observe",
    "render.terrain",
    "render.entities",
    "render.minimap",
    "render.hud",
)


def foreground_hwnd() -> int:
    if os.name != "nt":
        return 0
    try:
        return int(ctypes.windll.user32.GetForegroundWindow())
    except Exception:
        return 0


def resolve_java_executable() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("java")
    if found:
        return found
    raise RuntimeError("Unable to locate java executable")


def world_to_screen(proj: dict[str, Any], world_x: float, world_y: float, width: int, height: int) -> tuple[int, int]:
    """Maps world coordinates to clamped client screen pixel coordinates using status.worldToScreen."""
    sx = round(float(proj["offsetX"]) + float(world_x) * float(proj["scaleX"]))
    sy = round(float(proj["offsetY"]) + float(world_y) * float(proj["scaleY"]))
    return max(4, min(width - 5, sx)), max(4, min(height - 5, sy))


def validate_hotspot_summary(
    summary: dict[str, Any],
    *,
    min_steps: int = 0,
    max_p95_ms: float | None = None,
    max_p99_ms: float | None = None,
    min_effective_multiplier: float | None = None,
) -> list[str]:
    problems: list[str] = []
    if summary.get("schemaVersion") != 1:
        problems.append(f"Expected schemaVersion=1, got {summary.get('schemaVersion')}")
    frames = summary.get("frames") or {}
    if int(frames.get("steadyStateSampledFrames", 0)) <= 0:
        problems.append(f"Expected steadyStateSampledFrames > 0, got {frames}")
    pacing = summary.get("pacing") or {}
    executed = int(pacing.get("totalExecutedSubsteps", 0))
    if executed < min_steps:
        problems.append(f"Executed substeps {executed} < required min_steps {min_steps}")
    stages = summary.get("stagesMs") or {}
    for key in REQUIRED_STAGES:
        entry = stages.get(key)
        if not isinstance(entry, dict):
            problems.append(f"Missing stage '{key}' in stagesMs")
            continue
        for metric in ("mean", "p50", "p95", "p99", "max"):
            if metric not in entry:
                problems.append(f"Stage '{key}' missing percentile '{metric}'")
    work = summary.get("frameWorkMs") or {}
    for metric in ("mean", "p50", "p95", "p99", "max"):
        if metric not in work:
            problems.append(f"frameWorkMs missing '{metric}'")
    if max_p95_ms is not None and float(work.get("p95", 9999.0)) > max_p95_ms:
        problems.append(f"frameWorkMs.p95={work.get('p95')} > {max_p95_ms} ms")
    if max_p99_ms is not None and float(work.get("p99", 9999.0)) > max_p99_ms:
        problems.append(f"frameWorkMs.p99={work.get('p99')} > {max_p99_ms} ms")
    if min_effective_multiplier is not None and float(pacing.get("effectiveRateMultiplier", 0.0)) < min_effective_multiplier:
        problems.append(
            f"pacing.effectiveRateMultiplier={pacing.get('effectiveRateMultiplier')} < {min_effective_multiplier}x"
        )
    slow_top = summary.get("slowFramesTop10")
    if not isinstance(slow_top, list) or len(slow_top) > 10:
        problems.append("slowFramesTop10 must be a list of length <= 10")
    return problems


def verify_no_giant_raw_jsonl(directory: Path, max_jsonl_bytes: int = 1024 * 1024) -> list[str]:
    problems: list[str] = []
    if not directory.exists():
        return problems
    for path in directory.rglob("*.jsonl"):
        size = path.stat().st_size
        if size > max_jsonl_bytes:
            problems.append(f"Raw JSONL {path} ({size} bytes) exceeds cap {max_jsonl_bytes}")
    return problems


class BackgroundBridgeClient:
    def __init__(self, bridge_dir: Path, run_id: str, proc: subprocess.Popen[bytes]):
        self.bridge_dir = bridge_dir
        self.run_id = run_id
        self.proc = proc
        self.req_id = 0

    def send(self, payload: dict[str, Any], timeout: float = 30.0) -> dict[str, Any]:
        self.req_id += 1
        rid = self.req_id
        body = dict(payload, runId=self.run_id, id=rid)
        tmp = self.bridge_dir / f"request-{rid}.json.tmp"
        req = self.bridge_dir / f"request-{rid}.json"
        resp = self.bridge_dir / f"response-{rid}.json"
        tmp.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, req)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if resp.is_file():
                try:
                    data = json.loads(resp.read_text(encoding="utf-8"))
                except (PermissionError, OSError, json.JSONDecodeError):
                    time.sleep(0.015)
                    continue
                try:
                    req.unlink(missing_ok=True)
                    resp.unlink(missing_ok=True)
                except OSError:
                    pass
                if not data.get("ok", False):
                    raise RuntimeError(f"Background request #{rid} ({payload.get('action')}) failed: {data}")
                return data
            if self.proc.poll() is not None:
                raise RuntimeError(f"Client exited prematurely (rc={self.proc.returncode}) waiting for request #{rid}")
            time.sleep(0.025)
        raise TimeoutError(f"Timed out waiting for response-{rid}.json ({payload.get('action')})")


def run_stress(args: argparse.Namespace) -> int:
    repo = Path(args.repo).resolve()
    install_lib = repo / "Engine" / "pc" / "build" / "install" / "pc" / "lib"
    if not install_lib.is_dir():
        raise RuntimeError(f"Missing packaged PC lib directory: {install_lib} (run :pc:installDist first)")

    out_dir = Path(args.output).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    summary_path = out_dir / "hotspot-summary.json"

    map_path = Path(args.map).resolve() if args.map else (DEFAULT_STEAM_RW / DEFAULT_MAP_REL)
    if not map_path.is_file():
        raise RuntimeError(f"Skirmish map not found: {map_path}")

    inputs_path = (
        Path(args.inputs).resolve()
        if args.inputs
        else (repo / "SimulationTest" / "public" / "inputs" / "phase-seven-sandbox" / "json" / "package")
    )
    if not inputs_path.exists():
        raise RuntimeError(f"Sandbox content inputs not found: {inputs_path}")

    original_assets = Path(args.original_assets).resolve() if args.original_assets else DEFAULT_STEAM_RW
    use_original = original_assets.is_dir() and not args.placeholder_visuals

    hwnd_before = foreground_hwnd()
    java_exe = resolve_java_executable()

    with tempfile.TemporaryDirectory(prefix="p017-bg-stress-") as tmp_str:
        tmp_root = Path(tmp_str)
        tmp_run = tmp_root / "bg-evidence"
        tmp_appdata = tmp_root / "localappdata"
        tmp_appdata.mkdir(parents=True, exist_ok=True)
        child_env = dict(os.environ, LOCALAPPDATA=str(tmp_appdata))
        cli_args = [
            java_exe,
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            str(install_lib / "*"),
            "com.rustedwarfare.reforge.pc.WindowsLauncherKt",
            "--mode",
            "sandbox",
            "--development",
            "true",
            "--diagnostics",
            "false",
            "--background-input",
            "true",
            "--background-size",
            args.window_size,
            "--inputs",
            str(inputs_path),
            "--evidence",
            str(tmp_run),
            "--hotspot-summary",
            str(summary_path),
            "--map",
            str(map_path),
        ]
        if args.placeholder_visuals:
            cli_args.extend(["--visuals", "placeholder"])
        elif use_original:
            cli_args.extend(["--original-assets", str(original_assets)])

        stdout_log = out_dir / "client-stdout.log"
        stderr_log = out_dir / "client-stderr.log"
        started_wall = time.time()
        with stdout_log.open("wb") as out_f, stderr_log.open("wb") as err_f:
            proc = subprocess.Popen(cli_args, cwd=str(repo), env=child_env, stdout=out_f, stderr=err_f)
            try:
                bridge_dir = tmp_run / "background-input"
                ready_file = bridge_dir / "ready.json"
                deadline = time.monotonic() + 60.0
                while time.monotonic() < deadline:
                    if ready_file.is_file():
                        break
                    if proc.poll() is not None:
                        raise RuntimeError(f"Client exited before ready.json (rc={proc.returncode})")
                    time.sleep(0.05)
                else:
                    raise TimeoutError("Timed out waiting for background-input/ready.json")

                ready = json.loads(ready_file.read_text(encoding="utf-8"))
                run_id = ready["runId"]
                win_w = int(ready["width"])
                win_h = int(ready["height"])
                client = BackgroundBridgeClient(bridge_dir, run_id, proc)

                # 1. Wait for startup menu & resource preparation to become ready, then click Start
                deadline = time.monotonic() + 120.0
                start_btn = None
                last_st: dict[str, Any] = {}
                gen = int(ready["generation"])
                while time.monotonic() < deadline:
                    st = client.send({"action": "status"})
                    last_st = st
                    gen = int(st["generation"])
                    btns = st.get("uiButtons") or {}
                    if st.get("canStart") and not st.get("visualBusy") and "start-match" in btns:
                        start_btn = btns["start-match"]
                        break
                    time.sleep(0.10)
                if start_btn is None:
                    raise TimeoutError(f"Startup menu never reached canStart=true (last_status={last_st})")

                client.send({
                    "action": "click",
                    "generation": gen,
                    "button": "left",
                    "x": int(start_btn["x"]),
                    "y": int(start_btn["y"]),
                })

                # 2. Wait for 350x350 skirmish match & original textures to finish loading
                deadline = time.monotonic() + 120.0
                match_status: dict[str, Any] | None = None
                while time.monotonic() < deadline:
                    st = client.send({"action": "status"})
                    if st.get("loadError"):
                        raise RuntimeError(f"Match loadError: {st['loadError']}")
                    if st.get("hasSession") and not st.get("loading"):
                        match_status = st
                        break
                    time.sleep(0.10)
                if match_status is None:
                    raise TimeoutError("Timed out waiting for 350x350 match session to open")

                gen = int(match_status["generation"])
                f3_keycode = int(match_status.get("keyF3", LIBGDX_KEY_F3))
                h_keycode = int(match_status.get("keyH", LIBGDX_KEY_H))

                # 3. Click HUD 4x speed button
                btns = match_status.get("uiButtons") or {}
                if "set-speed:4" not in btns:
                    raise RuntimeError(f"Missing set-speed:4 button in HUD: {list(btns.keys())}")
                client.send({
                    "action": "click",
                    "generation": gen,
                    "button": "left",
                    "x": int(btns["set-speed:4"]["x"]),
                    "y": int(btns["set-speed:4"]["y"]),
                })

                # 4. Fund p1 & spawn multi-factory base + initial mobile wave on verified passable pads via `/` console
                anchors = match_status.get("passableLandAnchors") or []
                near_pads = anchors[:4]
                spawn_pads = anchors[:8] if len(anchors) >= 6 else near_pads
                far_targets = anchors[4:] if len(anchors) > 4 else anchors

                def send_console_cmd(cmd_text: str) -> None:
                    # Open console via typed '/' (sets swallowNextSlashTyped=false & clears field),
                    # type full command, press ENTER, then ESCAPE.
                    client.send({"action": "type", "generation": gen, "text": "/"})
                    client.send({"action": "type", "generation": gen, "text": cmd_text})
                    client.send({"action": "key", "generation": gen, "keycode": KEY_ENTER})
                    client.send({"action": "key", "generation": gen, "keycode": KEY_ESCAPE})

                send_console_cmd("/credits p1 +500000")
                factory_types = ("landFactory", "mechFactory", "airFactory")
                for idx_pad, pad in enumerate(near_pads[:3]):
                    px, py = round(float(pad["x"])), round(float(pad["y"]))
                    send_console_cmd(f"/spawn {factory_types[idx_pad % len(factory_types)]} {px} {py} p1")

                # Spawn initial mobile mixed squads (land + hover + air) across verified passable pads via /spawn-batch
                target_initial_spawn = max(24, min(240, int(args.initial_spawn_units)))
                if spawn_pads:
                    per_pad = max(1, (target_initial_spawn + len(spawn_pads) - 1) // max(1, len(spawn_pads)))
                    spawn_entries: list[str] = []
                    spawned = 0
                    for pad in spawn_pads:
                        bx, by = float(pad["x"]), float(pad["y"])
                        for k in range(per_pad):
                            if spawned >= target_initial_spawn:
                                break
                            ox = ((k % 6) - 2.5) * 28.0
                            oy = ((k // 6) + 1.5) * 28.0
                            mod = spawned % 6
                            utype = "c_tank" if mod in (0, 1, 2) else ("hoverTank" if mod in (3, 4) else "helicopter")
                            spawn_entries.append(f"u{spawned} {utype} {round(bx + ox)} {round(by + oy)} p1")
                            spawned += 1
                    chunk: list[str] = []
                    for entry in spawn_entries:
                        candidate = "/spawn-batch " + " | ".join(chunk + [entry])
                        if chunk and len(candidate) > 480:
                            send_console_cmd("/spawn-batch " + " | ".join(chunk))
                            chunk = [entry]
                        else:
                            chunk.append(entry)
                    if chunk:
                        send_console_cmd("/spawn-batch " + " | ".join(chunk))

                time.sleep(0.25)
                st = client.send({"action": "status"})
                alive_after_spawn = int(st.get("p1AliveCount") or 0)
                if anchors and alive_after_spawn < max(16, target_initial_spawn // 2):
                    raise RuntimeError(
                        f"Expected console spawn to raise p1AliveCount >= {max(16, target_initial_spawn // 2)}, "
                        f"got {alive_after_spawn} (status={st})"
                    )

                # 5. Click each spawned factory (land/mech/air) and queue continuous production via HUD
                factories = [u for u in (st.get("p1Entities") or []) if u.get("typeId") in factory_types]
                for fac in factories[:3]:
                    client.send({"action": "camera", "generation": gen, "centerX": fac["worldX"], "centerY": fac["worldY"], "zoom": 3.6})
                    st_fac = client.send({"action": "status"})
                    sx, sy = world_to_screen(st_fac["worldToScreen"], fac["worldX"], fac["worldY"], win_w, win_h)
                    client.send({"action": "click", "generation": gen, "button": "left", "x": sx, "y": sy})
                    st_sel = client.send({"action": "status"})
                    prod_btns = st_sel.get("uiButtons") or {}
                    prod_key = next(
                        (k for k in ("produce:c_tank", "produce:mechGun", "produce:helicopter") if k in prod_btns),
                        next((k for k in prod_btns if k.startswith("produce:")), None),
                    )
                    if prod_key:
                        pb = prod_btns[prod_key]
                        for _ in range(int(args.queue_per_factory)):
                            client.send({"action": "click", "generation": gen, "button": "left", "x": int(pb["x"]), "y": int(pb["y"])})

                # 6. Execute evenly spaced box-select group moves (normal & Alt), camera pans & scroll zooms across >= min_steps active steps
                st_loop_start = client.send({"action": "status"})
                base_step = int(st_loop_start.get("stepIndex") or 0)
                move_interval = max(40, int(args.min_steps) // max(2, int(args.group_moves) + 1))
                move_orders_issued = 0
                alt_orders_issued = 0
                max_selected_count = 0
                camera_pans = 0
                zoom_ops = 0
                battlefield_png = out_dir / "battlefield-4x.png"
                f3_hotspots_png = out_dir / "f3-hotspots-4x.png"
                captured_battlefield = False

                loop_deadline = time.monotonic() + float(args.timeout_seconds)
                while time.monotonic() < loop_deadline:
                    st = client.send({"action": "status"})
                    step_idx = int(st.get("stepIndex") or 0)
                    active_steps = step_idx - base_step
                    max_selected_count = max(max_selected_count, int(st.get("selectedCount") or 0))
                    p1_mobile = [
                        u for u in (st.get("p1Entities") or [])
                        if u.get("mobile") and u.get("typeId") != "builder"
                    ] or [u for u in (st.get("p1Entities") or []) if u.get("mobile")]

                    if p1_mobile and far_targets and (
                        move_orders_issued < int(args.group_moves)
                        and active_steps >= move_orders_issued * move_interval
                    ):
                        # Center at gameplay zoom (3.6 on 350x350 ~ 1:1 sprite scale) and drag world-projected box over squad
                        if move_orders_issued < len(near_pads):
                            pad_hub = near_pads[move_orders_issued]
                            hub_x, hub_y = float(pad_hub["x"]), float(pad_hub["y"]) + 48.0
                        else:
                            hub = p1_mobile[move_orders_issued % len(p1_mobile)]
                            hub_x, hub_y = float(hub["worldX"]), float(hub["worldY"])
                        client.send({"action": "camera", "generation": gen, "centerX": hub_x, "centerY": hub_y, "zoom": 3.6})
                        camera_pans += 1
                        st_hub = client.send({"action": "status"})
                        raw_x1, raw_y1 = world_to_screen(st_hub["worldToScreen"], hub_x - 280.0, hub_y - 220.0, win_w, win_h)
                        raw_x2, raw_y2 = world_to_screen(st_hub["worldToScreen"], hub_x + 280.0, hub_y + 220.0, win_w, win_h)
                        x1 = max(250, min(win_w - 280, min(raw_x1, raw_x2)))
                        y1 = max(84, min(win_h - 110, min(raw_y1, raw_y2)))
                        x2 = max(x1 + 64, min(win_w - 260, max(raw_x1, raw_x2)))
                        y2 = max(y1 + 64, min(win_h - 96, max(raw_y1, raw_y2)))
                        client.send({"action": "drag", "generation": gen, "button": "left", "x1": x1, "y1": y1, "x2": x2, "y2": y2})
                        st_after_box = client.send({"action": "status"})
                        max_selected_count = max(max_selected_count, int(st_after_box.get("selectedCount") or 0))

                        dest = far_targets[move_orders_issued % len(far_targets)]
                        client.send({"action": "camera", "generation": gen, "centerX": dest["x"], "centerY": dest["y"], "zoom": 3.4})
                        camera_pans += 1
                        st_dest = client.send({"action": "status"})
                        dx, dy = world_to_screen(st_dest["worldToScreen"], dest["x"], dest["y"], win_w, win_h)
                        dx = max(250, min(win_w - 260, dx))
                        dy = max(84, min(win_h - 96, dy))
                        use_alt = (move_orders_issued % 3 == 2)
                        client.send({
                            "action": "click",
                            "generation": gen,
                            "button": "right",
                            "x": dx,
                            "y": dy,
                            "alt": use_alt,
                        })
                        move_orders_issued += 1
                        if use_alt:
                            alt_orders_issued += 1

                        # Also exercise real middle-mouse drag pan + wheel zoom
                        mid_x, mid_y = win_w // 2 - 120, win_h // 2
                        client.send({
                            "action": "drag",
                            "generation": gen,
                            "button": "middle",
                            "x1": mid_x,
                            "y1": mid_y,
                            "x2": mid_x + 90,
                            "y2": mid_y + 40,
                        })
                        scroll_amt = 1 if (zoom_ops % 2 == 0) else -1
                        client.send({"action": "scroll", "generation": gen, "x": mid_x, "y": mid_y, "amount": scroll_amt})
                        zoom_ops += 1

                    if not captured_battlefield and active_steps >= max(180, int(args.min_steps) // 2) and p1_mobile:
                        focus_u = p1_mobile[0]
                        client.send({"action": "camera", "generation": gen, "centerX": focus_u["worldX"], "centerY": focus_u["worldY"], "zoom": 3.6})
                        time.sleep(0.08)
                        shot = client.send({"action": "screenshot"})
                        shutil.copy2(bridge_dir / shot["image"], battlefield_png)
                        captured_battlefield = True

                    if active_steps >= int(args.min_steps) and move_orders_issued >= int(args.group_moves):
                        break
                    time.sleep(0.12)
                else:
                    raise TimeoutError(
                        f"Timed out reaching min_steps={args.min_steps} and group_moves={args.group_moves} "
                        f"(reached step={step_idx}, moves={move_orders_issued})"
                    )

                if not captured_battlefield:
                    shot = client.send({"action": "screenshot"})
                    shutil.copy2(bridge_dir / shot["image"], battlefield_png)

                # 7. Toggle F3 + F3+H hotspot view, verify active in status, and capture F3 hotspot screenshot
                st_before_f3 = client.send({"action": "status"})
                f3_units = [
                    u for u in (st_before_f3.get("p1Entities") or [])
                    if u.get("mobile") and u.get("typeId") != "builder"
                ] or [u for u in (st_before_f3.get("p1Entities") or []) if u.get("mobile")]
                if f3_units:
                    client.send({
                        "action": "camera",
                        "generation": gen,
                        "centerX": f3_units[0]["worldX"],
                        "centerY": f3_units[0]["worldY"],
                        "zoom": 3.6,
                    })
                dbg = st_before_f3.get("debugLayer") or {}
                if not dbg.get("enabled"):
                    client.send({"action": "key", "generation": gen, "keycode": f3_keycode})
                st_f3 = client.send({"action": "status"})
                if not (st_f3.get("debugLayer") or {}).get("showHotspots"):
                    client.send({"action": "key", "generation": gen, "keycode": h_keycode, "f3": True})
                time.sleep(0.10)
                st_after_h = client.send({"action": "status"})
                if not (st_after_h.get("debugLayer") or {}).get("showHotspots"):
                    raise RuntimeError(f"Expected F3+H showHotspots=true, got {st_after_h.get('debugLayer')}")

                shot_f3 = client.send({"action": "screenshot"})
                shutil.copy2(bridge_dir / shot_f3["image"], f3_hotspots_png)

                # 8. Cleanly close client so dispose() flushes final hotspot-summary.json
                client.send({"action": "close"})
                exit_rc = proc.wait(timeout=30.0)
                if exit_rc != 0:
                    raise RuntimeError(f"Client exited with non-zero code {exit_rc}")
            finally:
                if proc.poll() is None:
                    proc.kill()
                    proc.wait(timeout=10.0)

        # Verify zero giant JSONL in temp run dir before temp cleanup AND in output dir
        raw_jsonl_problems = verify_no_giant_raw_jsonl(tmp_run) + verify_no_giant_raw_jsonl(out_dir)
        if raw_jsonl_problems:
            raise RuntimeError("Unexpected raw JSONL output: " + "; ".join(raw_jsonl_problems))

    hwnd_after = foreground_hwnd()
    if not summary_path.is_file():
        raise RuntimeError(f"Missing hotspot-summary.json at {summary_path}")

    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    validation_errors = validate_hotspot_summary(
        summary,
        min_steps=int(args.min_steps),
        max_p95_ms=args.assert_p95_ms,
        max_p99_ms=args.assert_p99_ms,
        min_effective_multiplier=args.assert_min_multiplier,
    )

    receipt = {
        "schemaVersion": 1,
        "status": "passed" if not validation_errors else "failed",
        "mapPath": str(map_path),
        "inputsPath": str(inputs_path),
        "usedOriginalAssets": not args.placeholder_visuals,
        "visualAssets": summary.get("visualAssets"),
        "windowSize": args.window_size,
        "wallElapsedSeconds": round(time.time() - started_wall, 3),
        "foregroundHwndBefore": hwnd_before,
        "foregroundHwndAfter": hwnd_after,
        "foregroundFocusPreserved": (hwnd_before == hwnd_after) if hwnd_before != 0 else True,
        "clientPid": ready["pid"],
        "clientExitedCleanly": True,
        "maxSelectedCount": max_selected_count,
        "moveOrdersIssued": move_orders_issued,
        "altMoveOrdersIssued": alt_orders_issued,
        "cameraPans": camera_pans,
        "zoomOperations": zoom_ops,
        "hotspotSummaryBytes": summary_path.stat().st_size,
        "screenshots": {
            "battlefield4x": str(battlefield_png),
            "f3Hotspots4x": str(f3_hotspots_png),
        },
        "validationErrors": validation_errors,
    }
    (out_dir / "run-receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, ensure_ascii=False, indent=2))
    return 0 if not validation_errors else 2


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Unattended 350x350 4x background OpenGL hotspot stress runner")
    p.add_argument("--repo", default=str(REPO_ROOT), help="Worktree root containing packaged Engine/pc/build/install/pc")
    p.add_argument("--output", required=True, help="Output directory for hotspot-summary.json, screenshots, and run-receipt.json")
    p.add_argument("--inputs", default=None, help="Path to sandbox content package")
    p.add_argument("--map", default=None, help="Path to 350x350 .tmx skirmish map")
    p.add_argument("--original-assets", default=None, help="Path to original Rusted Warfare directory")
    p.add_argument("--placeholder-visuals", action="store_true", help="Force built-in placeholder visuals")
    p.add_argument("--window-size", default="1920x1080", help="Background GL viewport size")
    p.add_argument("--min-steps", type=int, default=1200, help="Minimum simulation steps to execute at 4x")
    p.add_argument("--initial-spawn-units", type=int, default=64, help="Initial mobile units spawned across base pads")
    p.add_argument("--queue-per-factory", type=int, default=12, help="Units queued per spawned landFactory")
    p.add_argument("--group-moves", type=int, default=8, help="Number of box-select cross-island group move waves")
    p.add_argument("--timeout-seconds", type=float, default=240.0, help="Overall match loop timeout")
    p.add_argument("--assert-p95-ms", type=float, default=None, help="Optional hard cap on frameWorkMs.p95")
    p.add_argument("--assert-p99-ms", type=float, default=None, help="Optional hard cap on frameWorkMs.p99")
    p.add_argument("--assert-min-multiplier", type=float, default=None, help="Optional floor on effectiveRateMultiplier")
    return p


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)
    return run_stress(args)


if __name__ == "__main__":
    sys.exit(main())
