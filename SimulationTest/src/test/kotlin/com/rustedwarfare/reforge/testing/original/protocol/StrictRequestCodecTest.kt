package com.rustedwarfare.reforge.testing.original.protocol

import kotlin.test.*

class StrictRequestCodecTest {
    private val literal="""{"kind":"reference-request","schemaVersion":1,"sessionId":"s-1","sequence":1,"requestId":"a","operation":"probe","payloadBase64":"QQ==","payloadSha256":"559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd"}"""
    private fun rejected(code:String,value:ByteArray) {assertEquals(code,assertFailsWith<CodecException> {StrictRequestCodec.decode(value)}.code)}
    private fun rejected(code:String,value:String)=rejected(code,value.toByteArray(Charsets.UTF_8))
    @Test fun jc02_utf8_and_json_fail_before_schema() {
        rejected("invalid-utf8",byteArrayOf(0xff.toByte()))
        rejected("invalid-utf8",byteArrayOf(0xc3.toByte()))
        rejected("invalid-json","{\"kind\":")
    }
    @Test fun jc03_schema_types_field_set_ids_and_range_are_strict() {
        for(value in listOf(literal.replace("\"schemaVersion\":1","\"schemaVersion\":2"),
            literal.replace("\"schemaVersion\":1","\"schemaVersion\":1.0"),
            literal.replace("\"sequence\":1","\"sequence\":true"),
            literal.replace("\"sequence\":1","\"sequence\":\"1\""),
            literal.replace("\"sequence\":1","\"sequence\":0"),
            literal.replace("\"sequence\":1","\"sequence\":9223372036854775807"),
            literal.replace("\"sequence\":1","\"sequence\":9223372036854775808"),
            literal.replace("\"sessionId\":\"s-1\"","\"sessionId\":\"中文\""),
            literal.replace("\"requestId\":\"a\"","\"requestId\":\"\""),
            literal.replace("\"operation\":\"probe\"","\"operation\":\"advance\""),
            literal.replace("\"kind\":\"reference-request\",",""),literal.dropLast(1)+",\"extra\":0}")) rejected("invalid-schema",value)
    }
    @Test fun jc04_payload_base64_and_hash_are_not_repaired() {
        for(value in listOf(literal.replace("QQ==","Qg=="),literal.replace("QQ==","QQ"),literal.replace("QQ==","?"),
            literal.replace("559aead0","559AEAD0"))) rejected("invalid-payload",value)
    }
    @Test fun jc05_canonical_bytes_reject_duplicate_keys_whitespace_order_and_BOM() {
        rejected("noncanonical-request",literal.replace("\"requestId\":\"a\"","\"requestId\":\"a\",\"requestId\":\"a\""))
        rejected("noncanonical-request",literal.replace("\"kind\":\"reference-request\",\"schemaVersion\":1","\"schemaVersion\":1,\"kind\":\"reference-request\""))
        rejected("noncanonical-request"," "+literal)
        rejected("noncanonical-request",literal+"\n")
        rejected("noncanonical-request",byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+literal.toByteArray(Charsets.UTF_8))
    }
    @Test fun jc06_envelope_and_input_bytes_have_no_mutable_aliases() {
        val bytes=literal.toByteArray(Charsets.UTF_8);val decoded=StrictRequestCodec.decode(bytes);bytes.fill(0)
        val returned=decoded.payload;returned[0]=88
        assertContentEquals(literal.toByteArray(Charsets.UTF_8),StrictRequestCodec.encode(decoded))
        val source=byteArrayOf(65);val envelope=RequestEnvelope("s-1",1,"a","probe",source);source[0]=88
        assertContentEquals(byteArrayOf(65),envelope.payload)
        val max=RequestEnvelope("s-1",Long.MAX_VALUE-1,"a","collect",byteArrayOf())
        assertEquals(Long.MAX_VALUE-1,StrictRequestCodec.decode(StrictRequestCodec.encode(max)).sequence)
    }

    @Test fun jc01_literal_A_envelope_round_trips_without_Java_ledger_dependency() {
        val envelope=StrictRequestCodec.decode(literal.toByteArray(Charsets.UTF_8))
        assertEquals("s-1",envelope.sessionId);assertEquals(1L,envelope.sequence)
        assertEquals("a",envelope.requestId);assertEquals("probe",envelope.operation)
        assertContentEquals(byteArrayOf(65),envelope.payload)
        assertContentEquals(literal.toByteArray(Charsets.UTF_8),StrictRequestCodec.encode(envelope))
    }
}
