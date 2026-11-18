package fr.ses10doigts.tradeIO5.service.doctor.check;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorCheck;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Accès internet du serveur, en deux verdicts distincts pour localiser la panne :
 * <ul>
 *   <li>{@code internet-ip} : connexion TCP/443 vers des IP publiques, sans DNS. Échec de toutes ⇒ CRIT (pas de réseau sortant).</li>
 *   <li>{@code dns} : résolution des hôtes d'exchanges. Tous en échec ⇒ CRIT (cas « DNS HS, IP OK »), une partie ⇒ WARN. Non évalué (UNKNOWN)
 *       si l'accès IP lui-même est coupé, pour ne pas doubler l'alarme.</li>
 * </ul>
 * Aucune requête vers un exchange : seuls la résolution de noms et un handshake TCP vers des IP publiques sont émis.
 */
@Slf4j
@Component
public class InternetAccessCheck implements DoctorCheck {

    static final String SUBJECT_IP = "internet-ip";
    static final String SUBJECT_DNS = "dns";

    /** Sondes réseau, isolées pour les tests. */
    interface Probe {
        /** {@code true} si une connexion TCP à {@code ip:port} aboutit dans le délai. */
        boolean connect(String ip, int port, int timeoutMs);

        /** {@code true} si le nom se résout. */
        boolean resolve(String host);
    }

    private final List<String> hosts;
    private final Probe probe;

    @Autowired
    public InternetAccessCheck(@Value(DoctorDefaults.INTERNET_PROBE_HOSTS) String hosts) {
        this(Arrays.stream(hosts.split(",")).map(String::trim).filter(h -> !h.isEmpty()).toList(), new NetworkProbe());
    }

    InternetAccessCheck(List<String> hosts, Probe probe) {
        this.hosts = hosts;
        this.probe = probe;
    }

    @Override
    public String id() {
        return DoctorDefaults.INTERNET_ACCESS_CHECK;
    }

    @Override
    public String label() {
        return "Accès internet : réseau sortant (IP) et résolution DNS des exchanges";
    }

    @Override
    public DoctorFamily family() {
        return DoctorFamily.INFRA;
    }

    @Override
    public DoctorImpact impact() {
        return DoctorImpact.EXECUTION;
    }

    @Override
    public List<CheckResult> run() {
        boolean ipOk = DoctorDefaults.INTERNET_PROBE_IPS.stream()
                .anyMatch(ip -> probe.connect(ip, DoctorDefaults.INTERNET_PROBE_PORT, DoctorDefaults.INTERNET_PROBE_TIMEOUT_MS));
        CheckResult ip = ipOk
                ? CheckResult.ok(SUBJECT_IP, "réseau sortant OK (TCP/443 vers IP publique)")
                : CheckResult.crit(SUBJECT_IP, "aucun accès internet : " + DoctorDefaults.INTERNET_PROBE_IPS + ":"
                        + DoctorDefaults.INTERNET_PROBE_PORT + " injoignables");
        if (!ipOk) {
            return List.of(ip, CheckResult.unknown(SUBJECT_DNS, "non évalué : pas d'accès internet"));
        }
        return List.of(ip, dns());
    }

    private CheckResult dns() {
        if (hosts.isEmpty()) {
            return CheckResult.unknown(SUBJECT_DNS, "aucun hôte à sonder (" + DoctorDefaults.INTERNET_PROBE_HOSTS_PROPERTY + " vide)");
        }
        List<String> failed = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = hosts.stream().map(h -> pool.submit(() -> probe.resolve(h))).toList();
            for (int i = 0; i < hosts.size(); i++) {
                if (!resolved(futures.get(i))) {
                    failed.add(hosts.get(i));
                }
            }
        }
        if (failed.isEmpty()) {
            return CheckResult.ok(SUBJECT_DNS, hosts.size() + " hôte(s) résolu(s)");
        }
        if (failed.size() == hosts.size()) {
            return CheckResult.crit(SUBJECT_DNS, "DNS en panne (accès IP OK) : aucun hôte résolu " + failed);
        }
        return CheckResult.warn(SUBJECT_DNS, "résolution en échec pour " + failed);
    }

    private static boolean resolved(Future<Boolean> future) {
        try {
            return future.get(DoctorDefaults.INTERNET_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            future.cancel(true);
            return false;
        }
    }

    /** Sonde réelle : socket TCP et résolution via le résolveur du système. */
    static final class NetworkProbe implements Probe {

        @Override
        public boolean connect(String ip, int port, int timeoutMs) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, port), timeoutMs);
                return true;
            } catch (Exception e) {
                log.debug("Sonde TCP {}:{} en échec : {}", ip, port, e.toString());
                return false;
            }
        }

        @Override
        public boolean resolve(String host) {
            try {
                return InetAddress.getByName(host) != null;
            } catch (Exception e) {
                log.debug("Résolution de {} en échec : {}", host, e.toString());
                return false;
            }
        }
    }
}
