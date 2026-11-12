package fr.ses10doigts.tradeIO5.service.ssh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshInputValidatorTest {

    private static final String KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIabc+/= ami@pc";

    @Test
    void loginValide() {
        assertTrue(SshInputValidator.isValidLogin("alice"));
        assertTrue(SshInputValidator.isValidLogin("a1b"));
        assertTrue(SshInputValidator.isValidLogin("a" + "b".repeat(20)));
    }

    @Test
    void loginInvalide() {
        for (String l : new String[] { null, "", "ab", "Alice", "1abc", "al_ice", "al ice", "a;rm", "a" + "b".repeat(21), "tio_x\n" }) {
            assertFalse(SshInputValidator.isValidLogin(l), String.valueOf(l));
        }
    }

    @Test
    void cleValideEtTrim() {
        assertEquals(KEY, SshInputValidator.requirePublicKey("  " + KEY + "\n"));
        assertEquals("ssh-ed25519 AAAAC3Nz", SshInputValidator.requirePublicKey("ssh-ed25519 AAAAC3Nz"));
    }

    @Test
    void cleInvalide() {
        for (String k : new String[] { null, "", "ssh-rsa AAAA", "ssh-ed25519", KEY + "\n" + KEY,
                "restrict " + KEY, "ssh-ed25519 AAAA; rm -rf /", "ssh-ed25519 AAAA c\"x", "ssh-ed25519 " + "A".repeat(600) }) {
            assertThrows(SshProvisioningException.class, () -> SshInputValidator.requirePublicKey(k), String.valueOf(k));
        }
    }
}
