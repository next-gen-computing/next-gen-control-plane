package com.nextgen.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage EE: {@code ArgReader} previously could not tell a genuine boolean flag (e.g. {@code --build})
 * apart from a value-taking option whose value was accidentally omitted (e.g. {@code --token} followed
 * immediately by another {@code --flag}, or by nothing at all) — both silently defaulted to the literal
 * string {@code "true"}. These tests exercise the real class directly (no mocking needed — it's pure
 * argument-parsing logic with no I/O).
 */
class ArgReaderTest {

    @Test
    void requireReturnsTheExplicitlyProvidedValue() {
        Cli.ArgReader args = new Cli.ArgReader(new String[] {"--token", "abc123"});

        assertEquals("abc123", args.require("--token"));
    }

    @Test
    void requireThrowsAClearErrorWhenTheValueWasOmittedBecauseAnotherFlagFollowedImmediately() {
        Cli.ArgReader args = new Cli.ArgReader(new String[] {"--token", "--control-plane", "host:1234"});

        Cli.UsageException e = assertThrows(Cli.UsageException.class, () -> args.require("--token"));
        assertTrue(e.getMessage().contains("--token"), e.getMessage());
    }

    @Test
    void requireThrowsAClearErrorWhenTheValueWasOmittedAtTheEndOfArgs() {
        Cli.ArgReader args = new Cli.ArgReader(new String[] {"--token"});

        assertThrows(Cli.UsageException.class, () -> args.require("--token"));
    }

    @Test
    void requireThrowsWhenTheOptionIsAbsentEntirely() {
        Cli.ArgReader args = new Cli.ArgReader(new String[] {});

        assertThrows(Cli.UsageException.class, () -> args.require("--token"));
    }

    @Test
    void aGenuineBooleanFlagStillWorksViaFlagEvenWithNoFollowingValue() {
        Cli.ArgReader args = new Cli.ArgReader(new String[] {"--build"});

        assertTrue(args.flag("--build"));
    }

    @Test
    void aGenuineBooleanFlagFollowedByAnotherFlagStillWorksViaFlag() {
        Cli.ArgReader args = new Cli.ArgReader(new String[] {"--build", "--no-follow"});

        assertTrue(args.flag("--build"));
        assertTrue(args.flag("--no-follow"));
    }

    @Test
    void absentFlagIsFalse() {
        Cli.ArgReader args = new Cli.ArgReader(new String[] {});

        assertFalse(args.flag("--build"));
    }

    @Test
    void positionalArgumentsAreCollectedInOrder() {
        Cli.ArgReader args = new Cli.ArgReader(new String[] {"first", "--token", "abc", "second"});

        assertEquals(java.util.List.of("first", "second"), args.positionals());
    }
}
