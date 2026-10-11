package fr.ses10doigts.tradeIO5.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.admin.AdminActionDto;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionLog;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.repository.admin.AdminActionLogRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({AdminActionLogService.class, AdminActionLogServiceTest.Config.class})
@DisplayName("Journal admin : append-only, payload sans secret, pagination bornée, écriture non bloquante")
class AdminActionLogServiceTest {

    static final Pattern MUTATOR = Pattern.compile("(?i)^(update|delete|remove|set|merge|clear|truncate)\\w*$");

    @TestConfiguration
    static class Config {
        @Bean
        DomainClock clock() {
            return new FixedDomainClock(Instant.parse("2026-10-11T12:00:00Z"));
        }
    }

    @Autowired private AdminActionLogService journal;

    @Test
    @DisplayName("Écrit auteur, action, cible, issue ; lecture du plus récent au plus ancien")
    void recordsAndPagesNewestFirst() {
        journal.record("clem", AdminAction.KILL_SWITCH, "lève", AdminActionLogService.payload("engaged", false), AdminActionOutcome.OK);
        journal.record("clem", AdminAction.RUN, "plan:42", AdminActionLogService.payload("sent", 1), AdminActionOutcome.REFUSED);

        AdminActionDto.Page page = journal.page(0, 20);
        List<AdminActionDto> items = page.items();
        assertTrue(page.totalElements() >= 2);
        assertEquals("RUN", items.getFirst().action());
        assertEquals("plan:42", items.getFirst().target());
        assertEquals("REFUSED", items.getFirst().outcome());
        assertEquals("clem", items.getFirst().actor());
        assertEquals(Instant.parse("2026-10-11T12:00:00Z"), items.getFirst().createdAt());
        assertTrue(items.get(0).id() > items.get(1).id());
    }

    @Test
    @DisplayName("Taille de page bornée à la constante partagée, plancher à 1, page négative ramenée à 0")
    void pageBounds() {
        AdminActionDto.Page big = journal.page(0, 100_000);
        assertEquals(ExecutionDefaults.MAX_PAGE_SIZE, big.size());
        assertEquals(1, journal.page(0, 0).size());
        assertEquals(0, journal.page(-3, 20).page());
    }

    @Test
    @DisplayName("Payload : toute clé qui ressemble à un secret est écartée, rien d'un secret n'est écrit")
    void payloadHasNoSecret() throws Exception {
        journal.record("clem", AdminAction.CREDENTIAL_STORED, "credential:1", AdminActionLogService.payload("provider", "OKX",
                "apiKey", "KEY-123", "secretKey", "SEC-456", "passphrase", "PASS-789", "password", "PW", "userId", 3),
                AdminActionOutcome.OK);
        AdminActionDto entry = journal.page(0, 1).items().getFirst();
        for (String secret : new String[] {"KEY-123", "SEC-456", "PASS-789", "PW", "apiKey", "secretKey", "passphrase"}) {
            assertFalse(entry.payload().contains(secret), secret);
        }
        assertEquals("OKX", new ObjectMapper().readTree(entry.payload()).get("provider").asText());
    }

    @Test
    @DisplayName("Un échec d'écriture du journal ne casse pas l'action (WARN seulement)")
    void writeFailureIsSwallowed() {
        AdminActionLogRepository broken = mock(AdminActionLogRepository.class);
        when(broken.save(any())).thenThrow(new IllegalStateException("base indisponible"));
        AdminActionLogService service = new AdminActionLogService(broken, new FixedDomainClock(Instant.EPOCH),
                mock(PlatformTransactionManager.class));
        assertDoesNotThrow(() -> service.record("clem", AdminAction.RUN, "plan:1", null, AdminActionOutcome.OK));
    }

    @Test
    @DisplayName("Append-only : repository sans JpaRepository ni update/delete, entité sans setter, service sans méthode de modification")
    void appendOnly() {
        assertFalse(JpaRepository.class.isAssignableFrom(AdminActionLogRepository.class));
        for (Method m : AdminActionLogRepository.class.getMethods()) {
            assertTrue(m.getName().equals("save") || m.getName().startsWith("find"), "méthode inattendue : " + m.getName());
        }
        for (Method m : AdminActionLog.class.getDeclaredMethods()) {
            assertFalse(m.getName().startsWith("set"), "setter : " + m.getName());
        }
        Arrays.stream(AdminActionLogService.class.getDeclaredMethods()).filter(m -> Modifier.isPublic(m.getModifiers()))
                .forEach(m -> assertFalse(MUTATOR.matcher(m.getName()).matches(), "méthode de modification : " + m.getName()));
        assertNotNull(AdminActionLog.builder().build());
    }
}
