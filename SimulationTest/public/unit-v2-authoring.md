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

The [schema](unit-v2.schema.json) lists fields and types for the ordinary tank reader. Required gameplay fields can come from parents. The reader also checks the effective definition, value ranges and references after inheritance. Add the new unit name to the factory's production list to make the unit available through the normal purchase action.
