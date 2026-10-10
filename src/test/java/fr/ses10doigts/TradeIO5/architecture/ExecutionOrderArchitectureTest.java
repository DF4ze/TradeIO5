package fr.ses10doigts.tradeIO5.architecture;

import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import fr.ses10doigts.tradeIO5.service.execution.exchange.OkxSpotOrderClient;
import fr.ses10doigts.tradeIO5.service.execution.exchange.SpotOrderPort;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gardes d'architecture du chemin d'achat réel : (1) seules les classes de {@code service.execution.exchange} déclarent des
 * méthodes d'ordre ; (2) seules {@code service.execution.exchange} et {@code service.execution.run} référencent ce package
 * (donc ni le bench, ni le plan, ni les contrôleurs de lecture, ni le connecteur de lecture) ; (3) le client OKX n'est un
 * bean que si LIVE et déverrouillé ; (4) LIVE sans déverrouillage ou sans clé maître échoue au démarrage.
 */
@DisplayName("Chemin d'achat réel : gardes d'architecture")
class ExecutionOrderArchitectureTest {

    private static final String ROOT = "fr/ses10doigts/tradeIO5/";
    private static final String EXCHANGE = ROOT + "service/execution/exchange/";
    private static final String RUN = ROOT + "service/execution/run/";
    private static final Pattern ORDER_METHOD = Pattern.compile("(?i)^(buy|sell|submit|(place|new|create|cancel)Order\\w*)$");

    /** Simulations pures du carnet (calcul de glissement) : aucun appel réseau, aucun ordre. */
    private static final java.util.Set<String> SIMULATION_ONLY = java.util.Set.of(
            "fr.ses10doigts.tradeIO5.service.market.instrument.BookSlippage");

    private static List<Resource> productionClasses() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> out = new ArrayList<>();
        for (Resource r : resolver.getResources("classpath*:" + ROOT + "**/*.class")) {
            if (!r.getURL().getPath().contains("test-classes")) {
                out.add(r);
            }
        }
        return out;
    }

    @Test
    @DisplayName("Seul service.execution.exchange déclare des méthodes d'ordre")
    void onlyExchangeDeclaresOrderMethods() throws Exception {
        CachingMetadataReaderFactory readers = new CachingMetadataReaderFactory();
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (Resource r : productionClasses()) {
            String name = readers.getMetadataReader(r).getClassMetadata().getClassName();
            scanned++;
            if (name.startsWith("fr.ses10doigts.tradeIO5.service.execution.exchange.") || SIMULATION_ONLY.contains(name)) {
                continue;
            }
            for (Method m : Class.forName(name, false, getClass().getClassLoader()).getDeclaredMethods()) {
                if (!m.isSynthetic() && ORDER_METHOD.matcher(m.getName()).matches()) {
                    violations.add(name + "#" + m.getName());
                }
            }
        }
        assertTrue(scanned > 200, "scan vide : " + scanned);
        assertEquals(List.of(), violations);
    }

    @Test
    @DisplayName("Seules exchange et run référencent le package exchange (analyse du pool de constantes)")
    void onlyRunReferencesExchange() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Resource r : productionClasses()) {
            String path = r.getURL().getPath();
            if (path.contains("/" + EXCHANGE) || path.contains("/" + RUN)) {
                continue;
            }
            String content = new String(r.getContentAsByteArray(), StandardCharsets.ISO_8859_1);
            if (content.contains(EXCHANGE)) {
                violations.add(path.substring(path.indexOf(ROOT)));
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    @DisplayName("Le détecteur de pool de constantes voit bien la référence (OrderExecutor référence le port)")
    void detectorSeesReferences() throws Exception {
        Resource executor = new PathMatchingResourcePatternResolver()
                .getResource("classpath:" + RUN + "OrderExecutor.class");
        assertTrue(new String(executor.getContentAsByteArray(), StandardCharsets.ISO_8859_1).contains(EXCHANGE));
    }

    @Test
    @DisplayName("Bean OkxSpotOrderClient absent par défaut, en LIVE seul et en déverrouillé seul ; présent si les deux")
    void conditionalBean() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(DomainClock.class, () -> new FixedDomainClock(Instant.EPOCH))
                .withUserConfiguration(OkxSpotOrderClient.class);
        runner.run(c -> assertTrue(c.getBeansOfType(SpotOrderPort.class).isEmpty()));
        runner.withPropertyValues("tradeio.execution.mode=LIVE")
                .run(c -> assertTrue(c.getBeansOfType(SpotOrderPort.class).isEmpty()));
        runner.withPropertyValues("tradeio.execution.live-unlocked=true")
                .run(c -> assertTrue(c.getBeansOfType(SpotOrderPort.class).isEmpty()));
        runner.withPropertyValues("tradeio.execution.mode=DRY_RUN", "tradeio.execution.live-unlocked=true")
                .run(c -> assertTrue(c.getBeansOfType(SpotOrderPort.class).isEmpty()));
        runner.withPropertyValues("tradeio.execution.mode=LIVE", "tradeio.execution.live-unlocked=true")
                .run(c -> assertEquals(1, c.getBeansOfType(SpotOrderPort.class).size()));
    }

    @Test
    @DisplayName("LIVE sans déverrouillage ou sans clé maître => échec ; OFF / DRY_RUN => aucune exécution réelle")
    void liveLocked() {
        Duration ttl = Duration.ofHours(1);
        BigDecimal tol = new BigDecimal("0.1");
        assertThrows(IllegalStateException.class, () -> new ExecutionSettings("LIVE", ttl, tol, false, true));
        assertThrows(IllegalStateException.class, () -> new ExecutionSettings("LIVE", ttl, tol, true, false));
        assertThrows(IllegalStateException.class, () -> new ExecutionSettings("LIVE", ttl, tol));
        assertTrue(new ExecutionSettings("LIVE", ttl, tol, true, true).liveExecution());
        assertDoesNotThrow(() -> new ExecutionSettings("DRY_RUN", ttl, tol, false, false));
        assertEquals(false, new ExecutionSettings("DRY_RUN", ttl, tol, true, true).liveExecution());
        assertEquals(false, new ExecutionSettings("OFF", ttl, tol, true, true).liveExecution());
    }
}
