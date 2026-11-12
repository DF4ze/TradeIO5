package fr.ses10doigts.tradeIO5.service.ssh;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static fr.ses10doigts.tradeIO5.service.ssh.SshProvisioningException.Kind;

/** Provisioner factice (dev/test, aucun sudo) : état en mémoire, mêmes règles que le helper réel. */
@Slf4j
@Service
@ConditionalOnProperty(name = "tradeio.ssh.enabled", havingValue = "false", matchIfMissing = true)
public class NoopSshAccessProvisioner implements SshAccessProvisioner {

    public static final String NEVER = "never";
    public static final String DISABLED = "Jan 01, 1970";

    private final Map<String, String> accounts = new TreeMap<>();

    @Override
    public synchronized void add(String login, String publicKey) {
        SshInputValidator.requireLogin(login);
        SshInputValidator.requirePublicKey(publicKey);
        if (accounts.containsKey(login)) {
            throw new SshProvisioningException(Kind.CONFLICT, "Compte VPS déjà existant : tio_" + login);
        }
        accounts.put(login, NEVER);
        log.info("SSH (factice) add tio_{}", login);
    }

    @Override
    public synchronized void disable(String login) {
        requireExisting(SshInputValidator.requireLogin(login));
        accounts.put(login, DISABLED);
        log.info("SSH (factice) disable tio_{}", login);
    }

    @Override
    public synchronized void enable(String login) {
        requireExisting(SshInputValidator.requireLogin(login));
        accounts.put(login, NEVER);
        log.info("SSH (factice) enable tio_{}", login);
    }

    @Override
    public synchronized void del(String login) {
        SshInputValidator.requireLogin(login);
        accounts.remove(login);
        log.info("SSH (factice) del tio_{}", login);
    }

    @Override
    public synchronized List<Account> list() {
        return accounts.entrySet().stream().map(e -> new Account(e.getKey(), e.getValue(), NEVER.equals(e.getValue())))
                .toList();
    }

    private void requireExisting(String login) {
        if (!accounts.containsKey(login)) {
            throw new SshProvisioningException(Kind.UNKNOWN, "Compte VPS inconnu : tio_" + login);
        }
    }
}
