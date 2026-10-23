package com.rustedwarfare.reforge.testing.p002

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*

/** Explicit development identity. Historical schema1 never inherits these semantics. */
internal object DevelopmentKnowledgeProtocol {
    const val HOST_CONTRACT="0bcd2233692e728704ed640c06a546b40a735fcd8442941a466614f560c5937c"
    const val AUTOMATIC_CONTRACT="783db5a9bd65da49e0b2b6c58d2d32d31e76fc78d335c2fdf2d7a1d2209b16bb"
    val identity=obj("motionRulesVersion" to s("p002.dev.center-motion.v2"),"developmentOnly" to b(true),
        "qualificationScope" to s("development-knowledge-vision-only"),"originalCompatibility" to b(false),"fullTerrainWritebackQualified" to b(false))
    fun selected(suite:JsonObject):Boolean {
        if(suite.integer("schemaVersion")==1L) {
            if(identity.keys.any { it in suite })throw InputError("Historical suite cannot select development rules")
            return false
        }
        if(suite.text("kind")!="p002-knowledge-suite" || suite.integer("schemaVersion")!=2L)throw InputError("Unsupported development suite protocol")
        checkIdentity(suite)
        val expected=when(suite.text("scope")) {
            "trusted-host-projection"->listOf("p002.knowledge-host.development.center.v2","1ee42053c482b6ab7ca5bb41fa323b474c497f36b12360e6ef545432497f4b6d",
                "baaa71de09fdea40b9017598967b66a7df5236d941b6e881055ac63c2d6b4bbc",HOST_CONTRACT)
            "automatic-vision"->listOf("p002.automatic-vision.development.center.v2","80d9717afe14de87351b934e0ebf9ada997f0075ef1da56479a6406bd400dc9c",
                "75a9de388c14767d8df19147eae83bdbbc50dc36e469bb55dff90cea95d82eee",AUTOMATIC_CONTRACT)
            else->throw InputError("Unknown development knowledge scope")
        }
        if(listOf(suite.text("suiteId"),suite.text("historicalSuiteSha256"),suite.objectAt("scenario").text("sha256"),suite.objectAt("contract").text("sha256"))!=expected)
            throw InputError("Unknown development suite or frozen input identity")
        return true
    }
    fun checkIdentity(value:JsonObject) {
        if(identity.any { (key,expected)->value[key]!=expected })throw InputError("Development knowledge identity differs")
    }
    fun checkRecord(value:JsonObject,development:Boolean) {
        if(development)checkIdentity(value)
        else if(identity.keys.any { it in value })throw InputError("Development records cannot qualify historical semantics")
    }
    fun tag(value:JsonObject,development:Boolean)=if(development)JsonObject(value+identity)else value
    fun contract(value:JsonObject,development:Boolean) {
        if(!development) {
            value.header("p002-knowledge-contract")
            if(identity.keys.filter { it!="originalCompatibility" }.any { it in value })throw InputError("Development contract cannot qualify historical semantics")
        }else {
            if(value.text("kind")!="p002-knowledge-contract" || value.integer("schemaVersion")!=2L)throw InputError("Development contract version differs")
            checkIdentity(value)
            val historical=when(value.text("scope")) {
                "trusted-host-projection"->"bfeaf66e2a7abf13a4332815ee7b4827d46e55c836c17e0f87694a95b20d0384"
                "automatic-vision"->"2f3c7a09c15ad8199faadaee79d49bfa13f2407d0a2e4119df903971929ca6a1"
                else->throw InputError("Unknown development contract scope")
            }
            if(value.text("historicalContractSha256")!=historical)throw InputError("Development contract history differs")
        }
    }
}
