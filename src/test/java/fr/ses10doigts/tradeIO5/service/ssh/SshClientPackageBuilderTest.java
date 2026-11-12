package fr.ses10doigts.tradeIO5.service.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshClientPackageBuilderTest {

    private final SshClientPackageBuilder builder = new SshClientPackageBuilder();

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Test
    void zipContientScriptsEtConfGenereSansConfModele(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("tradeio-tunnel.sh"), "#!/bin/bash");
        Files.writeString(dir.resolve("tradeio-tunnel.ps1"), "ps");
        Files.writeString(dir.resolve("tradeio.conf"), "SSH_USER=tio_CHANGEME");
        Files.writeString(dir.resolve("tradeio.conf.example"), "exemple");
        Files.createDirectory(dir.resolve("sousdossier"));
        Map<String, String> files = unzip(builder.build(dir.toString(), "SSH_USER=tio_alice\n"));
        assertEquals("SSH_USER=tio_alice\n", files.get("tradeio.conf"));
        assertTrue(files.containsKey("tradeio-tunnel.sh"));
        assertTrue(files.containsKey("tradeio-tunnel.ps1"));
        assertFalse(files.containsKey("tradeio.conf.example"));
        assertFalse(files.containsKey("sousdossier"));
        assertEquals(3, files.size());
    }

    @Test
    void dossierNonConfigureOuVide(@TempDir Path dir) {
        assertThrows(SshProvisioningException.class, () -> builder.build("", "x"));
        assertThrows(SshProvisioningException.class, () -> builder.build(dir.toString(), "x"));
        assertThrows(SshProvisioningException.class, () -> builder.build(dir.resolve("absent").toString(), "x"));
    }
}
