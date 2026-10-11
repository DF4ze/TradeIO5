package fr.ses10doigts.tradeIO5.service.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import fr.ses10doigts.tradeIO5.model.entity.market.CandleEntity;
import fr.ses10doigts.tradeIO5.model.entity.market.EtfFlowSnapshotEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import jakarta.persistence.EntityManager;

@DataJpaTest
@DisplayName("HistoricalDataBackupService")
class HistoricalDataBackupServiceTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager em;
    @TempDir
    Path dir;

    private HistoricalDataBackupService service() {
        return new HistoricalDataBackupService(jdbc, dir.toString());
    }

    private void seed() {
        em.persist(CandleEntity.builder().source(MarketDataSource.BINANCE).pair("BTCUSDT").timeFrame(TimeFrame.H1)
                .timestamp(Instant.parse("2024-01-01T00:00:00Z")).open(new BigDecimal("1.5")).high(new BigDecimal("2"))
                .low(new BigDecimal("1")).close(new BigDecimal("1.75")).volume(new BigDecimal("10.123456")).build());
        em.persist(EtfFlowSnapshotEntity.builder().asset(EtfFlowAsset.BTC).date(LocalDate.of(2024, 1, 2))
                .totalNetInflow(12.5).fetchedAt(Instant.parse("2024-01-03T10:00:00Z")).build());
        em.flush();
    }

    private long count(String t) {
        return jdbc.queryForObject("select count(*) from " + t, Long.class);
    }

    @Test
    @DisplayName("export puis import après vidage : données identiques ; réimport = no-op")
    void roundTrip() throws Exception {
        seed();
        var svc = service();
        var exp = svc.exportAll();
        assertEquals(1L, exp.get("candle"));
        assertEquals(1L, exp.get("etf_flow_snapshot"));

        jdbc.update("delete from candle");
        jdbc.update("delete from etf_flow_snapshot");
        em.clear();

        var imp = svc.importIfEmpty();
        assertEquals(1L, imp.get("candle"));
        assertEquals(1L, imp.get("etf_flow_snapshot"));
        CandleEntity c = em.createQuery("from CandleEntity", CandleEntity.class).getSingleResult();
        assertEquals("BTCUSDT", c.getPair());
        assertEquals(0, new BigDecimal("10.123456").compareTo(c.getVolume()));
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), c.getTimestamp());
        EtfFlowSnapshotEntity e = em.createQuery("from EtfFlowSnapshotEntity", EtfFlowSnapshotEntity.class).getSingleResult();
        assertEquals(LocalDate.of(2024, 1, 2), e.getDate());
        assertEquals(12.5, e.getTotalNetInflow());

        assertEquals(0L, svc.importIfEmpty().get("candle"));
        assertEquals(1L, count("candle"));
    }

    @Test
    @DisplayName("table vide : export n'écrase pas le fichier existant")
    void emptyDoesNotOverwrite() throws Exception {
        seed();
        var svc = service();
        svc.exportAll();
        long size = Files.size(svc.fileOf("candle"));
        jdbc.update("delete from candle");
        assertEquals(0L, svc.exportAll().get("candle"));
        assertEquals(size, Files.size(svc.fileOf("candle")));
    }

    @Test
    @DisplayName("fichier absent ou table non vide : import no-op")
    void importNoop() throws Exception {
        assertEquals(0L, service().importIfEmpty().get("candle"));
        seed();
        var svc = service();
        svc.exportAll();
        assertEquals(0L, svc.importIfEmpty().get("candle"));
        assertEquals(1L, count("candle"));
        assertFalse(Files.exists(dir.resolve("candle.tsv.gz.tmp")));
        assertTrue(Files.exists(svc.fileOf("etf_flow_snapshot")));
    }

    @Test
    @DisplayName("escape/unescape symétriques")
    void escaping() {
        String s = "a\tb\nc\\d\re";
        assertEquals(s, HistoricalDataBackupService.unescape(HistoricalDataBackupService.escape(s)));
    }
}
