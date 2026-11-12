package fr.ses10doigts.tradeIO5.service.ssh;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Propriétés {@code tradeio.ssh.*} : activation du provisioning et valeurs du {@code tradeio.conf} fourni aux amis. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tradeio.ssh")
public class SshProperties {

    /** true => {@link ShellSshAccessProvisioner} (VPS) ; sinon provisioner factice en mémoire. */
    private boolean enabled = false;
    private String helperPath = "/usr/local/sbin/tradeio-user";
    private boolean sudo = true;
    private int timeoutSeconds = 10;

    private String host = "";
    private int port = 22;
    private String hostkey = "";
    private int localPort = 18080;
    private String remote = "127.0.0.1:9003";
    private String url = "http://127.0.0.1:18080/";

    /** Dossier du VPS contenant les scripts client (.bat/.ps1/.sh, README) zippés avec le tradeio.conf de l'ami. Vide => pas de package. */
    private String clientDir = "";
}
