# Owned named-weapon representative

ENG-P008-012 development variation, with equivalent INI, JSON and Hjson leaves.
All leaves inherit an owned INI base and named section template. The map, terrain
and three tank pictures are copied byte for byte from the existing owned public
production-combat fixture; no original-game assets or executable are included.

The existing controlled package layout supplies a=(110,210), b=(350,210), c=(510,330).
Near has maximum 300, far has minimum 300, and explicit multi-targeting is enabled.
Health 100000, range 450 and sight 30 are deliberate development values so native
inspection remains stable and both targets are visible. They are not original
tank statistics, balance rules, performance tolerances or a new spawn algorithm.
Near uses ordinary warmup; far uses noReset and transfer. The ordinary no-pack
combat fixture remains alongside the package for the existing fallback.

The adjacent platform plans and independent contracts were written before their
consumer run. Fixture preparation alone does not establish a passing run.
