# Ordinary group move replay

Run on the Windows host with Python, a JDK providing the class-file API (24+), and a desktop `pc` installation containing `core.jar` and `client.jar`. The runner verifies the input through the desktop scenario loader and issues one ordinary selection move through the simulation boundary. The command label must be a valid action ID prefix. The scenario must contain exactly the requested number of controllable `p1` mobile members.

```powershell
# From SimulationTest/tools in the checked-out repository
$repo = (Resolve-Path ../..).Path
$engineCheckout = Join-Path $repo Engine
$pcLib = Join-Path $engineCheckout pc/build/install/pc/lib
$sandboxScene = Join-Path $repo SimulationTest/public/scenarios/phase-six-a-json.json
$freshFixtureDirectory = Join-Path $env:TEMP ("rw-fixtures-" + [guid]::NewGuid())
$freshRun = Join-Path $env:TEMP ("rw-replay-" + [guid]::NewGuid())
python -B group_replay_fixtures.py $sandboxScene $freshFixtureDirectory
$scene = Join-Path $freshFixtureDirectory open/scenarios/open2-270.json
python -B run_group_replay.py --lib $pcLib --engine $engineCheckout --scene $scene --output $freshRun --command open2-270-0 --click 680 272 --steps 2400 --members 2 --quiet 600
```

The generator expects the phase-six sandbox content contract (`c_tank`, `mechGun`, `maps/phase-six-a.tmx`, 20-unit tiles). `cases.json` lists inputs, counts, clicks and headings. Each generated scene has a geometry companion with spawn poses, collision circles, obstacles and target capacity. Preserve the historical fixed-click input separately when comparing that order.

`trace.tsv.gz` records each selected vehicle every step. `x/y/heading` are final observed poses, `before*` and `ownEnd*` bound the vehicle鈥檚 own update, and `push*` records external displacement. `origin` is the active action identity. An empty motion sample leaves its diagnostic fields blank. `activeTarget*` tracks the current target. `arrivalOriginalTarget*` identifies the original command target retained by the arrival record. `group_replay_summary.py` reports arrival events, sustained pose stability and autonomous progress along the steering direction and forward body axis. Use the geometry and complete traces for collision, crossing and occupancy checks.

`steps.tsv` records simulation CPU and wall time for each step, command acceptance CPU on step zero, charged planning work, queue slices, restarts and publication work. `planning.tsv` accumulates nonoverlapping planning method intervals on the simulation thread, including repeated slot and route checks. `stderr.log` identifies installed hooks. A runtime with different planning method names requires updating and checking the timed scope. CPU times are in ns with the resolution of the host thread clock. Use `advanceWallNs` for frame-independent step latency. Each invocation starts a new JVM, world and group order. A shared-field reuse experiment requires recording actual cache hits when that mechanism is present.

Compare the same observation window on both versions. `completedWindow` ends at the first complete observation interval after every member arrives and holds position and heading. `equalWindow` includes the entire requested replay. Pair full-command cost only between runs completing the same task. Keep raw traces, input hashes, scenario, executable identity and per-step metrics with the result.

`published` is the `movement-started` event emitted when the simulation adopts the initial route. The planner clock records the passage admission decision as `admissionWaitIds` for each step. Join those member IDs to the matching trace step. `prePublicationWaitSteps` counts frames before a member has an adopted route. `admissionWaitSteps` counts the admission controller鈥檚 explicit waiting decisions and `releasedAfterWait` lists the first autonomous forward step after each wait episode. A further wait before motion extends the pending episode. `longestStationaryWithTargetOutsideAdmission` measures the longest other stationary interval with an active target. Determining its cause requires examining that vehicle鈥檚 route, steering and contact evidence.
