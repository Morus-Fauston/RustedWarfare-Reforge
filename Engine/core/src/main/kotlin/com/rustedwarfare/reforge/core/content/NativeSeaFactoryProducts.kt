package com.rustedwarfare.reforge.core.content

/** Native sea tiers consume declared products that can leave a water berth. */
internal object NativeSeaFactoryProducts {
    private val unitId = Regex("[A-Za-z][A-Za-z0-9_.-]*")

    fun validDeclaration(products: List<String>): Boolean =
        products.isNotEmpty() && products.distinct().size == products.size && products.all(unitId::matches)

    fun accepts(tier: Int, product: UnitDefinition): Boolean {
        val build = product.build ?: return false
        // The current native transport profile is shared by all Nautilus forms.
        // A native sea factory produces its submerged WATER form.
        if (product.transport == TransportProfile.NAUTILUS_SUBMERGED && product.movement.type != MovementType.WATER) return false
        return product.building == null && product.techLevel in 1..tier &&
            product.movement.type in setOf(MovementType.WATER, MovementType.HOVER, MovementType.OVER_CLIFF_WATER) &&
            product.movement.moveSpeed.isFinite() && product.movement.moveSpeed > 0.0 &&
            build.price >= 0L && build.rate.isFinite() && build.rate > 0f
    }
}
