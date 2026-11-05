# Explicit package combination inputs

Self-owned existing controlled map and Amber tank pictures only. Base scouts declares
250 HP amber_scout and 320 HP amber_guard. Overlay scouts explicitly depends on base
and replaces only amber_scout with a complete 420 HP definition; its metadata uses Hjson.
Final scouts explicitly depends on overlay and replaces the same runtime ID with a
600 HP INI definition. No package is discovered or enabled automatically.

Select base + overlay for 420 HP; reversing path order has the same final origin.
Select all three for the explicit base -> overlay -> final chain. Overlay alone is
the missing-dependency negative input; final + base also lacks its direct overlay.
The map and legacy units are retained for the disabled-package baseline, separate
from all three package roots. Runtime sources must include replaced base inputs.
