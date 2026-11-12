"""Generate the owned normal opening pack for the modular spider."""
import json
from pathlib import Path
import shutil

from aa_beam_fixture import png
from bunker_fixture import render


ROOT = Path(__file__).resolve().parents[2]
INPUTS = ROOT / "SimulationTest/public/inputs"
DESTINATION = INPUTS / "modular-spider-start"
SLOTS = ((-11, 32, 0), (11, 32, 0), (-11, 6, -90), (11, 6, 90), (-10, -20, 180), (11, -20, 180))
LEGS = ((-50, 20, -25, 10), (50, 20, 15, 10), (-35, 60, -15, 25), (35, 60, 15, 25),
        (-50, -30, -15, -25), (50, -30, 15, -25), (-35, -60, -15, -35), (35, -60, 15, -35))


def opening():
    sections = {
        "core": {"name": "modularSpider", "class": "CustomUnitMetadata", "price": 90000,
                 "maxHp": 10000, "radius": 49, "fogOfWarSightRange": 19, "transportSlotsNeeded": 14,
                 "isPickableStartingUnit": True, "startFallingWhenStartingUnit": True,
                 "maxShield": 5000, "shieldRegen": .3, "selfRegenRate": .1,
                 "generation_credits": 30, "generation_delay": 40},
        "graphics": {"image": "body.png", "image_shadow": "shadow.png", "image_wreak": "dead.png",
                     "image_turret": "nano.png", "image_slotUi": "slot-ui.png", "total_frames": 1},
        "movement": {"movementType": "OVER_CLIFF_WATER", "moveSpeed": .6, "moveAccelerationSpeed": .015,
                     "moveDecelerationSpeed": .015, "maxTurnSpeed": .4, "turnAcceleration": .1,
                     "reverseSpeedPercentage": 1},
    }
    for i, (x, y, heading) in enumerate(SLOTS, 1):
        sections[f"attachment_slot{i}"] = dict(x=x, y=y, idleDir=heading, idleDirReversing=heading,
            onCreateSpawnUnitOf="modularSpider_emptySlot", lockRotation=True,
            redirectDamageToParent=True, redirectDamageToParent_shieldOnly=True)
    for i, (x, y, anchor_x, anchor_y) in enumerate(LEGS, 1):
        sections[f"leg_{i}"] = dict(x=x, y=y, attach_x=anchor_x, attach_y=anchor_y,
            moveSpeed=2.6, heightSpeed=.6, moveWarmUp=4, resetAngle=20,
            holdDisMin=12, holdDisMax=25, holdDisMin_maxMovingLegs=5,
            favourOppositeSideNeighbours=True, image_leg="leg.png", image_foot="foot.png")
    return sections


def empty_slot():
    return {
        "core": {"name": "modularSpider_emptySlot", "class": "CustomUnitMetadata", "price": 1000,
                 "maxHp": 3000, "radius": 20, "selfRegenRate": 3000,
                 "isUnselectable": True, "canNotBeDirectlyAttacked": True},
        "graphics": {"image": "empty.png", "total_frames": 1},
        "action_buildGun": {"convertTo": "modularSpider_gunturret", "price": 2000, "buildSpeed": 0, "setBuilt": 0},
    }


def gun():
    return {
        "core": {"name": "modularSpider_gunturret", "class": "CustomUnitMetadata", "price": 2000,
                 "maxHp": 2000, "radius": 15, "displayRadius": 12, "isUnselectable": True,
                 "canNotBeDirectlyAttacked": False, "selfBuildRate": .001, "selfRegenRate": .2},
        "graphics": {"image": "empty.png", "image_turret": "gun.png", "total_frames": 1},
        "attack": {"canAttackFlyingUnits": False, "canAttackLandUnits": True, "canAttackUnderwaterUnits": False,
                   "maxAttackRange": 290, "turretSize": 22, "turretTurnSpeed": 2, "shootDelay": 20},
        "turret_1": {"x": 0, "y": 0, "barrelX": -3, "barrelOffsetX_onOddShots": 6, "projectile": "1",
                     "shoot_sound": "firing3", "shoot_sound_vol": .3, "recoilOffset": -.5,
                     "recoilOutTime": 6, "recoilReturnTime": 12, "idleSweepAngle": 20,
                     "idleSweepDelay": 120, "idleSweepSpeed": .2},
        "projectile_1": {"directDamage": 44, "speed": 6, "life": 60},
        "action_cancel": {"convertTo": "modularSpider_emptySlot", "refundCredits": 2000,
                          "addAllActionCooldownsTime": 120},
        "action_reclaim": {"convertTo": "modularSpider_emptySlot", "buildSpeed": 180, "refundCredits": 1000},
    }


def generate():
    assert not DESTINATION.exists(), "Existing owned input tree"
    for fmt in ("ini", "json", "hjson"):
        root = DESTINATION / fmt
        source = INPUTS / "flame-mech-production" / fmt
        pack = root / "opening-package"
        units = pack / "units"
        spider = units / "spider"
        spider.mkdir(parents=True)
        for folder in ("maps", "assets"):
            shutil.copytree(source / folder, root / folder)
        shutil.copyfile(source / "combat.properties", root / "combat.properties")
        for name in (f"mech.{fmt}", "body.png", "leg.png", "foot.png"):
            shutil.copyfile(source / "production-package/units" / name, units / name)
        forms = (("spider", opening()), ("empty", empty_slot()), ("gun", gun()))
        for name, sections in forms:
            (spider / f"{name}.{fmt}").write_text(render(sections, fmt), encoding="utf8")
        for filename, kind, width, height in (
            ("body", "body", 81, 113), ("dead", "dead", 70, 90), ("shadow", "shadow", 81, 113),
            ("slot-ui", "body", 82, 114), ("leg", "turret", 10, 84), ("foot", "body", 17, 22),
            ("nano", "turret", 11, 19), ("gun", "turret", 14, 46), ("empty", "empty", 1, 1),
        ):
            (spider / f"{filename}.png").write_bytes(png(kind, width, height))
        entries = [{"type": "mech", "file": f"units/mech.{fmt}"}]
        entries.extend({"type": "modular-spider", "file": f"units/spider/{name}.{fmt}"} for name, _ in forms)
        manifest = dict(format="rw-reforge.content-pack.v1", id=f"owned-modular-spider-start-{fmt}",
                        name="Owned Modular Spider opening", units=entries)
        (pack / "pack.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf8")
    (DESTINATION / "README.md").write_text("# Owned Modular Spider opening inputs\n\n"
        "Procedural spider sprites and the existing owned target and terrain. "
        "Generated with SimulationTest/tools/modular_spider_fixture.py.\n", encoding="utf8")


if __name__ == "__main__":
    generate()
