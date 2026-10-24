package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/** Literal tool evidence exercises the public file entry; it is never original runtime evidence. */
class OriginalMotionComparisonTest {
    private val folder = Files.createTempDirectory("reforge-o-cross-")
    private val root = Path.of(System.getProperty("repo.root"))
    private val referenceRoot = System.getProperty("workflow.reference.root")?.let(Path::of) ?: root
    private fun evidence(role: String): String = """
        {"kind":"motion-evidence","schemaVersion":1,"role":"$role","runId":"fixture-$role","sourceKind":"tool-fixture","complete":true,"exitCode":0,
        "samples":[
          {"index":0,"q":0,"phase":0,"inputSequence":-1,"state":{"id":"u1","owner":"p1","definition":"tank","x":300,"y":200,"heading":90,"target":null},"raw":{"fixture":"initial"}},
          {"index":1,"q":1,"phase":0,"inputSequence":-1,"state":{"id":"u1","owner":"p1","definition":"tank","x":300,"y":200,"heading":90,"target":null},"raw":{"fixture":"step1"}},
          {"index":2,"q":6,"phase":0,"inputSequence":-1,"state":{"id":"u1","owner":"p1","definition":"tank","x":300,"y":200,"heading":90,"target":null},"raw":{"fixture":"step6"}},
          {"index":3,"q":12,"phase":0,"inputSequence":-1,"state":{"id":"u1","owner":"p1","definition":"tank","x":300,"y":200,"heading":90,"target":null},"raw":{"fixture":"step12"}},
          {"index":4,"q":18,"phase":0,"inputSequence":-1,"state":{"id":"u1","owner":"p1","definition":"tank","x":300,"y":200,"heading":90,"target":null},"raw":{"fixture":"step18"}}
        ],"inputs":[],"recordCount":5}
    """.trimIndent().let { text ->
        val source = json.parseToJsonElement(text).jsonObject
        val literal = source.arrayAt("samples")[0].jsonObject
        JsonObject(source + mapOf("recordCount" to n(19), "samples" to array((0..18).map { q ->
            JsonObject(literal + mapOf("index" to n(q), "q" to n(q), "raw" to obj("fixture" to s("stationary-$q"))))
        }))).toString()
    }+"\n"

    private fun contract(actual: Path, expected: Path, caseId: String = "OM00"): Path {
        val standard = root.resolve("TestLab/standards/P002-original-motion/O.v1.json")
        val c = obj("kind" to s("motion-cross-contract"), "schemaVersion" to n(1),
            "scope" to s("original-motion-v1"), "contractId" to s("fixture.om00"), "caseId" to s(caseId),
            "standard" to obj("path" to s(standard.toString()), "sha256" to s(sha256(standard))),
            "actual" to obj("sha256" to s(sha256(actual)),"runId" to s("fixture-core")),
            "expected" to obj("sha256" to s(sha256(expected)),"runId" to s("fixture-original")))
        return folder.resolve("contract-${System.nanoTime()}.json").also { Files.writeString(it,c.toString()+"\n") }
    }

    @Test fun `approved O static literal control uses cross file entry without claiming native compatibility`() {
        val actual = folder.resolve("actual.json").also { Files.writeString(it,evidence("core")) }
        val expected = folder.resolve("expected.json").also { Files.writeString(it,evidence("original")) }
        val result = CrossComparison.compare(contract(actual,expected),actual,expected)
        assertEquals(0,result.exitCode,result.document.toString())
        assertEquals(s("p002.original-motion.O.v1"),result.document["standardId"])
        assertEquals(b(false),result.document["originalCompatibility"])
    }

    private fun straightEvidence(role: String,startX: Int = 300,goalX: Int = 500): String {
        val source = json.parseToJsonElement(evidence(role)).jsonObject
        val distance=goalX-startX
        val points = (0..distance+18).map { q -> obj("index" to n(q),"q" to n(q),"phase" to n(0),"inputSequence" to n(0),
            "state" to obj("id" to s("u1"),"owner" to s("p1"),"definition" to s("tank"),
                "x" to n(startX+minOf(q,distance)),"y" to n(200),"heading" to n(90),
                "target" to if(q<distance) obj("x" to n(goalX),"y" to n(200)) else JsonNull),
            "raw" to obj("fixture" to s("literal-line-$q"))) }
        return JsonObject(source+mapOf("samples" to array(points),"recordCount" to n(points.size),
            "inputs" to array(listOf(obj("sequence" to n(0),"q" to n(0),"action" to s("move"),
                "target" to obj("x" to n(goalX),"y" to n(200)),"raw" to obj("fixture" to s("literal-move"))))))).toString()+"\n"
    }

    @Test fun `aligned straight literal traces compare fixed points and retain arrival and settle events`() {
        val actual = folder.resolve("line-core.json").also { Files.writeString(it,straightEvidence("core")) }
        val expected = folder.resolve("line-original.json").also { Files.writeString(it,straightEvidence("original")) }
        val result = CrossComparison.compare(contract(actual,expected,"OM01E"),actual,expected)
        assertEquals(0,result.exitCode,result.document.toString())
        assertTrue(result.document.objectAt("details").arrayAt("points").size > 30)
        assertTrue(result.document.objectAt("details").objectAt("events").containsKey("arrival"))
    }

    @Test fun `identical trajectories crossing a wall fail full frequency geometry despite zero cross difference`() {
        val actual=folder.resolve("wall-core.json").also{Files.writeString(it,straightEvidence("core",150,450))}
        val expected=folder.resolve("wall-original.json").also{Files.writeString(it,straightEvidence("original",150,450))}
        val result=CrossComparison.compare(contract(actual,expected,"OM07"),actual,expected)
        assertEquals(2,result.exitCode,result.document.toString())
        assertEquals(s("swept-obstacle"),result.document["category"])
        assertTrue(result.document.objectAt("firstDivergence").containsKey("raw"))
        val diagnostic=result.document.objectAt("details").objectAt("experienceDiagnostics")
        assertTrue(diagnostic.objectAt("actual").number("routeLength")>0)
        assertEquals(JsonNull,diagnostic["routeLengthRatio"])
    }

    @Test fun `arrival case requires a sustained plateau followed by confirmed deceleration`() {
        fun trace(role: String): String {
            val source=json.parseToJsonElement(straightEvidence(role,300,360)).jsonObject
            val samples=source.arrayAt("samples").map { value->
                val row=value.jsonObject;val q=row.number("q");val state=row.objectAt("state")
                val x=when{q<=50->300+1.1*q;q<=60->355+.5*(q-50);else->360.0}
                JsonObject(row+mapOf("state" to JsonObject(state+mapOf("x" to n(x)))))
            }
            return JsonObject(source+mapOf("samples" to array(samples))).toString()
        }
        val actual=folder.resolve("decel-core.json").also{Files.writeString(it,trace("core"))}
        val expected=folder.resolve("decel-original.json").also{Files.writeString(it,trace("original"))}
        val result=CrossComparison.compare(contract(actual,expected,"OM06"),actual,expected)
        assertEquals(0,result.exitCode,result.document.toString())
        val event=result.document.objectAt("details").objectAt("events").objectAt("arrival-deceleration").objectAt("actual")
        assertEquals(50.0,event.number("lowQ"));assertEquals(51.0,event.number("highQ"));assertEquals(52L,event.integer("confirmedIndex"))
    }

    @Test fun `hard missing capture crash and identity failures cannot become a sparse pass`() {
        val original=folder.resolve("negative-original.json").also{Files.writeString(it,evidence("original"))}
        val base=json.parseToJsonElement(evidence("core")).jsonObject
        val cases=listOf(
            Triple("missing-step",JsonObject(base+mapOf("samples" to array(base.arrayAt("samples").filterIndexed{i,_->i!=7}),"recordCount" to n(18))),3),
            Triple("incomplete",JsonObject(base+mapOf("complete" to b(false))),3),
            Triple("crash",JsonObject(base+mapOf("exitCode" to n(1))),4),
            Triple("identity",JsonObject(base+mapOf("role" to s("original"))),4)
        )
        for((name,document,code)in cases){
            val actual=folder.resolve("negative-$name.json").also{Files.writeString(it,document.toString())}
            val result=CrossComparison.compare(contract(actual,original),actual,original)
            assertEquals(code,result.exitCode,"$name: ${result.document}")
            assertEquals(b(false),result.document["originalCompatibility"])
        }
    }

    @Test fun `core O adapter runs fixed public steps and retains every raw DTO`() {
        val original=folder.resolve("core-replay-original-fixture.json").also{Files.writeString(it,evidence("original"))}
        val standard=root.resolve("TestLab/standards/P002-original-motion/O.v1.json")
        val prepared=referenceRoot.resolve(".scratch/开发票据/测试系统/P002-原版参考与规则性能/evidence/TEST-P002-022/o-approved-20261021/prepared-inputs.json")
        fun ref(p: Path)=obj("path" to s(p.toString()),"sha256" to s(sha256(p)))
        val request=folder.resolve("core-request.json").also{Files.writeString(it,obj("kind" to s("motion-core-request"),"schemaVersion" to n(1),
            "caseId" to s("OM00"),"runId" to s(java.util.UUID.randomUUID().toString()),"standard" to ref(standard),
            "preparedInputs" to ref(prepared),"original" to ref(original)).toString())}
        val output=folder.resolve("core-output.json")
        val capture=MotionCoreCapture.capture(request,output)
        assertEquals(19L,capture.integer("recordCount"));assertEquals(s("core-runtime"),capture["sourceKind"])
        assertTrue(capture.arrayAt("inputs").isEmpty())
        for((i,value)in capture.arrayAt("samples").withIndex()){
            val row=value.jsonObject;assertEquals(i.toDouble(),row.number("q"))
            assertEquals(300.0,row.objectAt("state").number("x"));assertEquals(200.0,row.objectAt("state").number("y"))
            assertEquals(i.toDouble(),row.objectAt("raw").objectAt("observation").number("simulationTime"))
        }
        assertFailsWith<java.nio.file.FileAlreadyExistsException>{MotionCoreCapture.capture(request,output)}
    }

    @Test fun `approved obstacle control accepts different legal routes and reports route time ratios`() {
        fun around(role: String,top: Int): String {
            val source=json.parseToJsonElement(straightEvidence(role,150,450)).jsonObject
            val rise=200-top;val end=2*rise+300
            val samples=(0..end+18).map{q->
                val x=when{q<rise->150; q<rise+300->150+q-rise;else->450}
                val y=when{q<rise->200-q;q<rise+300->top;q<end->top+q-rise-300;else->200}
                obj("index" to n(q),"q" to n(q),"phase" to n(0),"inputSequence" to n(0),"raw" to obj("fixture" to s("around-$q")),
                    "state" to obj("id" to s("u1"),"owner" to s("p1"),"definition" to s("tank"),"x" to n(x),"y" to n(y),"heading" to n(90),
                        "target" to if(q<end)obj("x" to n(450),"y" to n(200))else JsonNull))
            }
            return JsonObject(source+mapOf("samples" to array(samples),"recordCount" to n(samples.size))).toString()
        }
        val actual=folder.resolve("around-core.json").also{Files.writeString(it,around("core",80))}
        val expected=folder.resolve("around-original.json").also{Files.writeString(it,around("original",100))}
        val result=CrossComparison.compare(contract(actual,expected,"OM07"),actual,expected)
        assertEquals(0,result.exitCode,result.document.toString())
        assertEquals(1.08,result.document.objectAt("details").number("routeLengthRatio"),.0001)
    }

    @Test fun `core replay submits zero q first input immediately and ceil later input without advancing to consume`() {
        val standard=root.resolve("TestLab/standards/P002-original-motion/O.v1.json")
        val prepared=referenceRoot.resolve(".scratch/开发票据/测试系统/P002-原版参考与规则性能/evidence/TEST-P002-022/o-approved-20261021/prepared-inputs-v2.json")
        val base=json.parseToJsonElement(evidence("original")).jsonObject
        val commands=listOf(obj("sequence" to n(0),"q" to n(0),"action" to s("move"),"target" to obj("x" to n(500),"y" to n(200)),"raw" to obj()),
            obj("sequence" to n(1),"q" to n(7.2),"action" to s("stop"),"target" to JsonNull,"raw" to obj()))
        val original=folder.resolve("command-boundary-fixture.json").also{Files.writeString(it,JsonObject(base+mapOf("inputs" to array(commands))).toString())}
        fun ref(p: Path)=obj("path" to s(p.toString()),"sha256" to s(sha256(p)))
        val request=folder.resolve("command-boundary-request.json").also{Files.writeString(it,obj("kind" to s("motion-core-request"),"schemaVersion" to n(1),
            "caseId" to s("OM05"),"runId" to s(java.util.UUID.randomUUID().toString()),"standard" to ref(standard),"preparedInputs" to ref(prepared),"original" to ref(original)).toString())}
        val result=MotionCoreCapture.capture(request,folder.resolve("command-boundary-result.json"))
        val inputs=result.arrayAt("inputs").map{it.jsonObject}
        assertEquals(listOf(0.0,8.0),inputs.map{it.number("q")})
        for(input in inputs){assertEquals(input.number("q"),input.objectAt("raw").objectAt("before").number("simulationTime"));assertEquals(input.number("q"),input.objectAt("raw").objectAt("after").number("simulationTime"))}
        assertEquals(21L,result.integer("recordCount"))
        val atZero=result.arrayAt("samples").map{it.jsonObject}.filter{it.number("q")==0.0}
        assertEquals(2,atZero.size);assertEquals(JsonNull,atZero.first().objectAt("state")["target"]);assertNotEquals(JsonNull,atZero.last().objectAt("state")["target"])
    }

    @Test fun `actual native static raw capture and actual core replay can pass only through pinned raw revalidation`() {
        val batch=referenceRoot.resolve(".scratch/开发票据/测试系统/P002-原版参考与规则性能/evidence/TEST-P002-022/o-approved-20261021")
        val original=batch.resolve("scene-run-b28dacdf-ca46-4da8-aea0-3ddb9cbebeca/original-motion-evidence-v3.json")
        val standard=root.resolve("TestLab/standards/P002-original-motion/O.v1.json")
        fun ref(p: Path)=obj("path" to s(p.toString()),"sha256" to s(sha256(p)))
        val coreRun=java.util.UUID.randomUUID().toString()
        val request=folder.resolve("native-static-replay-request.json").also{Files.writeString(it,obj("kind" to s("motion-core-request"),"schemaVersion" to n(1),
            "caseId" to s("OM00"),"runId" to s(coreRun),"standard" to ref(standard),"preparedInputs" to ref(batch.resolve("prepared-inputs-v2.json")),"original" to ref(original)).toString())}
        val core=folder.resolve("native-static-core.json");MotionCoreCapture.capture(request,core)
        val native=OriginalMotionComparison.read(original)
        val document=obj("kind" to s("motion-cross-contract"),"schemaVersion" to n(1),"scope" to s("original-motion-v1"),"contractId" to s("actual.om00"),"caseId" to s("OM00"),
            "standard" to ref(standard),"actual" to obj("sha256" to s(sha256(core)),"runId" to s(coreRun)),
            "expected" to obj("sha256" to s(sha256(original)),"runId" to native.required("runId")),
            "runtimeVerifier" to ref(Path.of("C:/Users/46348/AppData/Local/Programs/Python/Python313/python.exe")))
        val contract=folder.resolve("native-static-contract.json").also{Files.writeString(it,document.toString())}
        val result=CrossComparison.compare(contract,core,original)
        assertEquals(0,result.exitCode,result.document.toString());assertEquals(b(true),result.document["originalCompatibility"])
        assertEquals(s("compatibility-passed"),result.document["result"])
        val missing=folder.resolve("native-missing-verifier.json").also{Files.writeString(it,JsonObject(document.filterKeys{it!="runtimeVerifier"}).toString())}
        val refused=CrossComparison.compare(missing,core,original)
        assertEquals(3,refused.exitCode,refused.document.toString());assertEquals(b(false),refused.document["originalCompatibility"])
        val otherPrepared=folder.resolve("other-prepared.json").also{Files.writeString(it,OriginalMotionComparison.read(batch.resolve("prepared-inputs-v2.json")).toString())}
        val wrongRequest=folder.resolve("wrong-prepared-request.json").also{Files.writeString(it,JsonObject(OriginalMotionComparison.read(request)+mapOf("preparedInputs" to ref(otherPrepared))).toString())}
        val identityFailure=assertFailsWith<MotionFailure>{MotionCoreCapture.capture(wrongRequest,folder.resolve("must-not-capture.json"))}
        assertEquals("core-prepared-identity",identityFailure.category)
    }

    @Test fun `static later heading is diagnostic only while initial heading remains exact`() {
        val source=json.parseToJsonElement(evidence("core")).jsonObject
        val altered=JsonObject(source+mapOf("samples" to array(source.arrayAt("samples").mapIndexed{i,v->
            val row=v.jsonObject;JsonObject(row+mapOf("state" to JsonObject(row.objectAt("state")+mapOf("heading" to n(if(i==0)90 else 270)))))
        })))
        val actual=folder.resolve("static-heading-core.json").also{Files.writeString(it,altered.toString())}
        val expected=folder.resolve("static-heading-original.json").also{Files.writeString(it,evidence("original"))}
        val result=CrossComparison.compare(contract(actual,expected),actual,expected)
        assertEquals(0,result.exitCode,result.document.toString())
        assertEquals(1L,result.document.objectAt("details").objectAt("metrics").objectAt("heading").integer("count"))
    }

    @Test fun `wrong input target and timing preserve original command raw values`() {
        val expected=folder.resolve("input-negative-original.json").also{Files.writeString(it,straightEvidence("original"))}
        for((name,changes)in listOf("target" to mapOf("target" to obj("x" to n(490),"y" to n(200))),"timing" to mapOf("q" to n(1)))) {
            val base=json.parseToJsonElement(straightEvidence("core")).jsonObject
            val input=base.arrayAt("inputs").single().jsonObject
            val actual=folder.resolve("input-negative-$name.json").also{Files.writeString(it,JsonObject(base+mapOf("inputs" to array(listOf(JsonObject(input+changes))))).toString())}
            val result=CrossComparison.compare(contract(actual,expected,"OM01E"),actual,expected)
            assertEquals(2,result.exitCode,result.document.toString())
            assertEquals(s("literal-move"),result.document.objectAt("firstDivergence").objectAt("expected").objectAt("raw")["fixture"])
        }
    }

    @Test fun `deceleration platform survives transition band and never selects a later first low interval`() {
        fun events(speeds: List<Double>): Map<String,MotionInterval> {
            var x=300.0
            val points=listOf(MotionPoint(0,0.0,0,0,x,200.0,90.0,obj("x" to n(360),"y" to n(200)),obj()))+
                speeds.mapIndexed{i,v->x+=v;MotionPoint(i+1,(i+1).toDouble(),0,0,x,200.0,90.0,obj("x" to n(360),"y" to n(200)),obj())}
            return MotionRules.events(MotionTrace("core",points,obj("inputs" to array(emptyList()))),obj("category" to s("arrival-deceleration-settle"),"move" to array(listOf(n(360),n(200)))))
        }
        val observed=events(List(7){1.1}+listOf(1.04,1.02,1.02))["arrival-deceleration"]!!
        assertEquals(8.0,observed.low);assertEquals(9.0,observed.high);assertEquals(10,observed.confirmed)
        assertNull(events(List(7){1.1}+listOf(1.04,1.02,1.04,1.02,1.02))["arrival-deceleration"])
    }

    @Test fun `unreachable legal cancellation allows different fallback lengths without reachable ratio gate`() {
        fun cancelled(role: String,distance: Int): String {
            val base=json.parseToJsonElement(straightEvidence(role,150,410)).jsonObject
            val samples=(0..distance+18).map{q->obj("index" to n(q),"q" to n(q),"phase" to n(0),"inputSequence" to n(0),"raw" to obj("fixture" to s("cancel-$q")),
                "state" to obj("id" to s("u1"),"owner" to s("p1"),"definition" to s("tank"),"x" to n(150+minOf(q,distance)),"y" to n(200),"heading" to n(90),
                    "target" to if(q<distance)obj("x" to n(410),"y" to n(200))else JsonNull))}
            return JsonObject(base+mapOf("samples" to array(samples),"recordCount" to n(samples.size))).toString()
        }
        val actual=folder.resolve("cancel-core.json").also{Files.writeString(it,cancelled("core",20))}
        val expected=folder.resolve("cancel-original.json").also{Files.writeString(it,cancelled("original",100))}
        val result=CrossComparison.compare(contract(actual,expected,"OM08"),actual,expected)
        assertEquals(0,result.exitCode,result.document.toString());assertEquals(b(false),result.document.objectAt("details")["ratioGateApplied"])
        assertEquals(JsonNull,result.document.objectAt("details")["routeLengthRatio"])
    }

    @Test fun `local event width insufficiency cannot hide confirmed numeric failure and cannot grant a pass`() {
        fun trace(role: String,offset: Double): MotionTrace {
            val times=if(role=="original")listOf(0.0,1.5)+(2..218).map{it.toDouble()}else(0..218).map{it.toDouble()}
            val points=times.mapIndexed{i,q->val x=300+minOf(q,200.0)+(if(q>=2)offset else 0.0)
                MotionPoint(i,q,0,0,x,200.0,90.0,if(q<200)obj("x" to n(500),"y" to n(200))else JsonNull,obj("fixture" to s("width-$q")))}
            val input=obj("sequence" to n(0),"q" to n(0),"action" to s("move"),"target" to obj("x" to n(500),"y" to n(200)),"raw" to obj())
            return MotionTrace(role,points,obj("inputs" to array(listOf(input))))
        }
        val case=obj("id" to s("OM01E"),"category" to s("aligned-horizontal"),"move" to array(listOf(n(500),n(200))))
        val unavailable=MotionRules.compare(case,trace("core",0.0),trace("original",0.0))
        assertEquals(3,unavailable.code);assertTrue(unavailable.details.arrayAt("unavailableComparisons").isNotEmpty())
        val failed=MotionRules.compare(case,trace("core",5.0),trace("original",0.0))
        assertEquals(2,failed.code);assertNotEquals(JsonNull,failed.first)
        assertTrue(failed.details.arrayAt("unavailableComparisons").isNotEmpty())
    }

    @Test fun `native deceleration confirmation uses its own pre post despite an intervening zero delta displacement`() {
        var x=300.0
        val target=obj("x" to n(360),"y" to n(200))
        val points=mutableListOf(MotionPoint(0,0.0,0,0,x,200.0,90.0,target,obj()))
        for((i,speed)in (List(7){1.1}+listOf(1.04,1.02)).withIndex()){x+=speed;points+=MotionPoint(i+1,(i+1).toDouble(),0,0,x,200.0,90.0,target,obj())}
        // The extra observed zero-I state is retained. The next positive callback's pre is authoritative.
        x+=5;points+=MotionPoint(10,9.0,0,0,x,200.0,90.0,target,obj())
        val values=MutableList(50){n(0)};values[9]=n(x.toFloat().toRawBits());values[10]=n(200f.toRawBits());values[11]=n(0f.toRawBits());values[19]=n(1)
        values[22]=n(360f.toRawBits());values[23]=n(200f.toRawBits());values[30]=n(1f.toRawBits())
        points+=MotionPoint(11,10.0,0,0,x+1.02,200.0,90.0,target,obj("raw" to obj("values" to array(values))))
        val case=obj("category" to s("arrival-deceleration-settle"),"move" to array(listOf(n(360),n(200))))
        val event=MotionRules.events(MotionTrace("original",points,obj("inputs" to array(emptyList()))),case)["arrival-deceleration"]!!
        assertEquals(9.0,event.high);assertEquals(11,event.confirmed)
    }

    @Test fun `partial metric RMSE cannot prove failure of the missing fixed full set`() {
        fun trace(role: String): MotionTrace {
            val times=if(role=="original")listOf(0,3,24)else(0..24).toList()
            val points=times.mapIndexed{i,q->MotionPoint(i,q.toDouble(),0,0,300.0,200.0,if(role=="core" && q>0)97.0 else 90.0,
                obj("x" to n(500),"y" to n(200)),obj("fixture" to s("partial-$q")))}
            return MotionTrace(role,points,obj("inputs" to array(listOf(obj("sequence" to n(0),"q" to n(0),"action" to s("move"),"target" to obj("x" to n(500),"y" to n(200)),"raw" to obj())))))
        }
        val case=obj("id" to s("OM01E"),"category" to s("aligned-horizontal"),"move" to array(listOf(n(500),n(200))))
        val result=MotionRules.compare(case,trace("core"),trace("original"))
        assertEquals(3,result.code,result.toString());assertEquals(JsonNull,result.first)
        val heading=result.details.objectAt("metrics").objectAt("heading")
        assertTrue(heading.number("rmse")>5);assertEquals(b(false),heading["completeMetricCoverage"])
    }
}
