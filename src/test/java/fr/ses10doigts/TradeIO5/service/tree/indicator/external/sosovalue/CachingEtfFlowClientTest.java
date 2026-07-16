package fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.EtfFlowResponse;
import fr.ses10doigts.tradeIO5.model.entity.market.EtfFlowSnapshotEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.market.EtfFlowSnapshotRepository;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cf. docs/etude-cache-etf-flow-historisation.md. Vérifie le coeur du décorateur cache-aside :
 * - fetch() ne rappelle jamais le réseau si déjà rafraîchi aujourd'hui (gate sur fetchedAt) ;
 * - fetch() rappelle le réseau dès que le dernier snapshot connu date d'un jour antérieur ;
 * - refresh() bypasse toujours le gate (usage EtfFlowHistorizationJob) ;
 * - une réponse invalide n'est jamais persistée (pas de "négatif caching") ;
 * - upsert par (asset, date) et tolérance à la violation de contrainte unique concurrente.
 */
@DisplayName("CachingEtfFlowClient")
class CachingEtfFlowClientTest {

    private static final Instant TODAY = Instant.parse("2026-07-16T10:00:00Z");
    private static final ApiCredentialDTO CREDENTIAL =
            new ApiCredentialDTO(WebProviderCode.SOSOVALUE, "test-key", "", "http://localhost");

    private EtfFlowProvider delegate;
    private EtfFlowSnapshotRepository repository;
    private CachingEtfFlowClient client;

    @BeforeEach
    void setUp() {
        delegate = mock(EtfFlowProvider.class);
        repository = mock(EtfFlowSnapshotRepository.class);
        FixedDomainClock clock = new FixedDomainClock(TODAY);
        client = new CachingEtfFlowClient(delegate, repository, clock);
    }

    @Test
    @DisplayName("fetch() : aucun snapshot connu => appel réseau + persistance")
    void fetch_noPriorSnapshot_callsDelegateAndPersists() {
        when(repository.findTopByAssetOrderByDateDesc(EtfFlowAsset.BTC)).thenReturn(Optional.empty());
        when(repository.findByAssetAndDate(eq(EtfFlowAsset.BTC), any())).thenReturn(Optional.empty());
        EtfFlowResponse live = EtfFlowResponse.builder()
                .valid(true).date(LocalDate.of(2026, 7, 15)).total(-55_066_297.0).byIssuer(java.util.Map.of()).build();
        when(delegate.fetch(CREDENTIAL, EtfFlowAsset.BTC)).thenReturn(live);

        EtfFlowResponse result = client.fetch(CREDENTIAL, EtfFlowAsset.BTC);

        assertTrue(result.isValid());
        assertEquals(-55_066_297.0, result.getTotal(), 0.001);
        verify(delegate, times(1)).fetch(CREDENTIAL, EtfFlowAsset.BTC);

        ArgumentCaptor<EtfFlowSnapshotEntity> captor = ArgumentCaptor.forClass(EtfFlowSnapshotEntity.class);
        verify(repository).save(captor.capture());
        assertEquals(EtfFlowAsset.BTC, captor.getValue().getAsset());
        assertEquals(LocalDate.of(2026, 7, 15), captor.getValue().getDate());
        assertEquals(-55_066_297.0, captor.getValue().getTotalNetInflow(), 0.001);
        assertEquals(TODAY, captor.getValue().getFetchedAt());
    }

    @Test
    @DisplayName("fetch() : snapshot déjà rafraîchi aujourd'hui => cache HIT, aucun appel réseau")
    void fetch_alreadyFetchedToday_cacheHit() {
        EtfFlowSnapshotEntity cached = EtfFlowSnapshotEntity.builder()
                .asset(EtfFlowAsset.ETH).date(LocalDate.of(2026, 7, 16)).totalNetInflow(12_345_678.0)
                .fetchedAt(TODAY.minus(Duration.ofHours(2))) // même jour calendaire que TODAY
                .build();
        when(repository.findTopByAssetOrderByDateDesc(EtfFlowAsset.ETH)).thenReturn(Optional.of(cached));

        EtfFlowResponse result = client.fetch(CREDENTIAL, EtfFlowAsset.ETH);

        assertTrue(result.isValid());
        assertEquals(LocalDate.of(2026, 7, 16), result.getDate());
        assertEquals(12_345_678.0, result.getTotal(), 0.001);
        assertTrue(result.getByIssuer().isEmpty());
        verify(delegate, never()).fetch(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("fetch() : dernier snapshot connu d'un jour antérieur => cache MISS, appel réseau")
    void fetch_lastSnapshotFromPreviousDay_cacheMiss() {
        EtfFlowSnapshotEntity stale = EtfFlowSnapshotEntity.builder()
                .asset(EtfFlowAsset.BTC).date(LocalDate.of(2026, 7, 15)).totalNetInflow(1.0)
                .fetchedAt(TODAY.minus(Duration.ofDays(1)))
                .build();
        when(repository.findTopByAssetOrderByDateDesc(EtfFlowAsset.BTC)).thenReturn(Optional.of(stale));
        when(repository.findByAssetAndDate(eq(EtfFlowAsset.BTC), any())).thenReturn(Optional.empty());
        EtfFlowResponse fresh = EtfFlowResponse.builder()
                .valid(true).date(LocalDate.of(2026, 7, 16)).total(2.0).byIssuer(java.util.Map.of()).build();
        when(delegate.fetch(CREDENTIAL, EtfFlowAsset.BTC)).thenReturn(fresh);

        EtfFlowResponse result = client.fetch(CREDENTIAL, EtfFlowAsset.BTC);

        assertEquals(LocalDate.of(2026, 7, 16), result.getDate());
        verify(delegate, times(1)).fetch(CREDENTIAL, EtfFlowAsset.BTC);
        verify(repository).save(any());
    }

    @Test
    @DisplayName("refresh() : bypass toujours le gate, même si déjà rafraîchi aujourd'hui")
    void refresh_alwaysBypassesGate() {
        EtfFlowSnapshotEntity cached = EtfFlowSnapshotEntity.builder()
                .asset(EtfFlowAsset.BTC).date(LocalDate.of(2026, 7, 16)).totalNetInflow(1.0)
                .fetchedAt(TODAY)
                .build();
        when(repository.findByAssetAndDate(EtfFlowAsset.BTC, LocalDate.of(2026, 7, 16))).thenReturn(Optional.of(cached));
        EtfFlowResponse fresh = EtfFlowResponse.builder()
                .valid(true).date(LocalDate.of(2026, 7, 16)).total(99.0).byIssuer(java.util.Map.of()).build();
        when(delegate.fetch(CREDENTIAL, EtfFlowAsset.BTC)).thenReturn(fresh);

        EtfFlowResponse result = client.refresh(CREDENTIAL, EtfFlowAsset.BTC);

        assertEquals(99.0, result.getTotal(), 0.001);
        verify(delegate, times(1)).fetch(CREDENTIAL, EtfFlowAsset.BTC);
        // Upsert : la ligne existante (même asset+date) est mise à jour, pas recréée.
        verify(repository).save(cached);
        assertEquals(99.0, cached.getTotalNetInflow(), 0.001);
    }

    @Test
    @DisplayName("refresh() : réponse invalide => jamais persistée (pas de négatif caching)")
    void refresh_invalidResponse_neverPersisted() {
        when(delegate.fetch(CREDENTIAL, EtfFlowAsset.ETH)).thenReturn(EtfFlowResponse.invalid());

        EtfFlowResponse result = client.refresh(CREDENTIAL, EtfFlowAsset.ETH);

        assertFalse(result.isValid());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("persist() : violation de contrainte unique concurrente => ignorée sans exception")
    void refresh_concurrentUniqueConstraintViolation_swallowed() {
        when(repository.findByAssetAndDate(eq(EtfFlowAsset.BTC), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        EtfFlowResponse live = EtfFlowResponse.builder()
                .valid(true).date(LocalDate.of(2026, 7, 16)).total(1.0).byIssuer(java.util.Map.of()).build();
        when(delegate.fetch(CREDENTIAL, EtfFlowAsset.BTC)).thenReturn(live);

        EtfFlowResponse result = client.refresh(CREDENTIAL, EtfFlowAsset.BTC);

        // Pas d'exception propagée, et la réponse live est quand même retournée à l'appelant.
        assertTrue(result.isValid());
        assertEquals(1.0, result.getTotal(), 0.001);
    }
}
