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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SshAccessServiceTest {

    private static final String KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIabc ami@pc";

    private final Map<Long, User> db = new HashMap<>();
    private UserRepository users;
    private NoopSshAccessProvisioner provisioner;
    private SshAccessProvisioner provisionerSpy;
    private AdminActionLogService audit;
    private SshAccessService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        when(users.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            if (u.getId() == null) {
                u.setId((long) db.size() + 1);
            }
            db.put(u.getId(), u);
            return u;
        });
        when(users.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(db.get(i.<Long>getArgument(0))));
        when(users.findAll()).thenAnswer(i -> new java.util.ArrayList<>(db.values()));
        when(users.existsByUsername(any())).thenAnswer(i -> db.values().stream().anyMatch(u -> u.getUsername().equals(i.getArgument(0))));
        when(users.existsByEmail(any())).thenReturn(false);
        RoleRepository roles = mock(RoleRepository.class);
        when(roles.findByName(ERole.ROLE_USER)).thenReturn(Optional.of(new Role()));
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenAnswer(i -> "enc(" + i.getArgument(0) + ")");
        provisioner = new NoopSshAccessProvisioner();
        provisionerSpy = spy(provisioner);
        audit = mock(AdminActionLogService.class);
        DomainClock clock = mock(DomainClock.class);
        when(clock.now()).thenReturn(Instant.parse("2026-11-11T22:00:00Z"));
        service = new SshAccessService(users, roles, encoder, provisionerSpy, audit, new SshProperties(), clock, new SshClientPackageBuilder());
    }

    private Long create(String name, boolean ssh) {
        return service.createUser("admin", name, ssh).id();
    }

    @Test
    void creationGenereMotDePasseEtEmailPlaceholder() {
        SshAccessService.Created c = service.createUser("admin", "alice", true);
        User u = db.get(c.id());
        assertEquals(20, c.generatedPassword().length());
        assertEquals("enc(" + c.generatedPassword() + ")", u.getPassword());
        assertEquals("alice@tradeio.local", u.getEmail());
        assertEquals(SshAccessStatus.PENDING_KEY, c.sshStatus());
        assertFalse(c.toString().contains(c.generatedPassword()));
        assertEquals(SshAccessStatus.NONE, db.get(create("bob", false)).getSshStatus());
        verify(audit).record(eq("admin"), eq(AdminAction.SSH_ACCESS), eq("user:alice"), any(), eq(AdminActionOutcome.OK));
    }

    @Test
    void creationLoginInvalideOuExistant() {
        assertThrows(ResponseStatusException.class, () -> service.createUser("admin", "Al", true));
        assertThrows(ResponseStatusException.class, () -> service.createUser("admin", "a".repeat(21), true));
        create("alice", false);
        assertThrows(ResponseStatusException.class, () -> service.createUser("admin", "alice", false));
    }

    @Test
    void cleCreeLeCompteVpsPuisActive() {
        Long id = create("alice", true);
        assertEquals(SshAccessStatus.ACTIVE, service.registerKey("admin", id, KEY).sshStatus());
        assertEquals(1, provisioner.list().size());
        assertTrue(provisioner.list().getFirst().active());
    }

    @Test
    void cleInvalideRefuseeSansAppelVps() {
        Long id = create("alice", true);
        assertThrows(ResponseStatusException.class, () -> service.registerKey("admin", id, "ssh-rsa AAAA"));
        verify(provisionerSpy, never()).add(any(), any());
        assertEquals(SshAccessStatus.PENDING_KEY, db.get(id).getSshStatus());
    }

    @Test
    void addSurCompteExistantEstUnConflitLisible() {
        Long id = create("alice", true);
        provisioner.add("alice", KEY);
        assertThrows(ResponseStatusException.class, () -> service.registerKey("admin", id, KEY));
        assertEquals(SshAccessStatus.PENDING_KEY, db.get(id).getSshStatus());
        assertTrue(db.get(id).getSshMessage().contains("existant"));
    }

    @Test
    void desactivationCoupeLeVpsEtReactivationLeRetablit() {
        Long id = create("alice", true);
        service.registerKey("admin", id, KEY);
        assertEquals(SshAccessStatus.DISABLED, service.setEnabled("admin", id, false).sshStatus());
        assertFalse(db.get(id).isEnabled());
        assertFalse(provisioner.list().getFirst().active());
        assertEquals(SshAccessStatus.ACTIVE, service.setEnabled("admin", id, true).sshStatus());
        assertTrue(provisioner.list().getFirst().active());
    }

    @Test
    void echecVpsALaCoupureMarqueErreurMaisDesactiveLApp() {
        Long id = create("alice", true);
        service.registerKey("admin", id, KEY);
        doThrow(new SshProvisioningException(SshProvisioningException.Kind.INFRA, "sudo en échec")).when(provisionerSpy).disable("alice");
        SshAccessService.UserAccess r = service.setEnabled("admin", id, false);
        assertEquals(SshAccessStatus.ERROR, r.sshStatus());
        assertFalse(r.enabled());
        assertEquals("sudo en échec", r.sshMessage());
    }

    @Test
    void syncRealigneLeVpsSurLApp() {
        Long id = create("alice", true);
        service.registerKey("admin", id, KEY);
        User u = db.get(id);
        u.setSshStatus(SshAccessStatus.ERROR);
        u.setEnabled(false);
        assertEquals(SshAccessStatus.DISABLED, service.sync("admin", id).sshStatus());
        assertFalse(provisioner.list().getFirst().active());
    }

    @Test
    void syncSansCompteVpsRedevientPendingKey() {
        Long id = create("alice", true);
        db.get(id).setSshStatus(SshAccessStatus.ERROR);
        assertEquals(SshAccessStatus.PENDING_KEY, service.sync("admin", id).sshStatus());
    }

    @Test
    void suppressionCoupeLeVpsPuisSupprimeEtEchecBloque() {
        Long id = create("alice", true);
        service.registerKey("admin", id, KEY);
        service.delete("admin", id);
        assertTrue(provisioner.list().isEmpty());
        verify(users).delete(any(User.class));

        Long id2 = create("bob", true);
        service.registerKey("admin", id2, KEY);
        doThrow(new SshProvisioningException(SshProvisioningException.Kind.INFRA, "timeout")).when(provisionerSpy).del("bob");
        assertThrows(ResponseStatusException.class, () -> service.delete("admin", id2));
        assertEquals(SshAccessStatus.ERROR, db.get(id2).getSshStatus());
    }

    @Test
    void delCompteInconnuEstOk() {
        Long id = create("alice", true);
        service.delete("admin", id);
        assertTrue(provisioner.list().isEmpty());
    }

    @Test
    void refusSurSoiMeme() {
        Long id = create("admin", false);
        assertThrows(ResponseStatusException.class, () -> service.setEnabled("admin", id, false));
        assertThrows(ResponseStatusException.class, () -> service.delete("admin", id));
    }

    @Test
    void reconciliationDetecteOrphelinsManquantsEtDivergences() {
        Long a = create("alice", true);
        service.registerKey("admin", a, KEY);
        Long b = create("bob", true);
        service.registerKey("admin", b, KEY);
        Long c = create("carol", true);
        provisioner.add("orphan", KEY);
        provisioner.del("bob");
        provisioner.disable("alice");
        SshAccessService.Reconciliation r = service.reconcile("admin");
        assertEquals(java.util.List.of("orphan"), r.orphanVpsAccounts());
        assertEquals(java.util.List.of("bob"), r.usersMissingOnVps());
        assertEquals(1, r.statusMismatches().size());
        assertTrue(r.statusMismatches().getFirst().startsWith("alice"));
        assertEquals(java.util.List.of("carol"), r.pendingKey());
        assertNotEquals(a, c);
    }

    @Test
    void confClientPrefilleSansSecret() {
        Long id = create("alice", true);
        String conf = service.clientConf(id);
        assertTrue(conf.contains("SSH_USER=tio_alice"));
        assertTrue(conf.contains("LOCAL_PORT=18080"));
        assertTrue(conf.contains("REMOTE=127.0.0.1:9003"));
        assertThrows(ResponseStatusException.class, () -> service.clientConf(create("bob", false)));
    }
}
