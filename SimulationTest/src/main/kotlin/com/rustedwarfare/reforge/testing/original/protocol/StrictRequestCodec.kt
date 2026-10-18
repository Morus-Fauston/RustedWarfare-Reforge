package com.rustedwarfare.reforge.testing.original.protocol

import kotlinx.serialization.json.*
import java.util.Base64
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException

class CodecException(val code:String,message:String,cause:Throwable?=null):IllegalArgumentException(message,cause)

/** Host envelope only. Java ledger integration is a separate explicit controller responsibility. */
class RequestEnvelope(val sessionId:String,val sequence:Long,val requestId:String,val operation:String,payload:ByteArray) {
    init { validateEnvelope(sessionId,sequence,requestId,operation) }
    private val content=payload.copyOf()
    val payload:ByteArray get()=content.copyOf()
}

private fun validateEnvelope(sessionId:String,sequence:Long,requestId:String,operation:String) {
    val id=Regex("[A-Za-z0-9._-]+")
    if(!id.matches(sessionId)||!id.matches(requestId)||sequence<=0||sequence==Long.MAX_VALUE||operation !in setOf("probe","collect"))
        throw CodecException("invalid-schema","Invalid request identity, operation or sequence")
}

object StrictRequestCodec {
    private val json=Json { isLenient=false }
    fun encode(request:RequestEnvelope):ByteArray {
        val payload=request.payload
        return buildJsonObject {
            put("kind","reference-request");put("schemaVersion",1)
            put("sessionId",request.sessionId);put("sequence",request.sequence);put("requestId",request.requestId);put("operation",request.operation)
            put("payloadBase64",Base64.getEncoder().encodeToString(payload));put("payloadSha256",hash(payload))
        }.toString().toByteArray(Charsets.UTF_8)
    }
    fun decode(bytes:ByteArray):RequestEnvelope {
        val text=try {Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()}
            catch(error:CharacterCodingException){throw CodecException("invalid-utf8","Request is not strict UTF-8",error)}
        if(text.startsWith('\uFEFF'))throw CodecException("noncanonical-request","UTF-8 BOM is not allowed")
        val parsed=try {json.parseToJsonElement(text)}catch(error:IllegalArgumentException){throw CodecException("invalid-json","Malformed request JSON",error)}
        val value=parsed as? JsonObject ?: throw CodecException("invalid-schema","Expected request object")
        val expected=setOf("kind","schemaVersion","sessionId","sequence","requestId","operation","payloadBase64","payloadSha256")
        if(value.keys!=expected)throw CodecException("invalid-schema","Unknown or missing request field")
        fun string(key:String):String {
            val p=value[key] as? JsonPrimitive ?: throw CodecException("invalid-schema","Expected string $key")
            if(!p.isString)throw CodecException("invalid-schema","Expected string $key")
            return p.content
        }
        fun integer(key:String):Long {
            val p=value[key] as? JsonPrimitive ?: throw CodecException("invalid-schema","Expected integer $key")
            if(p.isString||!Regex("-?(0|[1-9][0-9]*)").matches(p.content))throw CodecException("invalid-schema","Expected integer $key")
            return p.longOrNull ?: throw CodecException("invalid-schema","Integer overflow $key")
        }
        if(string("kind")!="reference-request"||integer("schemaVersion")!=1L)throw CodecException("invalid-schema","Unknown kind or version")
        val session=string("sessionId");val sequence=integer("sequence");val id=string("requestId");val operation=string("operation")
        validateEnvelope(session,sequence,id,operation)
        val base64=string("payloadBase64");val sha=string("payloadSha256")
        val payload=try {Base64.getDecoder().decode(base64)}catch(error:IllegalArgumentException){throw CodecException("invalid-payload","Invalid Base64",error)}
        if(Base64.getEncoder().encodeToString(payload)!=base64||!Regex("[0-9a-f]{64}").matches(sha)||hash(payload)!=sha)
            throw CodecException("invalid-payload","Noncanonical Base64 or payload hash mismatch")
        val request=RequestEnvelope(session,sequence,id,operation,payload)
        if(!encode(request).contentEquals(bytes))throw CodecException("noncanonical-request","Request does not match canonical encoding")
        return request
    }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
}
