"""Generate the owned normal opening pack for the modular spider."""
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

from aa_beam_fixture import png
from bunker_fixture import render


ROOT = Path(__file__).resolve().parents[2]
INPUTS = ROOT / "SimulationTest/public/inputs"
DESTINATION = INPUTS / "modular-spider-start"
SLOTS = ((-11, 32, 0), (11, 32, 0), (-11, 6, -90), (11, 6, 90), (-10, -20, 180), (11, -20, 180))
LEGS = ((-50, 20, -25, 10), (50, 20, 15, 10), (-35, 60, -15, 25), (35, 60, 15, 25),
        (-50, -30, -15, -25), (50, -30, 15, -25), (-35, -60, -15, -35), (35, -60, 15, -35))
CHASSIS_BUILD_TYPES = ("builder", "hovercraft", "c_turret_t1", "c_turret_t1_artillery", "extractorT1",
                       "fabricatorT1", "landFactory", "mechFactory", "airFactory", "seaFactory",
                       "laserDefence", "repairbay", "bomber")


def opening():
    sections = {
        "core": {"name": "modularSpider", "class": "CustomUnitMetadata", "price": 90000, "buildSpeed": .0002,
                 "maxHp": 10000, "radius": 49, "fogOfWarSightRange": 19, "transportSlotsNeeded": 14,
                 "isPickableStartingUnit": True, "startFallingWhenStartingUnit": True,
                 "maxShield": 5000, "shieldRegen": .3, "selfRegenRate": .1,
                 "canRepairBuildings": True, "canRepairUnits": True, "autoRepair": True,
                 "nanoRange": 220, "nanoRepairSpeed": .4, "nanoBuildSpeed": 8,
                 "nukeOnDeath": True, "nukeOnDeathRange": 200, "nukeOnDeathDamage": 4400,
                 "nukeOnDeathDisableWhenNoNuke": True,
                 "generation_credits": 30, "generation_delay": 40},
        "graphics": {"image": "body.png", "image_shadow": "shadow.png", "image_wreak": "dead.png",
                     "image_turret": "nano.png", "image_slotUi": "slot-ui.png", "total_frames": 1},
        "movement": {"movementType": "OVER_CLIFF_WATER", "moveSpeed": .6, "moveAccelerationSpeed": .015,
                     "moveDecelerationSpeed": .015, "maxTurnSpeed": .4, "turnAcceleration": .1,
                     "reverseSpeedPercentage": 1},
        "canBuild_chassis": dict(constructionTypes=",".join(CHASSIS_BUILD_TYPES), forceNano=True),
        "turret_nanoTurret": dict(warmup=30, isMainNanoTurret=True),
        "action_blinkQueued": dict(tags="blink", buildSpeed=210, allowMultipleInQueue=False,
                                   canPlayerCancel=False, isActive=False, highPriorityQueue=True,
                                   whenBuilding_cannotMove=True),
        "projectile_blink": dict(teleportSource=True, instant=True, directDamage=0,
                                 targetGround=True, shouldRevealFog=True),
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
        "action_buildLightning": {"convertTo": "modularSpider_lightning", "price": 6000, "buildSpeed": 0, "setBuilt": 0},
        "action_buildArtillery": {"convertTo": "modularSpider_artillery", "price": 5000, "buildSpeed": 0, "setBuilt": 0},
        "action_buildFabricator": {"convertTo": "modularSpider_fabricator", "price": 3000, "buildSpeed": 0, "setBuilt": 0},
        "action_buildShieldGen": {"convertTo": "modularSpider_shieldGen", "price": 6000, "buildSpeed": 0, "setBuilt": 0},
        "action_buildSpeed": {"convertTo": "modularSpider_speedIncomplete", "price": 6000, "buildSpeed": 0, "setBuilt": 0},
        "action_buildLaserdefense": {"convertTo": "modularSpider_laserdefense", "price": 3000, "buildSpeed": 0, "setBuilt": 0},
        "action_buildAntinuke": {"convertTo": "modularSpider_antinuke", "price": 7000, "buildSpeed": 0, "setBuilt": 0},
        "action_buildBlink": {"convertTo": "modularSpider_blink", "price": 15000, "buildSpeed": 0, "setBuilt": 0},
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


def lightning():
    form = module("lightning", 6000, 2000, "lightning", 290, "2.00s", 30, 2, land=True, air=False)
    form["turret_1"] = {"x": 0, "y": 0, "projectile": "1", "size": 20, "image_drawOffsetY": 9,
                       "shoot_sound": "lighting_burst", "shoot_sound_vol": .3, "canShoot": True, "warmup": 30,
                       "recoilOffset": -1.5, "recoilOutTime": 3, "recoilReturnTime": 17}
    form["projectile_1"] = {"directDamage": 130, "life": 25, "speed": 0, "instant": True, "lightingEffect": True,
                            "shieldDamageMultiplier": 1, "shieldDefectionMultiplier": .1, "buildingDamageMultiplier": .5}
    return form


def artillery():
    form = module("artillery", 5000, 3000, "artillery", 350, 85, 30, 2, land=True, air=False)
    timing = {"projectile": "plasma", "delay": 110, "warmup": 20, "turnSpeedAcceleration": .1,
              "recoilOutTime": 3, "recoilReturnTime": 38}
    form["turret_mainCannon"] = dict(timing, x=-.5, y=20, size=16, turnSpeed=0, attachedTo="mainCannonBase",
                                    slave=True, shoot_sound="large_gun_fire2", shoot_sound_vol=.3, recoilOffset=-5)
    form["turret_mainCannonBase"] = dict(timing, x=0, y=0, size=40, turnSpeed=2, canShoot=False,
                                        recoilOffset=-1, image="artillery-base.png")
    form["projectile_plasma"] = {"directDamage": 190, "life": 80, "speed": 16, "targetSpeed": 16,
                                "targetSpeedAcceleration": .7}
    return form


def utility(name, price, sprite):
    return {
        "core": {"name": "modularSpider_" + name, "class": "CustomUnitMetadata", "price": price,
                 "maxHp": 3000, "radius": 15, "displayRadius": 12, "isUnselectable": True,
                 "canNotBeDirectlyAttacked": False, "selfBuildRate": .001, "selfRegenRate": .2},
        "graphics": {"image": sprite + ".png", "total_frames": 1},
        "action_cancel": {"convertTo": "modularSpider_emptySlot", "refundCredits": price,
                          "addAllActionCooldownsTime": 120},
        "action_reclaim": {"convertTo": "modularSpider_emptySlot", "refundCredits": price // 2, "buildSpeed": 180},
    }


def fabricator(upgraded):
    name = "fabricatorT2" if upgraded else "fabricator"
    form = utility(name, 7000 if upgraded else 3000, "fabricator-t2" if upgraded else "fabricator")
    form["core"].update(generation_credits=10 if upgraded else 5, generation_delay=40)
    if not upgraded:
        form["action_upgradeT2"] = dict(upgrade("fabricatorT2", 5000), buildSpeed=480)
    return form


def shield_generator():
    form = utility("shieldGen", 6000, "shield")
    form["hiddenAction_regen"] = dict(autoTrigger="true", takeResources="shield=-30",
                                     takeResources_includeParent=True, takeResources_discardCollected=True)
    return form


def speed(incomplete):
    form = utility("speedIncomplete" if incomplete else "speed", 6000, "speed")
    if incomplete:
        form["hiddenAction_speedFinished"] = dict(autoTrigger="if self.completed", convertTo="modularSpider_speed")
    return form


def laser_defense():
    form = utility("laserdefense", 3000, "laser-defense")
    form["defenseEnergy"] = dict(energyMax=1, energyRegen=.0003)
    form["turret_laserDefence"] = dict(x=0, y=0, invisible=True, canShoot=False,
                                       laserDefenceEnergyUse=.05, limitingRange=160, size=1)
    return form


def anti_nuke():
    form = utility("antinuke", 7000, "anti-nuke")
    form["action_buildAntiNuke"] = dict(price=3000, buildSpeed=.0007, ammoLimit=12, addResources="ammo=1")
    form["action_isEnabled"] = dict(buildSpeed=0, addResources="setFlag=1", highPriorityQueue=True,
                                    canPlayerCancel=False, allowMultipleInQueue=False)
    form["action_isDisabled"] = dict(form["action_isEnabled"], addResources="unsetFlag=1")
    form["turret_silo"] = dict(x=0, y=0, size=1, invisible=True, interceptProjectiles_withTags="nuke",
        interceptProjectiles_andTargetingGroundUnderDistance=1000, interceptProjectiles_andUnderDistance=1500,
        interceptProjectiles_andOverHeight=50, resourceUsage="ammo=1, hasMissingFlag=1",
        shoot_sound="nuke_launch", shoot_sound_vol=.15)
    return form


def blink():
    form = utility("blink", 15000, "blink")
    form["action_blink"] = dict(buildSpeed=0, allowMultipleInQueue=False,
        fireTurretXAtGround="dummyBlink", fireTurretXAtGround_withProjectile="dummyBlink",
        fireTurretXAtGround_onlyOverPassableTileOf="HOVER", addActionCooldownTime=1800,
        highPriorityQueue=True, whenBuilding_cannotMove=True, sendMessageTo="parent",
        sendMessageWithTags="blink", sendMessageWithData="blinkTarget=thisActionTarget")
    form["turret_dummyBlink"] = dict(x=0, y=0, invisible=True, canShoot=False, limitingRange=700, size=1)
    form["projectile_dummyBlink"] = dict(instant=True, directDamage=0, targetGround=True)
    return form


def chassis_support(pack, fmt):
    """Copy the existing owned definition and its local dependencies into the opening pack."""
    groups = (
        ("economy", INPUTS / "three-format-economy/construction" / fmt / "units",
         {"extractor": "economic-building", "extractorT2": "economic-building", "extractorT3": "economic-building",
          "fabricatorT1": "economic-building", "fabricatorT2": "economic-building", "fabricatorT3": "economic-building",
          "landFactory": "land-factory", "laserDefence": "point-defense",
          "turret_t1": "ground-turret", "turret_t2_gun": "ground-turret", "turret_t1_artillery": "ground-turret"},
         {"extractor_common", "turret_common_land"}),
        ("mech-factory", INPUTS / "mech-engineer-production" / fmt / "production-package/units",
         {"builder": "builder", "mechFactory": "mech-factory", "mechFactoryT2": "mech-factory",
          "heavy/heavy": "mech-heavy", "engineer/engineer": "mech-engineer"}, set()),
        ("air-factory", INPUTS / "bomber-production" / fmt / "package",
         {"units/airFactory": "air-factory", "units/airFactoryT2": "air-factory",
          "units/helicopter": "helicopter", "bomber": "bomber"}, set()),
        ("sea-factory", INPUTS / "transport-production" / fmt / "production-package/units",
         {"seaFactory": "sea-factory", "seaFactoryT2": "sea-factory", "hovercraft": "hovercraft", "land/tank": "tank",
          "submarine/submarine": "submarine", "naval/heavyBattleship": "battleship"}, set()),
        ("repairbay", INPUTS / "repairbay-production" / fmt / "production-package",
         {"repairbay": "repair-bay"}, set()),
    )
    entries = []
    for group, source, units, templates in groups:
        target = pack / "units" / group
        for stem in (*units, *sorted(templates)):
            local = Path(f"{stem}.{fmt}")
            original = source / local
            destination = target / local
            destination.parent.mkdir(parents=True, exist_ok=True)
            data = original.read_bytes()
            if stem in ("turret_t1_artillery", "turret_t2_gun"):
                health, price = (1000, 2100) if stem == "turret_t1_artillery" else (1100, 1500)
                before, after = ((f"maxHp: {health}", f"maxHp: {health}\nprice: {price}\nbuildSpeed: 0.0008")
                                 if fmt == "ini" else (f'"maxHp":{health}', f'"maxHp":{health},"price":{price},"buildSpeed":0.0008'))
                text = data.decode("utf8")
                if before not in text and fmt != "ini":
                    before, after = f'"maxHp": {health}', f'"maxHp": {health}, "price": {price}, "buildSpeed": 0.0008'
                assert before in text
                data = text.replace(before, after, 1).encode("utf8")
            destination.write_bytes(data)
            for image in original.parent.glob("*.png"):
                if image.name.encode("utf8") in data:
                    shutil.copyfile(image, destination.parent / image.name)
            if stem in units:
                entries.append({"type": units[stem], "file": destination.relative_to(pack).as_posix()})
    return entries


def add_construction_sites(root, fmt):
    map_path = root / "maps/p001-controlled.tmx"
    scene = ET.parse(map_path)
    document = scene.getroot()
    layer = document.find("layer")
    shore = INPUTS / "transport-production" / fmt / "assets/naval-ground.png"
    shutil.copyfile(shore, root / "assets" / shore.name)
    tileset = ET.Element("tileset", firstgid="38", name="naval-ground", tilewidth="20", tileheight="20")
    ET.SubElement(tileset, "image", source="../assets/naval-ground.png", width="100", height="160")
    ET.SubElement(ET.SubElement(ET.SubElement(tileset, "tile", id="1"), "properties"), "property", name="water", value="")
    document.insert(list(document).index(layer), tileset)
    economy = INPUTS / "three-format-economy/construction" / fmt / "assets/terrain.png"
    shutil.copyfile(economy, root / "assets/foundation.png")
    foundations = ET.Element("tileset", firstgid="78", name="owned-foundations", tilewidth="20", tileheight="20")
    ET.SubElement(foundations, "image", source="../assets/foundation.png", width="100", height="20")
    for tile_id, property_name in ((1, "trees"), (2, "cliff"), (4, "res_pool")):
        tile = ET.SubElement(foundations, "tile", id=str(tile_id))
        ET.SubElement(ET.SubElement(tile, "properties"), "property", name=property_name, value="")
    document.insert(list(document).index(layer), foundations)
    data = layer.find("data")
    cells = data.text.strip().split(",")
    width = int(layer.get("width"))
    height = int(layer.get("height"))
    for row in range(height):
        cells[row * width + width - 5:(row + 1) * width] = ["39"] * 5
    cells[11 * width + 5] = "82"
    data.text = ",".join(cells)
    scene.write(map_path, encoding="utf-8")


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
        add_construction_sites(root, fmt)
        shutil.copyfile(source / "combat.properties", root / "combat.properties")
        for name in (f"mech.{fmt}", "body.png", "leg.png", "foot.png"):
            shutil.copyfile(source / "production-package/units" / name, units / name)
        forms = (
            ("spider", opening()), ("empty", empty_slot()), ("gun", gun()),
            ("small-laser", small_laser(False)), ("small-laser-t2", small_laser(True)), ("gun-t2", gun_t2()),
            ("aa", anti_air()), ("aa-t2", advanced_anti_air(False)), ("flak", advanced_anti_air(True)),
            ("lightning", lightning()), ("artillery", artillery()),
            ("fabricator", fabricator(False)), ("fabricator-t2", fabricator(True)),
            ("shield", shield_generator()), ("speed-incomplete", speed(True)), ("speed", speed(False)),
            ("laser-defense", laser_defense()), ("anti-nuke", anti_nuke()), ("blink", blink()),
        )
        for name, sections in forms:
            (spider / f"{name}.{fmt}").write_text(render(sections, fmt), encoding="utf8")
        for filename, kind, width, height in (
            ("body", "body", 81, 113), ("dead", "dead", 70, 90), ("shadow", "shadow", 81, 113),
            ("slot-ui", "body", 82, 114), ("leg", "turret", 10, 84), ("foot", "body", 17, 22),
            ("nano", "turret", 11, 19), ("gun", "turret", 14, 46), ("empty", "empty", 1, 1),
            ("small-laser", "turret", 8, 34), ("small-laser-t2", "turret", 14, 24), ("gun-t2", "turret", 16, 46),
            ("aa", "turret", 16, 26), ("aa-t2", "turret", 25, 24), ("flak", "turret", 29, 32),
            ("lightning", "turret", 22, 31), ("artillery", "turret", 7, 35), ("artillery-base", "body", 20, 19),
            ("fabricator", "body", 20, 19), ("fabricator-t2", "dead", 20, 19),
            ("shield", "shadow", 22, 21), ("speed", "turret", 22, 21),
            ("laser-defense", "body", 20, 19), ("anti-nuke", "dead", 22, 21), ("blink", "shadow", 22, 21),
        ):
            (spider / f"{filename}.png").write_bytes(png(kind, width, height))
        entries = [{"type": "mech", "file": f"units/mech.{fmt}"}]
        entries.extend(chassis_support(pack, fmt))
        entries.extend({"type": "modular-spider", "file": f"units/spider/{name}.{fmt}"} for name, _ in forms)
        manifest = dict(format="rw-reforge.content-pack.v1", id=f"owned-modular-spider-start-{fmt}",
                        name="Owned Modular Spider opening", units=entries)
        (pack / "pack.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf8")
    (destination / "README.md").write_text("# Owned Modular Spider opening inputs\n\n"
        "Procedural spider sprites and the existing owned target and terrain. "
        "Generated with SimulationTest/tools/modular_spider_fixture.py.\n", encoding="utf8")


if __name__ == "__main__":
    generate()
