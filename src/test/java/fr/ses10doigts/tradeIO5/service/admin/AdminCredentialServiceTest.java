package fr.ses10doigts.tradeIO5.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.admin.AdminActionDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.AdminBindingDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.AdminCredentialDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.AdminPlanDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.AdminStateDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.CredentialStoredDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.OwnTradeCredentialRequest;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.execution.credential.MasterKeyMissingException;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Admin credentials : liste masquée, (dés)activation et saisie journalisées, aucun secret nulle part")
class AdminCredentialServiceTest {

    private static final String KEY = "AAAABBBBCCCC1234";
    private static final String SECRET = "SECRET-VALUE-xyz";
    private static final String PASSPHRASE = "PASSPHRASE-VALUE-abc";

    private ApiCredentialRepository repository;
    private UserRepository users;
    private TradeCredentialService trade;
    private AdminActionLogService journal;
    private AdminCredentialService service;
    private final User owner = User.builder().id(7L).username("alice").build();
    private final WebProvider okx = WebProvider.builder().code(WebProviderCode.OKX).build();

    @BeforeEach
    void setUp() {
        repository = mock(ApiCredentialRepository.class);
        users = mock(UserRepository.class);
        trade = mock(TradeCredentialService.class);
        journal = mock(AdminActionLogService.class);
        service = new AdminCredentialService(repository, users, trade, journal);
    }

    private ApiCredential credential(Long id, CredentialScope scope, String apiKey) {
        return ApiCredential.builder().id(id).user(owner).webProvider(okx).scope(scope).apiKey(apiKey).secretKey(SECRET)
                .passphrase(PASSPHRASE).enabled(true).createdAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build();
    }

    @Test
    @DisplayName("Liste : 4 derniers caractères pour READ, 'chiffrée' pour TRADE, clé courte sans fragment ; JSON sans secret")
    void listIsMasked() throws Exception {
        when(repository.findAll()).thenReturn(List.of(credential(2L, CredentialScope.TRADE, "enc:v1:AAAA"),
                credential(1L, CredentialScope.READ, KEY), credential(3L, CredentialScope.READ, "short")));

        List<AdminCredentialDto> list = service.list();

        assertEquals("1234", list.stream().filter(c -> c.id() == 1L).findFirst().orElseThrow().apiKeyHint());
        assertEquals(AdminCredentialDto.ENCRYPTED_HINT, list.stream().filter(c -> c.id() == 2L).findFirst().orElseThrow().apiKeyHint());
        assertNull(list.stream().filter(c -> c.id() == 3L).findFirst().orElseThrow().apiKeyHint());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(list);
        for (String leak : new String[] {KEY, SECRET, PASSPHRASE, "enc:v1"}) {
            assertFalse(json.contains(leak), leak);
        }
    }

    @Test
    @DisplayName("(Dés)activation : enregistrée et journalisée (CREDENTIAL_ENABLED) ; inconnue => 404 et REFUSED")
    void setEnabled() {
        ApiCredential c = credential(5L, CredentialScope.TRADE, "enc:v1:x");
        when(repository.findById(5L)).thenReturn(Optional.of(c));
        when(repository.save(c)).thenReturn(c);

        AdminCredentialDto dto = service.setEnabled(5L, false, "root");

        assertFalse(dto.enabled());
        verify(journal).record(eq("root"), eq(AdminAction.CREDENTIAL_ENABLED), eq("credential:5"), any(), eq(AdminActionOutcome.OK));
        assertThrows(AdminResourceNotFoundException.class, () -> service.setEnabled(99L, true, "root"));
        verify(journal).record("root", AdminAction.CREDENTIAL_ENABLED, "credential:99", null, AdminActionOutcome.REFUSED);
    }

    @Test
    @DisplayName("Saisie : journal CREDENTIAL_STORED sans aucun secret dans le payload")
    @SuppressWarnings("unchecked")
    void storeIsJournaledWithoutSecret() {
        when(trade.store(owner, WebProviderCode.OKX, KEY, SECRET, PASSPHRASE, true))
                .thenReturn(credential(11L, CredentialScope.TRADE, "enc:v1:x"));

        service.store(owner, "alice", WebProviderCode.OKX, KEY, SECRET, PASSPHRASE, null);

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(journal).record(eq("alice"), eq(AdminAction.CREDENTIAL_STORED), eq("credential:11"), payload.capture(),
                eq(AdminActionOutcome.OK));
        String rendered = payload.getValue().toString();
        for (String leak : new String[] {KEY, SECRET, PASSPHRASE}) {
            assertFalse(rendered.contains(leak), leak);
        }
    }

    @Test
    @DisplayName("Saisie : autre provider => IllegalArgumentException, clé maître absente => MasterKeyMissingException, tous deux REFUSED")
    void storeRefusals() {
        when(trade.store(owner, WebProviderCode.KRAKEN, KEY, SECRET, PASSPHRASE, true))
                .thenThrow(new IllegalArgumentException("Aucun client d'ordres pour KRAKEN"));
        when(trade.store(owner, WebProviderCode.OKX, KEY, SECRET, PASSPHRASE, true)).thenThrow(new MasterKeyMissingException());

        assertThrows(IllegalArgumentException.class, () -> service.store(owner, "alice", WebProviderCode.KRAKEN, KEY, SECRET, PASSPHRASE, true));
        assertThrows(MasterKeyMissingException.class, () -> service.store(owner, "alice", WebProviderCode.OKX, KEY, SECRET, PASSPHRASE, true));
        assertThrows(IllegalArgumentException.class, () -> service.store(owner, "alice", null, KEY, SECRET, PASSPHRASE, true));

        verify(journal).record(eq("alice"), eq(AdminAction.CREDENTIAL_STORED), eq("user:7/KRAKEN"), any(), eq(AdminActionOutcome.REFUSED));
        verify(journal).record(eq("alice"), eq(AdminAction.CREDENTIAL_STORED), eq("user:7/OKX"), any(), eq(AdminActionOutcome.REFUSED));
        verify(journal).record(eq("alice"), eq(AdminAction.CREDENTIAL_STORED), eq("user:7/null"), any(), eq(AdminActionOutcome.REFUSED));
    }

    @Test
    @DisplayName("Endpoint admin : utilisateur inconnu => IllegalArgumentException, rien de stocké")
    void unknownTargetUser() {
        when(users.findById(404L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.storeFor(404L, "root", WebProviderCode.OKX, KEY, SECRET, PASSPHRASE, true));
        assertThrows(IllegalArgumentException.class, () -> service.storeFor(null, "root", WebProviderCode.OKX, KEY, SECRET, PASSPHRASE, true));
        verify(trade, never()).store(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("DTO de lecture : aucun champ secret/passphrase/apiKey complet ; toString du corps de saisie masqué")
    void dtosCarryNoSecret() {
        Pattern forbidden = Pattern.compile("(?i)^(secret\\w*|passphrase|apikey|api_key|password)$");
        for (Class<?> dto : List.of(AdminCredentialDto.class, AdminBindingDto.class, AdminPlanDto.class, AdminStateDto.class,
                AdminActionDto.class, AdminActionDto.Page.class, CredentialStoredDto.class)) {
            for (RecordComponent component : dto.getRecordComponents()) {
                assertFalse(forbidden.matcher(component.getName()).matches(), dto.getSimpleName() + "." + component.getName());
            }
        }
        String s = new OwnTradeCredentialRequest(WebProviderCode.OKX, KEY, SECRET, PASSPHRASE, true).toString();
        for (String leak : new String[] {KEY, SECRET, PASSPHRASE}) {
            assertFalse(s.contains(leak), leak);
        }
        assertTrue(s.contains("OKX"));
    }
}
