package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminProviderDto;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Admin providers : déclaration, modification, validation, journal")
class AdminProviderServiceTest {

    private ProviderRepository repository;
    private AdminActionLogService journal;
    private AdminProviderService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProviderRepository.class);
        journal = mock(AdminActionLogService.class);
        service = new AdminProviderService(repository, journal);
        when(repository.save(any(WebProvider.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("liste : les codes sans ligne sont proposés")
    void listsAvailableCodes() {
        when(repository.findAll()).thenReturn(List.of(WebProvider.builder().code(WebProviderCode.OKX).name("OKX").build()));
        AdminProviderDto.Listing listing = service.list();
        assertEquals(1, listing.providers().size());
        assertFalse(listing.available().contains("OKX"));
        assertTrue(listing.available().contains("ONE_TRADING"));
    }

    @Test
    @DisplayName("création : URL normalisée (sans / final), actif par défaut, journal OK")
    void createsProvider() {
        when(repository.findByCode(WebProviderCode.ONE_TRADING)).thenReturn(Optional.empty());
        AdminProviderDto dto = service.create("one_trading", " One Trading ", "https://api.onetrading.com/fast/v1/", null, "admin");
        assertEquals("ONE_TRADING", dto.code());
        assertEquals("One Trading", dto.name());
        assertEquals("https://api.onetrading.com/fast/v1", dto.apiBaseUrl());
        assertTrue(dto.enabled());
        verify(journal).record(eq("admin"), eq(AdminAction.PROVIDER), any(), any(), eq(AdminActionOutcome.OK));
    }

    @Test
    @DisplayName("création refusée : code inconnu, déjà déclaré, URL non https, nom vide")
    void refusesInvalidCreations() {
        when(repository.findByCode(WebProviderCode.OKX)).thenReturn(Optional.of(WebProvider.builder().build()));
        assertThrows(IllegalArgumentException.class, () -> service.create("NOPE", "x", null, true, "admin"));
        assertThrows(IllegalArgumentException.class, () -> service.create("OKX", "x", null, true, "admin"));
        assertThrows(IllegalArgumentException.class, () -> service.create("ONE_TRADING", "x", "http://a.b", true, "admin"));
        assertThrows(IllegalArgumentException.class, () -> service.create("ONE_TRADING", " ", null, true, "admin"));
        verify(repository, never()).save(any());
        verify(journal, org.mockito.Mockito.times(4)).record(eq("admin"), eq(AdminAction.PROVIDER), any(), any(),
                eq(AdminActionOutcome.REFUSED));
    }

    @Test
    @DisplayName("modification : nom, URL et activation ; inconnu => 404 journalisé")
    void updatesProvider() {
        WebProvider existing = WebProvider.builder().id(3L).code(WebProviderCode.KRAKEN).name("Kraken").enabled(true).build();
        when(repository.findById(3L)).thenReturn(Optional.of(existing));
        AdminProviderDto dto = service.update(3L, "Kraken EU", "https://api.kraken.com", false, "admin");
        assertEquals("Kraken EU", dto.name());
        assertFalse(dto.enabled());
        assertThrows(AdminResourceNotFoundException.class, () -> service.update(99L, "x", null, true, "admin"));
        verify(journal).record(eq("admin"), eq(AdminAction.PROVIDER), eq("provider:99"), any(), eq(AdminActionOutcome.REFUSED));
    }
}
