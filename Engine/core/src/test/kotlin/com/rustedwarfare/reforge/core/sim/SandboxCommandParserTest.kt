package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class SandboxCommandParserTest {
    @Test fun help_and_spawn_have_typed_finite_defaults_and_explicit_options() {
        assertEquals(SandboxCommand.Help, assertIs<SandboxParseResult.Parsed>(SandboxCommands.parse("/help")).command)
        val plain = assertIs<SandboxCommand.Spawn>(assertIs<SandboxParseResult.Parsed>(
            SandboxCommands.parse("/spawn spcruiser 240 160 p2")).command)
        assertEquals("spcruiser", plain.typeId)
        assertEquals(Position(240.0, 160.0), plain.position)
        assertEquals("p2", plain.ownerPlayerId)
        assertEquals(0.0, plain.heading)
        assertEquals(SandboxInitialState.READY, plain.state)
        val explicit = assertIs<SandboxCommand.Spawn>(assertIs<SandboxParseResult.Parsed>(
            SandboxCommands.parse("  /spawn c_tank 1.25 -2 p1 state=damaged heading=90  ")).command)
        assertEquals(Position(1.25, -2.0), explicit.position)
        assertEquals(90.0, explicit.heading)
        assertEquals(SandboxInitialState.DAMAGED, explicit.state)
    }

    @Test fun rejected_tokens_identify_the_actual_one_based_character_position() {
        for ((text, code, position) in listOf(
            Triple("/spawn tank NaN 2 p1", "invalid-number", 13),
            Triple("/spawn tank 1 Infinity p1", "invalid-number", 15),
            Triple("/spawn tank 1 2 p1 heading=NaN", "invalid-number", 20),
            Triple("/spawn tank 1 2 p1 health=2", "unknown-field", 20),
            Triple("/spawn tank 1 2 p1 state=full", "invalid-state", 20),
            Triple("/spawn tank 1 2 p1 heading=0 heading=1", "duplicate-field", 30),
            Triple("/spawn tank 1 2 p1 state=ready state=damaged", "duplicate-field", 32),
            Triple("/help extra", "unexpected-argument", 7),
            Triple("/kill tank", "unknown-command", 1),
            Triple("/spawn tank 1 2", "missing-argument", 16))) {
            val error = assertIs<SandboxParseResult.Rejected>(SandboxCommands.parse(text), text)
            assertEquals(code, error.code, text)
            assertEquals(position, error.position, text)
        }
    }

    @Test fun bounded_single_line_input_never_accepts_arbitrary_internal_fields() {
        for (text in listOf("/help\n/spawn tank 1 2 p1", "/help\r", "x".repeat(513),
            "/spawn tank 1 2 p1 objectId=2", "/spawn tank 1 2 p1 state=ready=damaged")) {
            assertIs<SandboxParseResult.Rejected>(SandboxCommands.parse(text), text)
        }
    }
}
