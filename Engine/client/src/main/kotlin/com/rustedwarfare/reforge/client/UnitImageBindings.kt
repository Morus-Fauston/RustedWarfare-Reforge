package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.nio.file.Path
import java.util.Collections
import java.util.Locale

/** One address space for ordinary parts and independently pictured named turrets. */
internal data class UnitImagePart(val slot: UnitImageSlot, val weaponName: String? = null) {
    init { require(weaponName == null || slot == UnitImageSlot.TURRET) }
    val suffix: String get() = weaponName?.let { "weapons/${it.lowercase(Locale.ROOT)}/turret" }
        ?: slot.name.lowercase(Locale.ROOT)
}

internal data class BoundUnitImage<T>(val resource: T, val logicalSize: ImageLogicalSize?, val totalFrames: Int = 1)

/** Immutable effective layout. Transformations retain part identity, logical size and frame count together. */
internal class UnitImageBindings<T>(images: Map<UnitImagePart, BoundUnitImage<T>>) {
    val images: Map<UnitImagePart, BoundUnitImage<T>> = Collections.unmodifiableMap(LinkedHashMap(images))
    init {
        require(UnitImagePart(UnitImageSlot.BODY) in images) { "A unit requires a body image" }
        require(images.all { (part, image) -> image.totalFrames > 0 &&
            (part == UnitImagePart(UnitImageSlot.BODY) || image.totalFrames == 1) }) { "Invalid unit image frame count" }
    }

    operator fun get(slot: UnitImageSlot): BoundUnitImage<T>? = images[UnitImagePart(slot)]
    val body: BoundUnitImage<T> get() = requireNotNull(this[UnitImageSlot.BODY])
    val weapons: Map<String, BoundUnitImage<T>> get() = images.entries.filter { it.key.weaponName != null }
        .associate { requireNotNull(it.key.weaponName) to it.value }

    fun <R> map(transform: (UnitImagePart, BoundUnitImage<T>) -> BoundUnitImage<R>): UnitImageBindings<R> =
        UnitImageBindings(images.mapValues { (part, image) -> transform(part, image) })

    fun logicalSizes(): UnitImageLogicalSizes? = body.logicalSize?.let { size ->
        UnitImageLogicalSizes(size, this[UnitImageSlot.BACK]?.logicalSize, this[UnitImageSlot.TURRET]?.logicalSize,
            this[UnitImageSlot.DEAD]?.logicalSize, this[UnitImageSlot.LEG]?.logicalSize,
            this[UnitImageSlot.FOOT]?.logicalSize, this[UnitImageSlot.ROTOR]?.logicalSize,
            Collections.unmodifiableMap(weapons.mapNotNull { (name, image) -> image.logicalSize?.let { name to it } }.toMap()),
            this[UnitImageSlot.SHADOW]?.logicalSize, this[UnitImageSlot.SHIELD]?.logicalSize,
            this[UnitImageSlot.LEFT_WING]?.logicalSize, this[UnitImageSlot.RIGHT_WING]?.logicalSize)
    }
}

/** Compatibility adapters are the only place that enumerates the existing field-based interfaces. */
private fun <T> unitImageBindings(body: T, back: T?, turret: T?, dead: T?, totalFrames: Int,
    logical: UnitImageLogicalSizes?, leg: T?, foot: T?, rotor: T?, weapons: Map<String, T>, shadow: T? = null,
    shield: T? = null, leftWing: T? = null, rightWing: T? = null): UnitImageBindings<T> {
    val parts = linkedMapOf<UnitImagePart, BoundUnitImage<T>>()
    fun add(slot: UnitImageSlot, value: T?) {
        if (value != null) parts[UnitImagePart(slot)] = BoundUnitImage(value, logical?.forSlot(slot),
            if (slot == UnitImageSlot.BODY) totalFrames else 1)
    }
    add(UnitImageSlot.BODY, body); add(UnitImageSlot.BACK, back); add(UnitImageSlot.TURRET, turret)
    add(UnitImageSlot.DEAD, dead); add(UnitImageSlot.LEG, leg); add(UnitImageSlot.FOOT, foot); add(UnitImageSlot.ROTOR, rotor); add(UnitImageSlot.SHADOW, shadow)
    add(UnitImageSlot.SHIELD, shield)
    add(UnitImageSlot.LEFT_WING, leftWing); add(UnitImageSlot.RIGHT_WING, rightWing)
    weapons.forEach { (name, value) -> parts[UnitImagePart(UnitImageSlot.TURRET, name)] =
        BoundUnitImage(value, logical?.weaponTurrets?.get(name)) }
    return UnitImageBindings(parts)
}

internal fun VisualUnitImages.bindings() = unitImageBindings(body, back, turret, dead, totalFrames,
    logicalImages, leg, foot, rotor, weaponTurrets, shadow, shield, leftWing, rightWing)

internal fun VisualUnitResourceIds.bindings() = unitImageBindings(body, back, turret, dead, totalFrames,
    logicalImages, leg, foot, rotor, weaponTurrets, shadow, shield, leftWing, rightWing)

internal fun UnitImageBindings<Path>.visualImages() = VisualUnitImages(body.resource,
    this[UnitImageSlot.BACK]?.resource, this[UnitImageSlot.TURRET]?.resource, this[UnitImageSlot.DEAD]?.resource,
    body.totalFrames, logicalSizes(), this[UnitImageSlot.LEG]?.resource, this[UnitImageSlot.FOOT]?.resource,
    this[UnitImageSlot.ROTOR]?.resource, Collections.unmodifiableMap(weapons.mapValues { it.value.resource }), this[UnitImageSlot.SHADOW]?.resource,
    this[UnitImageSlot.SHIELD]?.resource, this[UnitImageSlot.LEFT_WING]?.resource, this[UnitImageSlot.RIGHT_WING]?.resource)

internal fun UnitImageBindings<ResourceId>.resourceIds() = VisualUnitResourceIds(body.resource,
    this[UnitImageSlot.BACK]?.resource, this[UnitImageSlot.TURRET]?.resource, this[UnitImageSlot.DEAD]?.resource,
    body.totalFrames, logicalSizes(), this[UnitImageSlot.LEG]?.resource, this[UnitImageSlot.FOOT]?.resource,
    this[UnitImageSlot.ROTOR]?.resource, Collections.unmodifiableMap(weapons.mapValues { it.value.resource }), this[UnitImageSlot.SHADOW]?.resource,
    this[UnitImageSlot.SHIELD]?.resource, this[UnitImageSlot.LEFT_WING]?.resource, this[UnitImageSlot.RIGHT_WING]?.resource)

internal fun UnitDefinition.imageBindings(): UnitImageBindings<Path> = unitImageBindings(imagePath,
    building?.backImagePath, combat?.turretImagePath, durability?.deadImagePath ?: combat?.deadImagePath,
    totalFrames, null, mech?.legs?.first()?.legImage, mech?.legs?.first()?.footImage, rotor?.imagePath,
    combat?.namedWeapons?.ordered.orEmpty().filter { it.imagePath != null && it.imagePath != combat?.turretImagePath }
        .associate { it.name to requireNotNull(it.imagePath) }, shadowImagePath, shieldImagePath,
    amphibiousJet?.leftWingImagePath, amphibiousJet?.rightWingImagePath)

internal fun UnitAppearance.imageBindings(): UnitImageBindings<Path> = unitImageBindings(imagePath,
    backImagePath, turretImagePath, deadImagePath, totalFrames, logicalImages, legImagePath, footImagePath, rotorImagePath,
    weaponImages.filterValues { it.imagePath != null && it.imagePath != turretImagePath }
        .mapValues { requireNotNull(it.value.imagePath) }, shadowImagePath, shieldImagePath, leftWingImagePath, rightWingImagePath)

internal fun UnitAppearance.withImages(images: UnitImageBindings<Path>): UnitAppearance = copy(
    imagePath = images.body.resource, totalFrames = images.body.totalFrames, logicalImages = images.logicalSizes(),
    backImagePath = images[UnitImageSlot.BACK]?.resource, turretImagePath = images[UnitImageSlot.TURRET]?.resource,
    deadImagePath = images[UnitImageSlot.DEAD]?.resource, legImagePath = images[UnitImageSlot.LEG]?.resource,
    footImagePath = images[UnitImageSlot.FOOT]?.resource, rotorImagePath = images[UnitImageSlot.ROTOR]?.resource,
    shadowImagePath = images[UnitImageSlot.SHADOW]?.resource,
    shieldImagePath = images[UnitImageSlot.SHIELD]?.resource,
    leftWingImagePath = images[UnitImageSlot.LEFT_WING]?.resource,
    rightWingImagePath = images[UnitImageSlot.RIGHT_WING]?.resource,
    weaponImages = Collections.unmodifiableMap(weaponImages.mapValues { (name, weapon) ->
        if (weapon.imagePath == null) weapon else {
            val part = UnitImagePart(UnitImageSlot.TURRET, name.takeUnless { weapon.imagePath == turretImagePath })
            val image = requireNotNull(images.images[part]) { "VISUAL_MAPPING_UNSUPPORTED: weapon $name" }
            weapon.copy(imagePath = image.resource, logicalSize = image.logicalSize)
        }
    }))
