package com.rustedwarfare.reforge.core.content

/** Ordered subsets of the production menu supported by the finite mech factory. */
internal object MechFactoryProducts {
    fun qualifies(products: List<String>, upgraded: Boolean): Boolean {
        val required = if (upgraded) listOf("builder", "mechGun", "mechHeavyMissile") else listOf("builder", "mechGun")
        val optional = if (upgraded) listOf("mechBunker", "mechEngineer", "mechFlame") else listOf("mechBunker")
        return products == required + optional.filter(products::contains)
    }
}
