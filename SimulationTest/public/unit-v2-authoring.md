# Authoring a Reforge unit

Set `format` to `rw-reforge.unit.v2` in a structured unit definition. Use the `.json` suffix for strict JSON or `.hjson` for Hjson. Both formats use the same content rules after parsing. List the file in a content pack's `units` array with `"type": "tank"` to select the ordinary tank reader. The same pack can include qualified INI definitions.

Use `copyFrom` to name parent files relative to the file that lists them. A child overrides supplied fields. When several parents declare the same field, the later parent wins. The ordinary tank reader resolves inherited image names from the unit being loaded. Diagnostics retain the path that declared the field.

```json
{
  "format": "rw-reforge.unit.v2",
  "copyFrom": ["base-tank.json"],
  "core": {"name": "amber_scout"},
  "weapons": [
    {
      "name": "main",
      "delay": 12,
      "projectile": {"directDamage": 41, "life": 50, "speed": 8}
    }
  ]
}
```

The child updates the parent's `main` mount by stable name. It keeps the mount coordinates and other unspecified weapon fields. `projectile` replaces the complete parent value. Omit it to inherit the parent value, set it to a string to reference a named entry in `projectiles`, or supply an object for a projectile used only by that weapon. A combat tank projectile declares direct damage, lifetime in simulation ticks, and speed in world units per tick. A weapon delay uses simulation ticks. The example assumes a normal combat parent with mount, targeting, graphics and movement defaults.

An empty `weapons` list retains the parent weapons. Set `removeWeapons` to the stable names to remove from this file's inherited weapons. A sibling parent can still supply a weapon removed within a different branch. An absent name, a duplicate declaration, or a surviving weapon link to a deleted entry is a load error. Projectile names beginning with `__inline_` are reserved.

The [schema](unit-v2.schema.json) lists fields and types for the ordinary tank and hover-tank readers. Required gameplay fields can come from parents. The selected reader checks the effective definition, value ranges and references after inheritance. Add the new unit name to the factory's production list to make the unit available through the normal purchase action.

Set `"unitType": "tank"` to use the ordinary tank's default land movement. You can omit the `movement` object or supply the entries you want to override, for example `"movement": {"moveSpeed": 1.25}`. Explicit movement fields from the unit or its parents take priority over template defaults. The specialized `hover-tank` loader accepts `"unitType": "hoverTank"` with the same override rule and its existing hover defaults.

Qualified native submarine and amphibious jet content uses its specialized loader and declares its route profile with a root `locomotion` field in JSON and Hjson. Use `"water"` with the submarine and `"air-water"` with the amphibious jet. The named profiles carry the existing dive and surface rules. Flight uses an air route until a dive begins, and it resumes when the ascending jet reaches height zero. For both units, water movement speed and underwater weapon eligibility change below height −1. Native INI keeps the established profile for its qualified unit.

Add a shield with "abilities": [{"name": "screen", "type": "shield", "maximum": 500, "regeneration": 0.08}]. The unit starts with its shield full by default. Set "startAtZero": true to start empty. Regeneration is measured in shield points per simulation tick and defaults to zero. One shield ability may be effective on a unit. A child can override parameters of the inherited ability by stable name. An empty array preserves inherited abilities, while "removeAbilities": ["screen"] removes that declaration. The existing INI shield fields map into the same runtime shield profile.

The qualified AA gunship reader ("aa-beam-gunship") takes an afterburn object with two unit ids, for example {"normalForm":"escort","boostedForm":"escort_boosted"}. Let the boosted definition inherit that object. List both definitions in the enabled content and point the existing conversion actions at the declared form ids. The qualified form keeps the AA gunship's energy, cooldown, acceleration, targeting and trail rules. INI uses the original named pair.

Attachment speed uses a replacement contribution. For the modular spider, a completed speed module takes priority over mounted-weapon contributions. Several contributions of the same priority use the first slot in the unit's layout. The winning contribution replaces base speed, then the terrain multiplier applies. Removing the winner allows the next contribution or the base speed to take effect.

A structured unit loaded as `hover-tank` can declare one `blink` ability. The target is measured in world units and must be on a HOVER-passable tile. Charge, unlock and cooldown durations use simulation ticks. Set `queueSpeed` when the unit uses a queue multiplier. It defaults to one and must agree with the factory multiplier on definitions that also produce units. The optional paid unlock requires both `unlockCredits` and `unlockTicks`. The owner sees `addBlink` until the unlock completes, then sees the ground-targeted `blink` action. Setting `locked` disables Blink on a deployed form and retains the definition for inheritance.

~~~json
"abilities": [
  {
    "name": "jump", "type": "blink", "range": 700,
    "chargeTicks": 210, "cooldownTicks": 1500,
    "unlockCredits": 10000, "unlockTicks": 3000
  }
]
~~~

A structured `hover-tank` can carry units by declaring one transport ability. Set its capacity in passenger slots, the boarding movement type, the unload terrain, and the interval between unload attempts in simulation ticks.

~~~json
{
  "name": "waterBay", "type": "transport", "capacity": 2,
  "passengerMovement": "HOVER", "unloadTerrain": "liquid", "unloadTicks": 6
}
~~~

The transport uses the same owner, construction, and cargo checks as the native carriers. The passenger consumes its own `transportSlotsNeeded`. The `unloadTerrain` values are `land`, `liquid`, `either` and `never`. A blocked exit leaves the passenger in the bay for the next attempt.

A structured `hover-tank` accepts one `pointDefense` ability. Supply `range`, `muzzleOffsetX` and `muzzleOffsetY` in world units, `recharge` per simulation tick, `cost` per deflection and `initialEnergy`. Energy parameters are fractions from zero to one. For example:

~~~json
{"name":"deflector", "type":"pointDefense", "range":160, "muzzleOffsetX":0,
 "muzzleOffsetY":-13, "recharge":0.0004, "cost":0.11, "initialEnergy":1}
~~~

A child can inherit this ability and override one parameter with `{"name":"deflector","cost":0.2}`. The loader checks the complete ability after inheritance.

An authored `water-combat` hull can declare one `navalInterception` ability. `weapon` names a mount with `canShoot: false` and a ballistic, accelerating projectile with `deflectionPower: -1`. `landingRange` and `trackingRange` are world distances, and `minimumHeight` is the strict lower height for an incoming round. `ammoPrice` sets the credit cost, `ammoBuildRate` sets progress per simulation tick, and `ammoLimit` limits stock plus queued orders. The owner stocks the interceptor through the normal `buildAntiNuke` action.

An authored modular-spider interceptor can inherit the qualified anti-nuke module, use a lowercase `core.name`, and override the three `interceptProjectiles_and…` fields on its `silo` mount. Set `convertTo` on the empty slot's `buildAntinuke` action to the authored ID. The slot uses the usual paid installation, ammunition queue, and online switch.

The `nuclear-silo` content reader also accepts an authored name with `ordnanceRole` set to `launcher` or `interceptor` in a structured `rw-reforge.nuclear-silo.v1` document. Use the corresponding facility's fixed body, construction and graphics fields. The role determines which stock and launch actions are available. The established INI IDs continue to select their own role.
