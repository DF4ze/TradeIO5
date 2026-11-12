package fr.ses10doigts.tradeIO5.service.ssh;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Construit le zip client : fichiers réguliers du dossier de scripts (non récursif, liens symboliques ignorés, tout
 * {@code tradeio.conf*} exclu) + le {@code tradeio.conf} fourni. Aucun secret : le conf ne contient que host, port,
 * compte, ports, URL et clé d'hôte publique.
 */
@Component
public class SshClientPackageBuilder {

    static final String CONF_NAME = "tradeio.conf";

    public byte[] build(String clientDir, String conf) {
        if (clientDir == null || clientDir.isBlank()) {
            throw new SshProvisioningException(SshProvisioningException.Kind.CONFLICT,
                    "Package client indisponible : tradeio.ssh.client-dir non configuré");
        }
        Path dir = Path.of(clientDir);
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                    .filter(f -> !f.getFileName().toString().startsWith(CONF_NAME)).sorted().toList();
        } catch (IOException e) {
            throw new SshProvisioningException(SshProvisioningException.Kind.INFRA, "Dossier client illisible : " + clientDir);
        }
        if (files.isEmpty()) {
            throw new SshProvisioningException(SshProvisioningException.Kind.CONFLICT, "Dossier client vide : " + clientDir);
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Path f : files) {
                zip.putNextEntry(new ZipEntry(f.getFileName().toString()));
                zip.write(Files.readAllBytes(f));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(CONF_NAME));
            zip.write(conf.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new SshProvisioningException(SshProvisioningException.Kind.INFRA, "Construction du package client impossible");
        }
    }
}
