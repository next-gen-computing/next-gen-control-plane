package com.nextgen.desktop.ui.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    @Test
    void correctPasswordMatchesItsOwnHash() {
        String salt = PasswordHasher.generateSalt();
        String hash = PasswordHasher.hash("correct horse battery staple".toCharArray(), salt);

        assertTrue(PasswordHasher.matches("correct horse battery staple".toCharArray(), salt, hash));
    }

    @Test
    void wrongPasswordDoesNotMatch() {
        String salt = PasswordHasher.generateSalt();
        String hash = PasswordHasher.hash("correct horse battery staple".toCharArray(), salt);

        assertFalse(PasswordHasher.matches("wrong password entirely".toCharArray(), salt, hash));
    }

    @Test
    void twoSaltsForTheSamePasswordProduceDifferentHashes() {
        String saltA = PasswordHasher.generateSalt();
        String saltB = PasswordHasher.generateSalt();

        assertNotEquals(saltA, saltB);
        assertNotEquals(
                PasswordHasher.hash("same password".toCharArray(), saltA),
                PasswordHasher.hash("same password".toCharArray(), saltB));
    }

    @Test
    void hashingIsDeterministicForTheSamePasswordAndSalt() {
        String salt = PasswordHasher.generateSalt();
        String first = PasswordHasher.hash("stable input".toCharArray(), salt);
        String second = PasswordHasher.hash("stable input".toCharArray(), salt);

        assertTrue(first.equals(second));
    }

    @Test
    void aPasswordThatIsAPrefixOfTheRealOneDoesNotMatch() {
        String salt = PasswordHasher.generateSalt();
        String hash = PasswordHasher.hash("verylongpassword123".toCharArray(), salt);

        assertFalse(PasswordHasher.matches("verylongpassword".toCharArray(), salt, hash));
    }
}
