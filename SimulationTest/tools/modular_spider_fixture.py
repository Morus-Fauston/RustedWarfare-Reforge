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
        "action_buildSmallLaser": {"convertTo": "modularSpider_smallgunturret", "price": 700, "buildSpeed": 0, "setBuilt": 0},
        "action_buildAntiair": {"convertTo": "modularSpider_antiair", "price": 2000, "buildSpeed": 0, "setBuilt": 0},
    }


def upgrade(target, price):
    return {"convertTo": "modularSpider_" + target, "price": price, "buildSpeed": 960}


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
        "action_upgradeT2": upgrade("gunturretT2", 16000),
    }


def module(name, price, health, sprite, reach, delay, barrel, turn, *, land, air):
    return {
        "core": {"name": "modularSpider_" + name, "class": "CustomUnitMetadata", "price": price,
                 "maxHp": health, "radius": 15, "displayRadius": 12, "isUnselectable": True,
                 "canNotBeDirectlyAttacked": False, "selfBuildRate": .001, "selfRegenRate": .2},
        "graphics": {"image": "empty.png", "image_turret": sprite + ".png", "total_frames": 1},
        "attack": {"canAttackFlyingUnits": air, "canAttackLandUnits": land, "canAttackUnderwaterUnits": False,
                   "maxAttackRange": reach, "turretSize": barrel, "turretTurnSpeed": turn, "shootDelay": delay},
        "action_cancel": {"convertTo": "modularSpider_emptySlot", "refundCredits": price,
                          "addAllActionCooldownsTime": 120},
        "action_reclaim": {"convertTo": "modularSpider_emptySlot", "refundCredits": price // 2, "buildSpeed": 180},
    }


def small_laser(upgraded):
    if upgraded:
        form = module("smallgunturretT2", 3700, 2000, "small-laser-t2", 290, 42, 30, 2, land=True, air=True)
    else:
        form = module("smallgunturret", 700, 1000, "small-laser", 290, 85, 30, 2, land=True, air=True)
        form["action_upgradeT2"] = upgrade("smallgunturretT2", 3000)
    mount = {"x": 0, "y": 0, "projectile": "laserShot", "delay": 30 if upgraded else 80,
             "size": 17, "turnSpeed": 5, "turnSpeedAcceleration": .2, "shoot_sound": "plasma_fire",
             "shoot_sound_vol": .05, "recoilOffset": -1, "recoilOutTime": 3, "recoilReturnTime": 4}
    if upgraded:
        mount.update(barrelX=-3, barrelOffsetX_onOddShots=6, image_drawOffsetY=5)
    form["turret_smallLaser1"] = mount
    form["projectile_laserShot"] = {"directDamage": 70, "speed": 9, "life": 70}
    return form


def gun_t2():
    form = module("gunturretT2", 18000, 3000, "gun-t2", 290, 13, 23, 6, land=True, air=False)
    form["turret_1"] = dict(gun()["turret_1"], barrelX=-4, barrelOffsetX_onOddShots=8,
                            shoot_sound_vol=.15, idleSweepSpeed=.3)
    form["projectile_1"] = {"directDamage": 40, "speed": 9, "life": 60}
    return form


def anti_air():
    form = module("antiair", 1000, 2000, "aa", 290, 85, 18, 2, land=False, air=True)
    form["turret_1"] = {"x": 0, "y": 0, "projectile": "1", "shoot_sound": "missile_fire",
                       "shoot_sound_vol": .3, "recoilOffset": -3, "recoilOutTime": 6,
                       "recoilReturnTime": 12, "idleSweepAngle": 45, "idleSweepDelay": 110, "idleSweepSpeed": .6}
    form["projectile_1"] = {"directDamage": 180, "speed": .3, "targetSpeed": 6,
                            "life": 240, "autoTargetingOnDeadTarget": True}
    form["action_upgradeT2"] = upgrade("antiairT2", 16000)
    form["action_upgradeFlak"] = upgrade("antiairFlak", 10000)
    return form


def advanced_anti_air(flak):
    if flak:
        form = module("antiairFlak", 12000, 3000, "flak", 200, 160, 27, 6, land=False, air=True)
        shot = {"directDamage": 0, "speed": 20, "areaDamage": 100, "areaRadius": 70}
        sound, local_y, warmup = "large_gun_fire2", 14, 55
    else:
        form = module("antiairT2", 18000, 3000, "aa-t2", 400, 70, 6, 6, land=False, air=True)
        shot = {"directDamage": 200, "speed": .6, "targetSpeed": 8}
        sound, local_y, warmup = "missile_fire", 6, 25
    form["turret_1"] = {"x": 0, "y": 0, "projectile": "1", "canShoot": False,
                       "recoilOffset": -3, "recoilOutTime": 6, "recoilReturnTime": 12, "idleSweepAngle": 45,
                       "idleSweepDelay": 90 if flak else 110, "idleSweepSpeed": .3 if flak else .2}
    form["turret_2"] = {"x": 7, "y": local_y, "projectile": "1", "attachedTo": "1", "slave": True,
                       "invisible": True, "warmup": 0, "shoot_sound": sound, "shoot_sound_vol": .3}
    if flak:
        form["turret_2"]["size"] = 0
    form["turret_3"] = dict(form["turret_2"], x=-7, linkDelayWithTurret="2", warmup=warmup)
    form["projectile_1"] = dict(shot, life=240, autoTargetingOnDeadTarget=True)
    return form


def generate(destination=DESTINATION):
    assert not destination.exists(), "Existing owned input tree"
    for fmt in ("ini", "json", "hjson"):
        root = destination / fmt
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
        forms = (
            ("spider", opening()), ("empty", empty_slot()), ("gun", gun()),
            ("small-laser", small_laser(False)), ("small-laser-t2", small_laser(True)), ("gun-t2", gun_t2()),
            ("aa", anti_air()), ("aa-t2", advanced_anti_air(False)), ("flak", advanced_anti_air(True)),
        )
        for name, sections in forms:
            (spider / f"{name}.{fmt}").write_text(render(sections, fmt), encoding="utf8")
        for filename, kind, width, height in (
            ("body", "body", 81, 113), ("dead", "dead", 70, 90), ("shadow", "shadow", 81, 113),
            ("slot-ui", "body", 82, 114), ("leg", "turret", 10, 84), ("foot", "body", 17, 22),
            ("nano", "turret", 11, 19), ("gun", "turret", 14, 46), ("empty", "empty", 1, 1),
            ("small-laser", "turret", 8, 34), ("small-laser-t2", "turret", 14, 24), ("gun-t2", "turret", 16, 46),
            ("aa", "turret", 16, 26), ("aa-t2", "turret", 25, 24), ("flak", "turret", 29, 32),
        ):
            (spider / f"{filename}.png").write_bytes(png(kind, width, height))
        entries = [{"type": "mech", "file": f"units/mech.{fmt}"}]
        entries.extend({"type": "modular-spider", "file": f"units/spider/{name}.{fmt}"} for name, _ in forms)
        manifest = dict(format="rw-reforge.content-pack.v1", id=f"owned-modular-spider-start-{fmt}",
                        name="Owned Modular Spider opening", units=entries)
        (pack / "pack.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf8")
    (destination / "README.md").write_text("# Owned Modular Spider opening inputs\n\n"
        "Procedural spider sprites and the existing owned target and terrain. "
        "Generated with SimulationTest/tools/modular_spider_fixture.py.\n", encoding="utf8")


if __name__ == "__main__":
    generate()
