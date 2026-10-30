package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.content.internal.readNativeFields
import com.rustedwarfare.reforge.core.content.internal.readSectionFields
import java.nio.file.Files
import kotlin.test.*

class RestrictedFieldsTest {
    @Test fun sectioned_duplicates_and_unknown_keys_keep_exact_diagnostics() {
        val path = Files.createTempFile("restricted-section-errors-", ".ini")
        val allowed = mapOf("core" to setOf("name"))
        data class Case(val input: String, val code: ContentErrorCode, val field: String?, val reason: String)
        try {
            for ((input, code, field, reason) in listOf(
                Case("[core]\nname: first\nname: second\n", ContentErrorCode.MALFORMED_SYNTAX, "name", "Duplicate field"),
                Case("[core]\n[core]\n", ContentErrorCode.MALFORMED_SYNTAX, null, "Duplicate section"),
                Case("[core]\nunknown: 1\n", ContentErrorCode.UNSUPPORTED_FIELD, "unknown", "Unsupported turret field"))) {
                Files.writeString(path, input)
                val error = assertFailsWith<ContentReadException> {
                    readSectionFields(path, allowed, "turret", trimSectionName = true, reportCurrentSection = true)
                }.error
                assertEquals(code, error.code)
                assertEquals(path.toAbsolutePath().toString(), error.file)
                assertEquals("core", error.section)
                assertEquals(field, error.field)
                assertEquals(reason, error.reason)
            }
        } finally { Files.delete(path) }
    }

    @Test fun sectioned_fields_preserve_the_two_existing_header_and_error_location_policies() {
        val path = Files.createTempFile("restricted-sections-", ".ini")
        val allowed = mapOf("core" to setOf("name"))
        try {
            Files.writeString(path, "[ core ]\nname: i:unit\n")
            assertEquals(mapOf("core" to mapOf("name" to "i:unit")),
                readSectionFields(path, allowed, "turret", trimSectionName = true, reportCurrentSection = true))
            val economic = assertFailsWith<ContentReadException> {
                readSectionFields(path, allowed, "economic", trimSectionName = false, reportCurrentSection = false)
            }.error
            assertEquals(ContentErrorCode.UNSUPPORTED_FIELD, economic.code)
            assertEquals(" core ", economic.section)
            Files.writeString(path, "[core]\ninvalid\n")
            val turret = assertFailsWith<ContentReadException> {
                readSectionFields(path, allowed, "turret", trimSectionName = true, reportCurrentSection = true)
            }.error
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX, turret.code)
            assertEquals("core", turret.section)
            assertEquals("Invalid field at line 2", turret.reason)
            val noSection = assertFailsWith<ContentReadException> {
                readSectionFields(path, allowed, "economic", trimSectionName = false, reportCurrentSection = false)
            }.error
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX, noSection.code)
            assertNull(noSection.section)
        } finally { Files.delete(path) }
    }

    @Test fun native_fields_keep_literal_values_and_reject_duplicate_keys_with_their_source_identity() {
        val path = Files.createTempFile("restricted-fields-", ".properties")
        try {
            Files.writeString(path, "# note\nname = builder\nvalue = left=right\n")
            assertEquals(mapOf("name" to "builder", "value" to "left=right"),
                readNativeFields(path, setOf("name", "value"), "builder"))
            Files.writeString(path, "name=builder\nname=builder\n")
            val failure = assertFailsWith<ContentReadException> {
                readNativeFields(path, setOf("name"), "builder")
            }.error
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX, failure.code)
            assertEquals(path.toAbsolutePath().toString(), failure.file)
            assertNull(failure.section)
            assertEquals("name", failure.field)
            assertEquals("Duplicate builder field", failure.reason)
        } finally { Files.delete(path) }
    }
}
