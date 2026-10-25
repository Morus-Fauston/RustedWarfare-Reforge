package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Private tiny inputs, never original assets or a formal capture fixture. */
class MotionV2CoreInputsTest {
    @TempDir lateinit var directory: Path
    private fun identity(p:Path)=obj("path" to s(p.toAbsolutePath().normalize().toString()),"bytes" to n(Files.size(p)),"sha256" to s(sha256(p)))
    internal data class Fixture(val manifest:Path,val request:Path,val map:Path,val unit:Path,val tsx:Path,val png:Path)
    internal fun fixture():Fixture {
        val png=directory.resolve("atlas.png")
        Files.write(png,ByteBuffer.allocate(24).putLong(0x89504e470d0a1a0auL.toLong()).putInt(13).putInt(0x49484452).putInt(40).putInt(20).array())
        val tsx=directory.resolve("tile.tsx")
        Files.writeString(tsx,"""<tileset name="private" tilewidth="20" tileheight="20" tilecount="2" columns="2"><image source="atlas.png" width="40" height="20"/><tile id="1"><properties><property name="trees" value=""/></properties></tile></tileset>""")
        val map=directory.resolve("map.tmx")
        Files.writeString(map,"""<map orientation="orthogonal" renderorder="right-down" width="3" height="2" tilewidth="20" tileheight="20"><tileset firstgid="1" source="tile.tsx"/><layer name="Ground" width="3" height="2"><data encoding="csv">1,2,1,2,1,2</data></layer></map>""")
        val unit=directory.resolve("unit.ini")
        Files.writeString(unit,"""
            [core]
            name: private_fixture
            class: CustomUnitMetadata
            radius: 1
            displayRadius: 1
            [graphics]
            total_frames: 1
            image: atlas.png
            [movement]
            movementType: LAND
            moveSpeed: 1.1
            moveAccelerationSpeed: 0.07
            moveDecelerationSpeed: 0.17
            maxTurnSpeed: 4.1
            turnAcceleration: 0.25
            moveSlidingMode: false
            moveIgnoringBody: false
        """.trimIndent())
        val prepared=directory.resolve("prepared.json")
        Files.writeString(prepared,obj("cases" to array(listOf(obj("caseId" to s("OM00"),"core" to identity(map)))),"coreUnit" to identity(unit)).toString())
        val catalog=directory.resolve("catalog.json");Files.writeString(catalog,"{}")
        val original=directory.resolve("original.json");Files.writeString(original,"{}")
        val request=directory.resolve("request.json")
        val run="55628806-760b-4ec8-a46b-4e26f2cfdcaa"
        Files.writeString(request,record("motion-diagnostic-request","formalCompatibility" to b(false),"caseId" to s("OM00"),"runId" to s(run),
            "preparedInputs" to identity(prepared),"catalog" to identity(catalog),"original" to identity(original)).toString())
        val manifest=directory.resolve("manifest.json")
        Files.writeString(manifest,record("motion-core-input-qualification","formalCompatibility" to b(false),"caseId" to s("OM00"),"runId" to s(run),
            "request" to identity(request),"map" to identity(map),"unit" to identity(unit),
            "files" to array(listOf(map,unit,tsx,png).map(::identity))).toString())
        return Fixture(manifest,request,map,unit,tsx,png)
    }

    @Test fun actual_loaded_non_square_truth_and_profile_share_one_declared_png() {
        val f=fixture();val loaded=MotionV2CoreInputs.load(f.manifest,f.request)
        assertEquals(3,loaded.map.widthTiles);assertEquals(2,loaded.map.heightTiles)
        assertFalse(loaded.map.blocking.isBlocked(0,0));assertTrue(loaded.map.blocking.isBlocked(1,0))
        val terrain=loaded.evidence.objectAt("terrain")
        assertEquals("010101",terrain.text("bits"))
        assertEquals(digest("010101".toByteArray(Charsets.US_ASCII)),terrain.text("bitsSha256"))
        assertEquals(1.0,loaded.unit.radius)
        assertEquals(.17,loaded.evidence.objectAt("unit").objectAt("movement").number("deceleration"))
        assertEquals(4,loaded.evidence.arrayAt("files").size)
        assertTrue(loaded.verifyStable().isEmpty())
    }

    @Test fun actual_rejected_submission_is_recorded_without_claiming_consumption_or_advancing() {
        val f=fixture();val loaded=MotionV2CoreInputs.load(f.manifest,f.request)
        val scenario=Scenario("private.reject",loaded.map,mapOf("tank" to loaded.unit),listOf(Player("p1",PlayerControl.LOCAL)),
            listOf(UnitSpawn("u1","p1","tank",Position(10.0,10.0),0.0)),
            CoordinateModel("p001.coordinate.v1","2d","x-right","y-down","top-left","world-unit","double","ieee754-binary64"),
            TimeModel("p001.time.v1","sim-tick","ieee754-binary64","fixed-explicit-delta"),MovementContract(16.0,4.0))
        val context=assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            val attempts=mutableListOf<JsonElement>();val original=obj("q" to n(0),"action" to s("stop"),"target" to JsonNull)
            assertFails { MotionDiagnosticCapture.submitAndRecord(context,StopAction("diag.0","unknown","u1",context.world),original,0,0,attempts) }
            assertEquals(1,attempts.size)
            val actual=attempts.single().jsonObject
            assertEquals(b(true),actual.required("attempted"));assertEquals(0L,actual.integer("sequence"));assertEquals(0L,actual.integer("q"))
            assertEquals("rejected",actual.objectAt("submitResult").text("status"))
            assertEquals("unknown-actor",actual.objectAt("submitResult").objectAt("error").text("code"))
            assertEquals(original,actual.required("originalInput"))
            val issued=actual.objectAt("issuedAction")
            assertEquals("diag.0",issued.text("actionId"));assertEquals("unknown",issued.text("actorPlayerId"))
            assertEquals("u1",issued.text("unitId"));assertEquals(context.world.id,issued.text("worldId"))
            assertEquals(JsonNull,issued.required("atStep"));assertEquals("stop",issued.text("kind"))
            val before=actual.objectAt("before");val after=actual.objectAt("after")
            assertEquals(before.required("units"),after.required("units"))
            assertEquals(0.0,before.number("simulationTime"));assertEquals(0.0,after.number("simulationTime"))
            assertEquals(0.0,context.observe().simulationTime)
            val accepted=MotionDiagnosticCapture.submitAndRecord(context,StopAction("diag.1","p1","u1",context.world),original,1,0,attempts)
            assertEquals("diag.1",accepted.actionId);assertEquals(2,attempts.size)
            assertEquals("accepted",attempts.last().jsonObject.objectAt("submitResult").text("status"))
            assertEquals("rejected",attempts.first().jsonObject.objectAt("submitResult").text("status"))
        } finally { context.close() }
    }

    @Test fun unsupported_production_actions_are_rejected_before_the_movement_capture_submits_them() {
        val f = fixture(); val loaded = MotionV2CoreInputs.load(f.manifest, f.request)
        val scenario = Scenario("private.production-reject", loaded.map, mapOf("tank" to loaded.unit),
            listOf(Player("p1", PlayerControl.LOCAL)), listOf(UnitSpawn("u1", "p1", "tank", Position(10.0,10.0),0.0)),
            CoordinateModel("p001.coordinate.v1","2d","x-right","y-down","top-left","world-unit","double","ieee754-binary64"),
            TimeModel("p001.time.v1","sim-tick","ieee754-binary64","fixed-explicit-delta"), MovementContract(16.0,4.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            val actions = listOf(ProduceAction("unsupported-pay", "p1", "u1", "tank", "0".repeat(32), context.world),
                CancelProductionAction("unsupported-cancel", "p1", "u1", "tank", context.world))
            for (action in actions) {
                val before = context.observe()
                val attempts = mutableListOf<JsonElement>()
                assertFails { MotionDiagnosticCapture.submitAndRecord(context, action,
                    obj("q" to n(0),"action" to s("stop")), 0, 0, attempts) }
                assertSame(before, context.observe(), "Unsupported capture must not register or reject a gameplay action")
                assertTrue(attempts.isEmpty())
                assertIs<ActionResult.Accepted>(context.submit(SelectAction(action.actionId, "p1", "u1", context.world)),
                    "The unsupported adapter call must not consume the gameplay action ID")
            }
        } finally { context.close() }
    }

    @Test fun legacy_capture_keeps_strict_request_fields() {
        val f=fixture();val q=readObject(f.request)
        Files.writeString(f.request,JsonObject(q+("sourceManifest" to identity(f.manifest))).toString())
        val raw=directory.resolve("raw.json")
        val error=assertFails { MotionDiagnosticCapture.capture(f.request,raw) }
        assertContains(error.message.orEmpty(),"Unsupported O policy/field")
        assertFalse(Files.exists(raw));assertFalse(Files.exists(directory.resolve("raw.json.input-failure.json")))
    }

    @Test fun bound_capture_does_not_bypass_original_semantic_gate_and_retains_loaded_input_facts() {
        val f=fixture();val raw=directory.resolve("raw.json")
        var callbackCalled=false
        assertFails { MotionDiagnosticCapture.captureWithInputs(f.request,raw,f.manifest) { callbackCalled=true } }
        assertFalse(callbackCalled);assertFalse(Files.exists(raw))
        val failure=readObject(directory.resolve("raw.json.input-failure.json"))
        assertEquals("original",failure.text("stage"))
        assertEquals(b(true),failure.required("validationObserverUsed"))
        assertEquals(identity(f.request),failure.required("request"));assertEquals(identity(f.manifest),failure.required("manifest"))
        assertEquals("010101",failure.objectAt("inputObservation").objectAt("terrain").text("bits"))
        assertEquals(JsonNull,failure.required("initialization"));assertEquals(JsonNull,failure.required("raw"))
        assertEquals(b(false),failure.required("rawWritten"));assertEquals(JsonNull,failure.required("partialRaw"))
        assertEquals(0,failure.arrayAt("inputAttempts").size)
        assertEquals(b(false),failure.required("formalCompatibility"))
    }

    private fun manifest(f:Fixture,changes:Map<String,JsonElement>) {
        Files.writeString(f.manifest,JsonObject(readObject(f.manifest)+changes).toString())
    }
    internal fun refresh(f:Fixture) {
        val q=readObject(f.request);val prepared=Path.of(q.objectAt("preparedInputs").text("path"))
        Files.writeString(prepared,obj("cases" to array(listOf(obj("caseId" to s("OM00"),"core" to identity(f.map)))),"coreUnit" to identity(f.unit)).toString())
        Files.writeString(f.request,JsonObject(q+("preparedInputs" to identity(prepared))).toString())
        val m=readObject(f.manifest)
        manifest(f,mapOf("request" to identity(f.request),"map" to identity(f.map),"unit" to identity(f.unit),
            "files" to array(m.arrayAt("files").map { identity(Path.of(it.jsonObject.text("path"))) })))
    }

    @Test fun identical_bytes_at_another_tileset_path_do_not_bind_the_actual_reference() {
        val f=fixture();val other=directory.resolve("p001-tile.tsx");Files.copy(f.tsx,other)
        assertEquals(sha256(f.tsx),sha256(other))
        manifest(f,mapOf("files" to array(listOf(f.map,f.unit,other,f.png).map(::identity))))
        val error=assertFails { MotionV2CoreInputs.load(f.manifest,f.request) }
        assertContains(error.message.orEmpty(),"Undeclared actual dependency")
    }

    @Test fun dependency_union_rejects_missing_extra_duplicate_and_unbound_unit_image() {
        val f=fixture();val baseline=Files.readString(f.manifest)
        val extra=directory.resolve("extra.png");Files.copy(f.png,extra)
        val paths=listOf(f.map,f.unit,f.tsx,f.png)
        val variants=listOf(paths.filter { it!=f.tsx },paths.filter { it!=f.png },paths+listOf(extra),paths+listOf(f.png))
        assertEquals(listOf(3,3,5,5),variants.map { it.size })
        for(files in variants) {
            Files.writeString(f.manifest,baseline);manifest(f,mapOf("files" to array(files.map(::identity))))
            assertFails { MotionV2CoreInputs.load(f.manifest,f.request) }
        }
        Files.writeString(f.manifest,baseline)
        Files.writeString(f.unit,Files.readString(f.unit).replace("image: atlas.png","image: extra.png"));refresh(f)
        assertContains(assertFails { MotionV2CoreInputs.load(f.manifest,f.request) }.message.orEmpty(),"Undeclared actual dependency")
        manifest(f,mapOf("files" to array((paths+listOf(extra)).map(::identity))))
        val good=MotionV2CoreInputs.load(f.manifest,f.request)
        assertEquals(extra,good.unit.imagePath)
        assertTrue(good.verifyStable().isEmpty())
    }

    @Test fun manifest_identity_and_prepared_binding_are_not_only_same_case_names() {
        val f=fixture();val baseline=Files.readString(f.manifest)
        val mapCopy=directory.resolve("copy.tmx");Files.copy(f.map,mapCopy)
        val unitCopy=directory.resolve("copy.ini");Files.copy(f.unit,unitCopy)
        val requestCopy=directory.resolve("copy-request.json");Files.copy(f.request,requestCopy)
        val wrongRequest=JsonObject(identity(f.request)+("sha256" to s("0".repeat(64))))
        for(changes in listOf(mapOf("caseId" to s("OM01E")),mapOf("runId" to s("c0a0f0ad-a7f8-4cb9-99c9-7cae2f9bccaa")),
            mapOf("request" to wrongRequest),mapOf("request" to identity(requestCopy)),mapOf("map" to identity(mapCopy)),
            mapOf("unit" to identity(unitCopy)),mapOf("unexpected" to b(true)))) {
            Files.writeString(f.manifest,baseline);manifest(f,changes)
            assertFails { MotionV2CoreInputs.load(f.manifest,f.request) }
        }
    }

    @Test fun changed_inputs_keep_old_loaded_facts_and_new_objects_reflect_actual_truth_and_profile() {
        val f=fixture();val old=MotionV2CoreInputs.load(f.manifest,f.request)
        Files.writeString(f.tsx,Files.readString(f.tsx).replace("<properties><property name=\"trees\" value=\"\"/></properties>",""))
        Files.writeString(f.unit,Files.readString(f.unit).replace("radius: 1","radius: 2").replace("moveDecelerationSpeed: 0.17","moveDecelerationSpeed: 0.2"))
        assertTrue(old.verifyStable().isNotEmpty())
        assertEquals("010101",old.evidence.objectAt("terrain").text("bits"))
        assertTrue(old.map.blocking.isBlocked(1,0));assertEquals(1.0,old.unit.radius)
        refresh(f)
        val new=MotionV2CoreInputs.load(f.manifest,f.request)
        assertEquals("000000",new.evidence.objectAt("terrain").text("bits"))
        assertFalse(new.map.blocking.isBlocked(1,0));assertEquals(2.0,new.unit.radius)
        assertEquals(.2,new.evidence.objectAt("unit").objectAt("movement").number("deceleration"))
        assertTrue(new.verifyStable().isEmpty())
        assertTrue(old.verifyStable().isNotEmpty())
    }

    @Test fun mismatched_bytes_non_normalized_components_and_external_doctypes_are_rejected() {
        val f=fixture();val baseline=Files.readString(f.manifest)
        val records=readObject(f.manifest).arrayAt("files").map { it.jsonObject }
        for(bad in listOf(JsonObject(identity(f.tsx)+("bytes" to n(Files.size(f.tsx)+1))),
            JsonObject(identity(f.tsx)+("path" to s(directory.resolve("sub/../tile.tsx").toString()))))) {
            manifest(f,mapOf("files" to array(records.map { if(it.text("path")==f.tsx.toString()) bad else it })))
            assertFails { MotionV2CoreInputs.load(f.manifest,f.request) }
            Files.writeString(f.manifest,baseline)
        }
        Files.writeString(f.map,"<!DOCTYPE map SYSTEM \"file:///not-a-declared-input.dtd\">"+Files.readString(f.map));refresh(f)
        val error=assertFails { MotionV2CoreInputs.load(f.manifest,f.request) }
        assertContains(error.message.orEmpty(),"DOCTYPE")
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    fun actual_windows_junction_component_is_rejected_before_dependency_selection() {
        val f=fixture();val target=Files.createDirectory(directory.resolve("target"))
        val saved=target.resolve("tile.tsx");Files.copy(f.tsx,saved);val hash=sha256(saved)
        val link=directory.resolve("redirect")
        val process=ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start()
        val finished=process.waitFor(10,TimeUnit.SECONDS)
        if(!finished) { process.destroyForcibly();error("Private junction creation timed out") }
        val output=process.inputStream.bufferedReader().readText()
        assertEquals(0,process.exitValue(),output)
        try {
            val alias=link.resolve("tile.tsx")
            manifest(f,mapOf("files" to array(listOf(f.map,f.unit,alias,f.png).map(::identity))))
            val error=assertFails { MotionV2CoreInputs.load(f.manifest,f.request) }
            assertTrue(error.message.orEmpty().contains("Reparse input component") || error.message.orEmpty().contains("Redirected input component"),error.toString())
            assertEquals(hash,sha256(saved))
        } finally {
            // Delete only this one generated junction, never recurse into its target.
            check(link.toAbsolutePath().normalize().parent==directory.toAbsolutePath().normalize())
            Files.delete(link)
        }
        assertEquals(hash,sha256(saved))
    }
}
