# Owned transport fixtures

These INI, JSON and Hjson packages use project-owned controlled content and placeholder artwork.
They contain no original game images, audio, executable code or decompiled source.

The existing naval-production owned fixture supplies the shore map, sea factories, builder,
submarine, tank, helicopter, battleship and underwater target. The command center and its owned
images come from amphibious-production. Native definition numbers describe the finite content
contract; these fixtures make no original-runtime compatibility claim.

The landing-craft live/dead placeholders are independent colored rectangles on a transparent
20 by 32 canvas. Their full pixel recipe and standalone PNG generator are in
`SimulationTest/tools/transport_assets.py`. Run it without arguments to verify all six images;
`--output <new-directory>` writes newly encoded equivalent PNGs without replacing fixture files.
Compression bytes are not part of the artwork recipe; paired scenarios pin the actual fixture bytes.

`SimulationTest/tools/transport_scenarios.py` generates the three paired short core/client examples,
independent behavior contracts and three normal paid-production session plans. Private original
resource qualification and actual Windows operation are separate evidence domains.
