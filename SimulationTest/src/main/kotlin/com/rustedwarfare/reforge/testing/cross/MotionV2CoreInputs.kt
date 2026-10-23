package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.testing.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.*
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException

/** Explicit qualification inputs only. This is not formal Sources admission. */
internal object MotionV2CoreInputs {
    class Loaded private constructor(val map: MapDefinition,val unit: UnitDefinition,val evidence: JsonObject,
                                     private val identities: List<JsonObject>) {
        fun verifyStable(): List<String> = identities.mapNotNull { r ->
            try { checked(r);null } catch(e:Exception) { "${r["path"]}: ${e.message}" }
        }
        companion object {
            fun open(manifest: Path, request: Path): Loaded {
                val identities=mutableListOf<JsonObject>()
                fun bound(r:JsonObject):Path=checked(r).also { identities+=r }
                fun read(r:JsonObject)=readObject(bound(r))
                val manifestRef=identity(manifest)
                val m=read(manifestRef);m.header("motion-core-input-qualification")
                require(m.keys==setOf("kind","schemaVersion","formalCompatibility","caseId","runId","request","map","unit","files"))
                require(m.required("formalCompatibility")==b(false))
                val requestRef=m.objectAt("request")
                require(requestRef.text("path")==request.toAbsolutePath().toString())
                val q=read(requestRef);q.header("motion-diagnostic-request")
                require(q.keys==setOf("kind","schemaVersion","formalCompatibility","caseId","runId","preparedInputs","catalog","original"))
                require(q.required("formalCompatibility")==b(false) && q.required("caseId")==m.required("caseId") && q.required("runId")==m.required("runId"))
                val run=m.text("runId");require(UUID.fromString(run).toString()==run)
                val case=m.text("caseId")
                require(case in setOf("OM00","OM01E","OM01W","OM02S","OM02N","OM03SE","OM03NW","OM04","OM05","OM06","OM07","OM08"))
                val prepared=read(q.objectAt("preparedInputs"))
                bound(q.objectAt("catalog"));bound(q.objectAt("original"))
                val rows=prepared.arrayAt("cases").map { it.jsonObject }.filter { it.text("caseId")==case }
                require(rows.size==1 && rows.single().required("core")==m.required("map") && prepared.required("coreUnit")==m.required("unit"))
                val declarations=m.arrayAt("files").map { it.jsonObject }
                val byPath=linkedMapOf<Path,JsonObject>()
                for(r in declarations) require(byPath.put(bound(r),r)==null) { "Duplicate input path" }
                val mapPath=Path.of(m.objectAt("map").text("path"));val unitPath=Path.of(m.objectAt("unit").text("path"))
                require(byPath[mapPath]==m.required("map") && byPath[unitPath]==m.required("unit"))
                fun declared(p:Path):Path { require(p in byPath) { "Undeclared actual dependency: $p" };return bound(byPath.getValue(p)) }
                val mapXml=xml(declared(mapPath));require(mapXml.tagName=="map")
                val tsx=children(mapXml,"tileset").map { element ->
                    require(element.hasAttribute("source")) { "Qualification supports the fixed external tilesets" }
                    relative(mapPath,element.getAttribute("source"))
                }.toSet()
                val images=tsx.flatMap { p ->
                    val tile=xml(declared(p));require(tile.tagName=="tileset")
                    val image=children(tile,"image");require(image.size==1)
                    listOf(relative(p,image.single().getAttribute("source"))).also { it.forEach(::declared) }
                }.toSet()
                val map=content(ContentLoader.loadMap(declared(mapPath)))
                val unit=content(ContentLoader.loadUnitVariant(declared(unitPath)))
                require(map.tileSets.map { it.imagePath }.toSet()==images) { "Loaded atlas path mismatch" }
                declared(unit.imagePath)
                val expected=setOf(mapPath,unitPath,unit.imagePath)+tsx+images
                require(byPath.keys==expected) { "Declared files differ from the actual dependency union" }
                require(map.widthTiles==map.blocking.widthTiles && map.heightTiles==map.blocking.heightTiles)
                val bits=buildString { for(y in 0 until map.heightTiles) for(x in 0 until map.widthTiles) append(if(map.blocking.isBlocked(x,y)) '1' else '0') }
                val movement=unit.movement
                val evidence=record("qualified-core-input-observation","formalCompatibility" to b(false),"caseId" to m.required("caseId"),
                    "runId" to m.required("runId"),"manifest" to manifestRef,"request" to requestRef,"map" to m.required("map"),
                    "unitInput" to m.required("unit"),"files" to array(declarations),
                    "terrain" to obj("width" to n(map.widthTiles),"height" to n(map.heightTiles),"tileWidth" to n(map.tileWidth),
                        "tileHeight" to n(map.tileHeight),"bits" to s(bits),"bitsSha256" to s(digest(bits.toByteArray(Charsets.US_ASCII)))),
                    "unit" to obj("id" to s(unit.id),"radius" to n(unit.radius),"movement" to obj("type" to s(movement.type.name),
                        "speed" to n(movement.moveSpeed),"acceleration" to n(movement.moveAccelerationSpeed),"deceleration" to n(movement.moveDecelerationSpeed),
                        "turnSpeed" to n(movement.maxTurnSpeed),"turnAcceleration" to n(movement.turnAcceleration),
                        "sliding" to b(movement.slidingMode),"ignoringBody" to b(movement.ignoringBody))))
                return Loaded(map,unit,evidence,identities.distinct())
            }
        }
    }
    fun load(manifest: Path, request: Path) = Loaded.open(manifest,request)
    fun reference(path:Path):JsonObject=identity(path)

    private fun path(path:Path):Path {
        require(path.isAbsolute && path==path.normalize()) { "Expected absolute normalized input path" }
        var cursor=checkNotNull(path.root)
        for(part in path) {
            cursor=cursor.resolve(part)
            val a=Files.readAttributes(cursor,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            require(!a.isSymbolicLink && !a.isOther) { "Reparse input component: $cursor" }
            // Compare to reject a junction; never replace the original path with
            // its followed target before checking and reading the declaration.
            require(cursor.toRealPath()==cursor) { "Redirected input component: $cursor" }
            require(if(cursor==path) a.isRegularFile else a.isDirectory)
        }
        return path
    }
    private fun checked(r:JsonObject):Path {
        require(r.keys==setOf("path","bytes","sha256"))
        val p=path(Path.of(r.text("path")))
        require(r.integer("bytes")>=0 && Files.size(p)==r.integer("bytes") && r.text("sha256").matches(Regex("[0-9a-f]{64}")) && sha256(p)==r.text("sha256")) { "Input identity changed: $p" }
        return p
    }
    private fun identity(p:Path):JsonObject {
        val absolute=path(p.toAbsolutePath())
        return obj("path" to s(absolute.toString()),"bytes" to n(Files.size(absolute)),"sha256" to s(sha256(absolute)))
    }
    private fun relative(parent:Path,literal:String):Path {
        require(literal.isNotBlank())
        val p=Path.of(literal);require(!p.isAbsolute)
        return parent.parent.resolve(p).normalize()
    }
    private fun children(e:Element,name:String)=(0 until e.childNodes.length).map { e.childNodes.item(it) }
        .filterIsInstance<Element>().filter { it.tagName==name }
    private fun xml(p:Path):Element {
        val factory=DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"")
        factory.isXIncludeAware=false;factory.isExpandEntityReferences=false
        val builder=factory.newDocumentBuilder()
        builder.setErrorHandler(object:ErrorHandler {
            override fun warning(e:SAXParseException) { throw e }
            override fun error(e:SAXParseException) { throw e }
            override fun fatalError(e:SAXParseException) { throw e }
        })
        return builder.parse(ByteArrayInputStream(Files.readAllBytes(p))).documentElement
    }
    private fun <T> content(r:ContentResult<T>):T=when(r) {
        is ContentResult.Success -> r.value
        is ContentResult.Failure -> throw InputError("Qualified input load failed: ${r.error}")
    }
}
