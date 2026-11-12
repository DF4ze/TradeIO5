package fr.ses10doigts.tradeIO5.service.ssh;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static fr.ses10doigts.tradeIO5.service.ssh.SshProvisioningException.Kind;

/**
 * Provisioner de production : appelle {@code sudo -n /usr/local/sbin/tradeio-user <cmd> ...} (règle sudoers limitée à ce
 * script). {@link ProcessBuilder} avec tableau d'arguments, jamais de shell ; chemin absolu ; timeout ; entrées validées
 * avant l'appel. Les cas CONFLICT/UNKNOWN sont détectés par un {@code list} préalable (pas de parsing de messages).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "tradeio.ssh.enabled", havingValue = "true")
public class ShellSshAccessProvisioner implements SshAccessProvisioner {

    private static final Pattern LIST_LINE = Pattern.compile("^tio_([a-z][a-z0-9]{2,20})\\s+(.+?)\\s*$");
    private static final int MSG_MAX = 200;

    private final SshProperties props;

    public ShellSshAccessProvisioner(SshProperties props) {
        this.props = props;
    }

    @Override
    public void add(String login, String publicKey) {
        SshInputValidator.requireLogin(login);
        String key = SshInputValidator.requirePublicKey(publicKey);
        if (exists(login)) {
            throw new SshProvisioningException(Kind.CONFLICT, "Compte VPS déjà existant : tio_" + login);
        }
        run("add", login, key);
    }

    @Override
    public void disable(String login) {
        requireExisting(SshInputValidator.requireLogin(login));
        run("disable", login);
    }

    @Override
    public void enable(String login) {
        requireExisting(SshInputValidator.requireLogin(login));
        run("enable", login);
    }

    @Override
    public void del(String login) {
        SshInputValidator.requireLogin(login);
        if (exists(login)) {
            run("del", login);
        }
    }

    @Override
    public List<Account> list() {
        String out = run("list");
        List<Account> accounts = new ArrayList<>();
        for (String line : out.split("\\R")) {
            Matcher m = LIST_LINE.matcher(line.trim());
            if (m.matches()) {
                accounts.add(new Account(m.group(1), m.group(2), "never".equalsIgnoreCase(m.group(2))));
            }
        }
        return accounts;
    }

    private boolean exists(String login) {
        return list().stream().anyMatch(a -> a.login().equals(login));
    }

    private void requireExisting(String login) {
        if (!exists(login)) {
            throw new SshProvisioningException(Kind.UNKNOWN, "Compte VPS inconnu : tio_" + login);
        }
    }

    /** Exécute le helper ; retourne stdout. Exit != 0 => INFRA avec stderr tronqué. */
    private String run(String... args) {
        List<String> cmd = new ArrayList<>();
        if (props.isSudo()) {
            cmd.add("sudo");
            cmd.add("-n");
        }
        cmd.add(props.getHelperPath());
        cmd.addAll(List.of(args));
        log.debug("SSH helper : {} {}", args[0], args.length > 1 ? args[1] : "");
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).start();
            p.getOutputStream().close();
            CompletableFuture<String> out = read(p.getInputStream());
            CompletableFuture<String> err = read(p.getErrorStream());
            if (!p.waitFor(props.getTimeoutSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new SshProvisioningException(Kind.INFRA, "Timeout du helper SSH (" + args[0] + ")");
            }
            String stdout = out.get(2, TimeUnit.SECONDS);
            String stderr = err.get(2, TimeUnit.SECONDS);
            if (p.exitValue() != 0) {
                log.warn("SSH helper {} : exit {} : {}", args[0], p.exitValue(), cut(stderr));
                throw new SshProvisioningException(Kind.INFRA, "Helper SSH en échec (" + args[0] + ") : " + cut(stderr));
            }
            return stdout;
        } catch (IOException e) {
            throw new SshProvisioningException(Kind.INFRA, "Helper SSH injoignable : " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SshProvisioningException(Kind.INFRA, "Appel du helper SSH interrompu");
        } catch (SshProvisioningException e) {
            throw e;
        } catch (Exception e) {
            if (p != null) {
                p.destroyForcibly();
            }
            throw new SshProvisioningException(Kind.INFRA, "Lecture de la sortie du helper impossible");
        }
    }

    private static CompletableFuture<String> read(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static String cut(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() > MSG_MAX ? t.substring(0, MSG_MAX) : t;
    }
}
