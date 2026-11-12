package fr.ses10doigts.tradeIO5.service.ssh;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.security.model.ERole;
import fr.ses10doigts.tradeIO5.security.model.Role;
import fr.ses10doigts.tradeIO5.security.model.SshAccessStatus;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.RoleRepository;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.admin.AdminActionLogService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.ssh.SshAccessProvisioner.Account;
import fr.ses10doigts.tradeIO5.service.ssh.SshProvisioningException.Kind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cycle de vie utilisateur TradeIO + accès VPS (tunnel). Ordre : création = app puis VPS ; coupure (disable/delete) = VPS
 * d'abord. Échec VPS => statut ERROR + message, état applicatif cohérent ; {@link #sync} = « réessayer ».
 * Aucun secret journalisé (le mot de passe généré n'est renvoyé qu'une fois à l'appelant).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SshAccessService {

    static final String EMAIL_DOMAIN = "tradeio.local"; // TODO gestion des users : e-mail réel
    private static final String PWD_CHARS = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int PWD_LEN = 20;
    private static final int USERNAME_MAX = 20;
    private static final int MSG_MAX = 255;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder encoder;
    private final SshAccessProvisioner provisioner;
    private final AdminActionLogService audit;
    private final SshProperties props;
    private final DomainClock clock;
    private final SshClientPackageBuilder packageBuilder;

    public record Created(Long id, String username, String generatedPassword, SshAccessStatus sshStatus) {
        @Override
        public String toString() {
            return "Created[id=" + id + ", username=" + username + ", sshStatus=" + sshStatus + "]";
        }
    }

    public record UserAccess(Long id, String username, boolean enabled, SshAccessStatus sshStatus, String sshMessage,
                             java.time.Instant sshUpdatedAt) {
    }

    /** Écart app / VPS : comptes VPS sans utilisateur, utilisateurs sans compte VPS, statuts divergents. */
    public record Reconciliation(List<String> orphanVpsAccounts, List<String> usersMissingOnVps,
                                 List<String> statusMismatches, List<String> pendingKey) {
    }

    // ---------------------------------------------------------------- lecture

    public List<UserAccess> listAccess() {
        return userRepository.findAll().stream().sorted(Comparator.comparing(u -> u.getUsername().toLowerCase()))
                .map(SshAccessService::toAccess).toList();
    }

    public String clientConf(Long userId) {
        User u = require(userId);
        if (u.getSshStatus() == SshAccessStatus.NONE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Aucun accès VPS pour cet utilisateur");
        }
        return "# TradeIO - config client tunnel SSH (utilisateur " + u.getUsername() + ")\n"
                + "HOST=" + props.getHost() + "\n"
                + "PORT=" + props.getPort() + "\n"
                + "SSH_USER=tio_" + u.getUsername() + "\n"
                + "LOCAL_PORT=" + props.getLocalPort() + "\n"
                + "REMOTE=" + props.getRemote() + "\n"
                + "URL=" + props.getUrl() + "\n"
                + "HOSTKEY=" + props.getHostkey() + "\n";
    }

    /** Zip prêt à envoyer à l'ami : scripts du dossier {@code tradeio.ssh.client-dir} + {@code tradeio.conf} généré pour son compte. */
    public byte[] clientPackage(Long userId) {
        String conf = clientConf(userId);
        try {
            return packageBuilder.build(props.getClientDir(), conf);
        } catch (SshProvisioningException e) {
            throw toHttp(e);
        }
    }

    // ---------------------------------------------------------------- création

    /** Crée le compte applicatif (ROLE_USER, mot de passe généré, e-mail placeholder), puis marque PENDING_KEY si accès VPS voulu. */
    public Created createUser(String actor, String username, boolean withSsh) {
        String login = username == null ? "" : username.trim();
        if (login.length() > USERNAME_MAX || !SshInputValidator.isValidLogin(login)) {
            audit(actor, login, AdminActionOutcome.REFUSED, "event", "CREATE_REFUSED", "reason", "login invalide");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Login invalide (a-z0-9, 3 à 20 caractères, commence par une lettre)");
        }
        String email = login + "@" + EMAIL_DOMAIN;
        if (userRepository.existsByUsername(login) || userRepository.existsByEmail(email)) {
            audit(actor, login, AdminActionOutcome.REFUSED, "event", "CREATE_REFUSED", "reason", "existe déjà");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Utilisateur déjà existant : " + login);
        }
        String password = randomPassword();
        Set<Role> roles = new HashSet<>();
        roleRepository.findByName(ERole.ROLE_USER).ifPresent(roles::add);
        User user = User.builder().username(login).email(email).password(encoder.encode(password)).roles(roles).enabled(true)
                .sshStatus(withSsh ? SshAccessStatus.PENDING_KEY : SshAccessStatus.NONE).sshUpdatedAt(clock.now()).build();
        userRepository.save(user);
        audit(actor, login, AdminActionOutcome.OK, "event", "CREATE", "ssh", withSsh);
        return new Created(user.getId(), login, password, user.getSshStatus());
    }

    /** Enregistre la clé publique reçue : crée le compte VPS. Réservé aux utilisateurs sans compte VPS actif. */
    public UserAccess registerKey(String actor, Long userId, String publicKey) {
        User u = require(userId);
        if (u.getSshStatus() == SshAccessStatus.ACTIVE || u.getSshStatus() == SshAccessStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Compte VPS déjà créé pour " + u.getUsername());
        }
        String key;
        try {
            key = SshInputValidator.requirePublicKey(publicKey);
            provisioner.add(u.getUsername(), key);
            if (!u.isEnabled()) {
                provisioner.disable(u.getUsername());
            }
        } catch (SshProvisioningException e) {
            return fail(actor, u, "ADD", e, null);
        }
        setStatus(u, u.isEnabled() ? SshAccessStatus.ACTIVE : SshAccessStatus.DISABLED, null);
        audit(actor, u.getUsername(), AdminActionOutcome.OK, "event", "ADD", "publicKey", key);
        return toAccess(u);
    }

    // ---------------------------------------------------------------- activation / suppression

    /** Désactivation : VPS d'abord, puis app. Activation : app d'abord, puis VPS. */
    public UserAccess setEnabled(String actor, Long userId, boolean enabled) {
        User u = require(userId);
        guardSelf(actor, u);
        boolean hasVps = hasVpsAccount(u);
        if (!enabled) {
            SshProvisioningException failure = null;
            if (hasVps) {
                try {
                    provisioner.disable(u.getUsername());
                } catch (SshProvisioningException e) {
                    failure = e;
                }
            }
            u.setEnabled(false);
            if (failure != null) {
                return fail(actor, u, "DISABLE", failure, null);
            }
            if (hasVps) {
                setStatus(u, SshAccessStatus.DISABLED, null);
            } else {
                userRepository.save(u);
            }
            audit(actor, u.getUsername(), AdminActionOutcome.OK, "event", "DISABLE", "vps", hasVps);
            return toAccess(u);
        }
        u.setEnabled(true);
        userRepository.save(u);
        if (hasVps) {
            try {
                provisioner.enable(u.getUsername());
            } catch (SshProvisioningException e) {
                return fail(actor, u, "ENABLE", e, null);
            }
            setStatus(u, SshAccessStatus.ACTIVE, null);
        }
        audit(actor, u.getUsername(), AdminActionOutcome.OK, "event", "ENABLE", "vps", hasVps);
        return toAccess(u);
    }

    /** Suppression : {@code del} VPS d'abord (compte inconnu = OK), puis suppression applicative. Échec VPS => rien n'est supprimé. */
    public void delete(String actor, Long userId) {
        User u = require(userId);
        guardSelf(actor, u);
        if (u.getSshStatus() != SshAccessStatus.NONE) {
            try {
                provisioner.del(u.getUsername());
            } catch (SshProvisioningException e) {
                fail(actor, u, "DELETE", e, null);
                throw toHttp(e);
            }
        }
        userRepository.delete(u);
        audit(actor, u.getUsername(), AdminActionOutcome.OK, "event", "DELETE");
    }

    // ---------------------------------------------------------------- réessayer / réconciliation

    /**
     * « Réessayer » : aligne le VPS sur l'état applicatif. Compte VPS absent => PENDING_KEY (clé à recoller) ; présent =>
     * enable/disable selon {@code enabled}. Sans effet pour un utilisateur sans accès VPS.
     */
    public UserAccess sync(String actor, Long userId) {
        User u = require(userId);
        if (u.getSshStatus() == SshAccessStatus.NONE) {
            return toAccess(u);
        }
        try {
            boolean present = provisioner.list().stream().anyMatch(a -> a.login().equals(u.getUsername()));
            if (!present) {
                setStatus(u, SshAccessStatus.PENDING_KEY, null);
                audit(actor, u.getUsername(), AdminActionOutcome.OK, "event", "SYNC", "result", "absent => PENDING_KEY");
                return toAccess(u);
            }
            if (u.isEnabled()) {
                provisioner.enable(u.getUsername());
            } else {
                provisioner.disable(u.getUsername());
            }
        } catch (SshProvisioningException e) {
            return fail(actor, u, "SYNC", e, null);
        }
        setStatus(u, u.isEnabled() ? SshAccessStatus.ACTIVE : SshAccessStatus.DISABLED, null);
        audit(actor, u.getUsername(), AdminActionOutcome.OK, "event", "SYNC", "result", u.getSshStatus().name());
        return toAccess(u);
    }

    public Reconciliation reconcile(String actor) {
        List<Account> accounts;
        try {
            accounts = provisioner.list();
        } catch (SshProvisioningException e) {
            audit(actor, "-", AdminActionOutcome.ERROR, "event", "RECONCILE", "reason", e.getMessage());
            throw toHttp(e);
        }
        Map<String, Account> byLogin = accounts.stream().collect(Collectors.toMap(Account::login, Function.identity(), (a, b) -> a));
        List<User> users = userRepository.findAll();
        Set<String> usernames = users.stream().map(User::getUsername).collect(Collectors.toSet());
        List<String> orphans = accounts.stream().map(Account::login).filter(l -> !usernames.contains(l)).sorted().toList();
        List<String> missing = new java.util.ArrayList<>();
        List<String> mismatches = new java.util.ArrayList<>();
        List<String> pending = new java.util.ArrayList<>();
        for (User u : users) {
            SshAccessStatus st = u.getSshStatus();
            Account a = byLogin.get(u.getUsername());
            if (st == SshAccessStatus.PENDING_KEY) {
                pending.add(u.getUsername());
            }
            if ((st == SshAccessStatus.ACTIVE || st == SshAccessStatus.DISABLED) && a == null) {
                missing.add(u.getUsername());
            } else if (a != null && (st == SshAccessStatus.ACTIVE) != a.active() && st != SshAccessStatus.ERROR) {
                mismatches.add(u.getUsername() + " : app=" + st + ", VPS=" + (a.active() ? "actif" : "désactivé"));
            } else if (a != null && (st == SshAccessStatus.NONE || st == SshAccessStatus.PENDING_KEY)) {
                mismatches.add(u.getUsername() + " : app=" + st + ", VPS=compte présent");
            }
        }
        audit(actor, "-", AdminActionOutcome.OK, "event", "RECONCILE", "orphans", orphans.size(), "missing", missing.size(),
                "mismatches", mismatches.size());
        return new Reconciliation(orphans, missing.stream().sorted().toList(), mismatches.stream().sorted().toList(),
                pending.stream().sorted().toList());
    }

    // ---------------------------------------------------------------- interne

    private boolean hasVpsAccount(User u) {
        SshAccessStatus s = u.getSshStatus();
        return s == SshAccessStatus.ACTIVE || s == SshAccessStatus.DISABLED || s == SshAccessStatus.ERROR;
    }

    private void guardSelf(String actor, User target) {
        if (target.getUsername().equals(actor)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Action refusée sur son propre compte");
        }
        if (target.getRoles().stream().anyMatch(r -> r.getName() == ERole.ROLE_SYS)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Action refusée sur le compte technique System");
        }
    }

    private User require(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur inconnu"));
    }

    private void setStatus(User u, SshAccessStatus status, String message) {
        u.setSshStatus(status);
        u.setSshMessage(cut(message));
        u.setSshUpdatedAt(clock.now());
        userRepository.save(u);
    }

    /** Statut ERROR + message, trace d'audit, et réponse lisible (CONFLICT/VALIDATION => levée, sinon statut renvoyé). */
    private UserAccess fail(String actor, User u, String event, SshProvisioningException e, String extra) {
        if (e.getKind() == Kind.VALIDATION) {
            audit(actor, u.getUsername(), AdminActionOutcome.REFUSED, "event", event, "kind", e.getKind().name());
            throw toHttp(e);
        }
        boolean conflictOrInvalid = e.getKind() == Kind.CONFLICT;
        setStatus(u, conflictOrInvalid && u.getSshStatus() == SshAccessStatus.PENDING_KEY ? SshAccessStatus.PENDING_KEY
                : SshAccessStatus.ERROR, e.getMessage());
        audit(actor, u.getUsername(), conflictOrInvalid ? AdminActionOutcome.REFUSED : AdminActionOutcome.ERROR, "event", event,
                "kind", e.getKind().name(), "reason", e.getMessage());
        if (conflictOrInvalid) {
            throw toHttp(e);
        }
        return toAccess(u);
    }

    private static ResponseStatusException toHttp(SshProvisioningException e) {
        HttpStatus s = switch (e.getKind()) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNKNOWN -> HttpStatus.NOT_FOUND;
            case INFRA -> HttpStatus.BAD_GATEWAY;
        };
        return new ResponseStatusException(s, e.getMessage());
    }

    private void audit(String actor, String login, AdminActionOutcome outcome, Object... kv) {
        audit.record(actor, AdminAction.SSH_ACCESS, "user:" + login, AdminActionLogService.payload(kv), outcome);
    }

    private static UserAccess toAccess(User u) {
        return new UserAccess(u.getId(), u.getUsername(), u.isEnabled(), u.getSshStatus(), u.getSshMessage(), u.getSshUpdatedAt());
    }

    private static String cut(String s) {
        return s == null ? null : (s.length() > MSG_MAX ? s.substring(0, MSG_MAX) : s);
    }

    private static String randomPassword() {
        StringBuilder sb = new StringBuilder(PWD_LEN);
        for (int i = 0; i < PWD_LEN; i++) {
            sb.append(PWD_CHARS.charAt(RANDOM.nextInt(PWD_CHARS.length())));
        }
        return sb.toString();
    }
}
