package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.ses10doigts.tradeIO5.model.dto.market.BucketView;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.entity.market.CandleEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.repository.market.CandleRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.Bucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Runner manuel (réseau + DB réels) — Étape 5 de la roadmap "Trend unifié" : gate d'infrastructure
 * qui mesure et documente l'état réel de l'historique D1/H1 persisté pour BTCUSDT, et vérifie le
 * comportement du cache-aside ({@link CachingMarketDataApiClient}) sur une fenêtre pluriannuelle.
 * <p>
 * Cf. docs/prompts/prompt-implementation-trend-unifie-etape5-historique-d1.md (prompt d'origine)
 * et docs/etudes/etude-indicateur-trend-unifie.md §8 (contexte). Ce lot ne calibre ni ne compare
 * rien (pas l'Étape 2 ni l'Étape 6) : il mesure, documente, et rend un verdict GO/NO-GO factuel.
 * <p>
 * <b>Section 0 — écart de conception assumé, à lire avant toute exécution :</b>
 * <ul>
 *   <li><b>Ce runner cible la vraie base MySQL de dev de l'application (celle
 *   d'{@code application-dev.properties}, {@code localhost:3306/tradeio5}), PAS le H2 éphémère
 *   utilisé par les autres bancs de calibration manuels de ce projet
 *   ({@code RainbowDcaBacktestManualRunnerTest}, {@code YoutubeManualNetworkTest}).</b> C'est
 *   délibéré : le sujet de cette Étape 5 est justement l'état RÉELLEMENT persisté (cf. prompt §1 :
 *   "l'état hérité de juillet 2026 + ce qui s'est accumulé depuis") — un H2 recréé à chaque
 *   exécution serait vide à chaque run et ne dirait rien de l'état réel de l'application. Les
 *   écritures effectuées par ce runner sont exactement celles que {@link CachingMarketDataApiClient}
 *   ferait en production (mêmes beans, même mécanisme, aucune ligne de code modifiée pour ce lot) :
 *   insertion de bougies closes uniquement, protégée par la contrainte unique
 *   {@code uk_candle_source_pair_tf_ts} — idempotent, jamais de suppression ni de mise à jour.
 *   {@code spring.jpa.hibernate.ddl-auto=none} est forcé explicitement pour ce test : le schéma de
 *   cette base existe déjà, aucune migration auto ne doit être tentée depuis ce test.</li>
 *   <li>Les crons (@Scheduled) du projet sont désactivés par défaut (propriété cron non positionnée
 *   → {@code Scheduled.CRON_DISABLED}) : le contexte Spring complet démarré par {@code @SpringBootTest}
 *   ne déclenche donc aucun job planifié pendant l'exécution de ce runner.</li>
 *   <li>Avant de relancer ce runner, vérifier l'état de la base réelle (ex. via une requête SQL
 *   directe) plutôt que de supposer qu'elle est vide — cf. rapport généré par la précédente
 *   exécution sous {@code target/trend-unifie-etape5/}.</li>
 * </ul>
 * <p>
 * <b>Écart méthodologique §2/§3 du prompt (documenté aussi dans le rapport généré) :</b> le prompt
 * décrit un appel unique à {@code getCandles(..., since_large, until=now)} suivi d'un unique rejeu
 * de vérification. En pratique, Binance plafonne chaque réponse REST à ~1000 bougies quel que soit
 * le {@code limit} demandé, et {@link CachingMarketDataApiClient} ne boucle pas en interne sur un
 * trou trop grand pour une seule réponse — un appel unique sur plusieurs années ne peut donc
 * combler qu'une fraction du trou. Ce runner rejoue donc LE MÊME appel (mêmes {@code since}/
 * {@code until}, aucun découpage manuel ajouté côté runner) jusqu'à convergence (plus aucune
 * bougie nouvellement persistée), pour mesurer la profondeur RÉELLEMENT atteignable et le nombre
 * d'itérations nécessaires — sans modifier ni contourner le mécanisme de cache-aside lui-même.
 */
@SpringBootTest(properties = {
        "logging.level.fr.ses10doigts.tradeIO5=INFO",
        // Cible délibérément la vraie base de dev (cf. javadoc section 0), pas le H2 de test.
        "spring.datasource.url=jdbc:mysql://${MYSQL_HOST:localhost}:3306/tradeio5?rewriteBatchedStatements=true",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=klm",
        "spring.datasource.password=klm31",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=none"
})
@DisplayName("Étape 5 Trend unifié — profondeur/fiabilité historique D1/H1 BTCUSDT (réseau + DB dev réels, décommenter pour lancer)")
@org.junit.jupiter.api.Disabled("Runner manuel réseau+DB dev réels — commenter cette annotation pour lancer le diagnostic Étape 5.")
class HistoricalDataDepthManualRunnerTest {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(HistoricalDataDepthManualRunnerTest.class);

    private static final String SYMBOL = "BTC";
    private static final MarketDataSource SOURCE = MarketDataSource.BINANCE;
    /** Couvre à la fois la cible D1 (2017-08-17) et la cible H1 (2020-01-01) actées dans le prompt. */
    private static final Instant FETCH_SINCE = Instant.parse("2017-08-17T00:00:00Z");
    private static final Instant H1_TARGET_WINDOW_START = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant D1_TARGET_WINDOW_START = Instant.parse("2017-08-17T00:00:00Z");
    private static final int MAX_ITERATIONS = 120;
    private static final Duration MAX_TOTAL_DURATION = Duration.ofMinutes(20);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_INSTANT;

    @Autowired
    @Qualifier("cachingBinanceMarketDataApiClient")
    private MarketDataApiClient cachingBinanceClient;

    @Autowired
    private CandleRepository candleRepository;

    @Autowired
    private AssetProviderRepository assetProviderRepository;

    @Autowired
    private DomainClock clock;

    @Test
    @DisplayName("Profondeur/fiabilité historique D1 et H1 BTCUSDT via cache-aside — rapport GO/NO-GO")
    void mesureProfondeurEtFiabiliteHistoriqueD1H1_BTCUSDT() throws IOException {
        StringBuilder report = new StringBuilder();
        List<String> deviations = new ArrayList<>();
        Instant runStarted = clock.now();

        String providerSymbol = resolveProviderSymbol();
        report.append("# Étape 5 — Profondeur/fiabilité historique D1/H1 BTCUSDT\n\n");
        report.append("Exécution : ").append(runStarted).append("\n");
        report.append("Datasource ciblée : `jdbc:mysql://localhost:3306/tradeio5` (base de dev réelle, cf. javadoc section 0 du runner)\n");
        report.append("Symbole nu : `").append(SYMBOL).append("` -> providerSymbol Binance : `").append(providerSymbol).append("`\n\n");

        // ---- §1 (vu par ce runner, base réelle) : état AVANT toute action de ce run ----
        report.append("## 1. État de la base au démarrage de ce run (avant toute action de ce runner)\n\n");
        DepthSnapshot h1Before = snapshot(providerSymbol, TimeFrame.H1, FETCH_SINCE, runStarted);
        DepthSnapshot d1Before = snapshot(providerSymbol, TimeFrame.D1, D1_TARGET_WINDOW_START, runStarted);
        appendSnapshotSection(report, "H1", h1Before, FETCH_SINCE, runStarted);
        appendSnapshotSection(report, "D1", d1Before, D1_TARGET_WINDOW_START, runStarted);
        log.info("Baseline avant fetch — H1: {} bougie(s) [{}..{}], {} trou(s) ; D1: {} bougie(s), {} trou(s)",
                h1Before.count(), h1Before.min(), h1Before.max(), h1Before.gaps().size(),
                d1Before.count(), d1Before.gaps().size());

        // ---- Instrumentation réseau : compte les lignes "Appel réseau"/"Mismatch" émises par
        // CachingMarketDataApiClient (cf. prompt §2 : "instrumenter ou déduire des logs debug") ----
        ch.qos.logback.classic.Logger cachingLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CachingMarketDataApiClient.class);
        Level previousLevel = cachingLogger.getLevel();
        cachingLogger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        cachingLogger.addAppender(appender);

        try {
            // ---- §2 : tentative littérale D1 via le cache-aside (appel unique) ----
            report.append("## 2. Tentative D1 native via le cache-aside (§2 du prompt, appel unique)\n\n");
            int beforeD1 = appender.list.size();
            long startD1Nanos = System.nanoTime();
            String d1Outcome;
            try {
                List<MarketData> d1Result = cachingBinanceClient.getCandles(providerSymbol, TimeFrame.D1, D1_TARGET_WINDOW_START, runStarted, 0);
                d1Outcome = "Appel réussi, " + d1Result.size() + " bougie(s) retournée(s) (inattendu, cf. finding ci-dessous).";
            } catch (RuntimeException e) {
                d1Outcome = "ÉCHEC (attendu, cf. finding) — " + e.getClass().getSimpleName() + " : " + e.getMessage();
            }
            long d1Ms = (System.nanoTime() - startD1Nanos) / 1_000_000;
            long d1NetworkCalls = countMatching(appender.list, beforeD1, "Appel réseau");
            report.append("- Résultat : ").append(d1Outcome).append("\n");
            report.append("- Durée : ").append(d1Ms).append(" ms, appels réseau observés : ").append(d1NetworkCalls).append("\n");
            report.append("- **Finding architectural** : aucun `MarketDataApiClient` réel de ce projet (Binance, Kraken) ne supporte "
                    + "`TimeFrame.D1` nativement (`NATIVE_INTERVALS` ne contient que `H1`) — le D1 n'est JAMAIS fetché/persisté "
                    + "directement, il est systématiquement dérivé par resampling H1→D1 via `Bucket` (même patron que "
                    + "`RainbowDcaBacktestService`). Ce n'est pas un bug de `CachingMarketDataApiClient` (qui délègue fidèlement), "
                    + "mais une limite architecturale de fond : `cachingBinanceMarketDataApiClient.getCandles(symbol, D1, ...)` "
                    + "échoue dès que le cache D1 n'est pas déjà intégralement couvert (ce qui est TOUJOURS le cas aujourd'hui, "
                    + "aucune ligne D1 n'ayant jamais été persistée). Signalé ici tel que demandé par le prompt (\"si un bug "
                    + "bloquant est trouvé... le signaler dans le rapport, ne pas corriger silencieusement\") — pas de fix appliqué.\n\n");
            deviations.add("D1 n'est jamais fetché nativement par un MarketDataApiClient réel de ce projet — mesuré par "
                    + "resampling H1→D1 (Bucket) plutôt que par un appel getCandles(..., D1, ...) direct (qui échoue systématiquement).");

            // ---- §2/§3 H1 : appel unique (itération 1) puis rejeu jusqu'à convergence ----
            report.append("## 3. Backfill H1 via le cache-aside (§2 appel unique = itération 1, puis rejeu jusqu'à stabilité §3)\n\n");
            report.append("| Itération | Bougies visibles (résultat) | Appels réseau | Mismatch WARN | Durée (ms) |\n");
            report.append("|---|---|---|---|---|\n");

            long previousResultSize = -1;
            int iterationsRun = 0;
            boolean converged = false;
            boolean timedOut = false;
            long lastIterationNetworkCalls = -1;
            long secondToLastIterationNetworkCalls = -1;

            for (int i = 1; i <= MAX_ITERATIONS; i++) {
                if (Duration.between(runStarted, clock.now()).compareTo(MAX_TOTAL_DURATION) > 0) {
                    timedOut = true;
                    break;
                }
                Instant now = clock.now();
                int beforeIter = appender.list.size();
                long startNanos = System.nanoTime();
                List<MarketData> result;
                try {
                    result = cachingBinanceClient.getCandles(providerSymbol, TimeFrame.H1, FETCH_SINCE, now, 0);
                } catch (RuntimeException e) {
                    report.append("| ").append(i).append(" | ERREUR : ").append(e.getClass().getSimpleName())
                            .append(" : ").append(e.getMessage()).append(" | - | - | - |\n");
                    deviations.add("Itération " + i + " du backfill H1 a levé " + e.getClass().getSimpleName()
                            + " (" + e.getMessage() + ") — boucle interrompue, résultat partiel documenté.");
                    break;
                }
                long iterMs = (System.nanoTime() - startNanos) / 1_000_000;
                long networkCalls = countMatching(appender.list, beforeIter, "Appel réseau");
                long mismatchWarns = countMatching(appender.list, beforeIter, "Mismatch bougies attendues/reçues");

                report.append("| ").append(i).append(" | ").append(result.size()).append(" | ")
                        .append(networkCalls).append(" | ").append(mismatchWarns).append(" | ").append(iterMs).append(" |\n");
                iterationsRun = i;
                secondToLastIterationNetworkCalls = lastIterationNetworkCalls;
                lastIterationNetworkCalls = networkCalls;

                if (result.size() == previousResultSize && i > 1) {
                    converged = true;
                    break;
                }
                previousResultSize = result.size();
            }
            report.append("\n");

            if (timedOut) {
                report.append("**Boucle interrompue par le garde-fou temps total (").append(MAX_TOTAL_DURATION.toMinutes())
                        .append(" min) après ").append(iterationsRun).append(" itération(s)** — résultat partiel ci-dessous.\n\n");
                deviations.add("Garde-fou temps total (" + MAX_TOTAL_DURATION.toMinutes() + " min) atteint après "
                        + iterationsRun + " itération(s) — backfill H1 potentiellement incomplet.");
            } else if (!converged && iterationsRun == MAX_ITERATIONS) {
                report.append("**Garde-fou MAX_ITERATIONS (").append(MAX_ITERATIONS)
                        .append(") atteint sans convergence détectée** — résultat partiel ci-dessous.\n\n");
                deviations.add("Garde-fou MAX_ITERATIONS (" + MAX_ITERATIONS + ") atteint sans convergence — backfill H1 potentiellement incomplet.");
            } else if (converged) {
                report.append("**Convergence atteinte après ").append(iterationsRun).append(" itération(s)** "
                        + "(la dernière itération n'a plus persisté aucune bougie close nouvelle).\n\n");
            }

            // ---- §3 : vérification explicite de la stabilité (dernières 2 itérations) ----
            report.append("## 4. Vérification de la stabilité (§3 du prompt)\n\n");
            report.append("- Appels réseau de l'avant-dernière itération jouée : ").append(secondToLastIterationNetworkCalls).append("\n");
            report.append("- Appels réseau de la dernière itération jouée : ").append(lastIterationNetworkCalls).append("\n");
            report.append("- Interprétation attendue une fois convergé : la dernière itération ne doit plus déclencher qu'UN SEUL "
                    + "appel réseau — celui de la dernière bougie H1 encore ouverte (\"bougie en cours\"), volontairement exclue de la "
                    + "mémoïsation des trous par `CachingMarketDataApiClient` (cf. sa javadoc) car jamais persistée en cache — "
                    + "PAS zéro appel. Un rejeu qui redéclenche massivement du réseau au-delà de ce cas signalerait un vrai bug "
                    + "de stabilité du cache-aside (non observé ici si le tableau ci-dessus se stabilise à 1).\n\n");

            // ---- État de la base APRÈS le backfill ----
            report.append("## 5. État de la base après le backfill H1\n\n");
            Instant afterFetch = clock.now();
            DepthSnapshot h1After = snapshot(providerSymbol, TimeFrame.H1, FETCH_SINCE, afterFetch);
            appendSnapshotSection(report, "H1 (fenêtre complète, cible D1 incluse : depuis 2017-08-17)", h1After, FETCH_SINCE, afterFetch);
            DepthSnapshot h1TargetWindow = snapshot(providerSymbol, TimeFrame.H1, H1_TARGET_WINDOW_START, afterFetch);
            appendSnapshotSection(report, "H1 (fenêtre cible actée pour la calibration : depuis 2020-01-01)", h1TargetWindow, H1_TARGET_WINDOW_START, afterFetch);

            // ---- §6 : profondeur D1 dérivée par resampling H1→D1 (Bucket), seule voie possible (cf. §2) ----
            report.append("## 6. Profondeur D1 dérivée par resampling H1→D1 (`Bucket`, seule voie possible — cf. finding section 2)\n\n");
            List<CandleEntity> h1Full = candleRepository.findBySourceAndPairAndTimeFrameAndTimestampBetweenOrderByTimestampAsc(
                    SOURCE, providerSymbol, TimeFrame.H1, FETCH_SINCE, afterFetch);
            if (h1Full.isEmpty()) {
                report.append("Aucune bougie H1 persistée sur la fenêtre — resampling D1 impossible.\n\n");
            } else {
                Bucket bucket = new Bucket(TimeFrame.H1, h1Full.size() + 100);
                for (CandleEntity entity : h1Full) {
                    bucket.append(toMarketData(entity));
                }
                BucketView d1View = bucket.view(TimeFrame.D1, afterFetch);
                List<MarketData> d1Data = d1View.data();
                report.append("- Bougies D1 dérivées : ").append(d1Data.size()).append("\n");
                if (!d1Data.isEmpty()) {
                    report.append("- Première : ").append(d1Data.getFirst().getTimestamp())
                            .append(" — Dernière : ").append(d1Data.getLast().getTimestamp()).append("\n");
                    long calendarDays = Duration.between(d1Data.getFirst().getTimestamp(), d1Data.getLast().getTimestamp()).toDays();
                    report.append("- Étendue calendaire : ~").append(calendarDays).append(" jours (~")
                            .append(String.format("%.1f", calendarDays / 365.25)).append(" ans)\n");
                }
                report.append("- Complétude de la dernière bougie D1 (`CompletenessLevel`) : ").append(d1View.completeness()).append("\n\n");
            }

            // ---- Écarts ----
            report.append("## 7. Écarts pris par rapport au prompt d'origine\n\n");
            for (String deviation : deviations) {
                report.append("- ").append(deviation).append("\n");
            }
            report.append("\n");

            // ---- Verdict ----
            report.append("## 8. Verdict GO/NO-GO\n\n");
            appendVerdict(report, h1After, h1TargetWindow, converged, timedOut, iterationsRun);

        } finally {
            cachingLogger.detachAppender(appender);
            cachingLogger.setLevel(previousLevel);
        }

        String reportText = report.toString();
        log.info("\n{}", reportText);

        Path outputDir = Path.of("target", "trend-unifie-etape5");
        Files.createDirectories(outputDir);
        String fileName = "rapport-historique-d1-h1-btcusdt-" + runStarted.toString().replace(":", "-") + ".md";
        Path outputFile = outputDir.resolve(fileName);
        Files.writeString(outputFile, reportText, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Rapport écrit dans {}", outputFile.toAbsolutePath());
    }

    private void appendVerdict(StringBuilder report, DepthSnapshot h1Full, DepthSnapshot h1TargetWindow,
                                boolean converged, boolean timedOut, int iterationsRun) {
        boolean h1TargetWindowClean = h1TargetWindow.gaps().isEmpty();
        boolean fullyConverged = converged && !timedOut;

        if (fullyConverged && h1TargetWindowClean) {
            report.append("**GO** — le backfill H1 a convergé en ").append(iterationsRun)
                    .append(" itération(s) et la fenêtre cible actée pour la calibration (depuis 2020-01-01) ne présente plus "
                            + "de trou après ce run. Historique fiable disponible pour la calibration walk-forward (Étape 2 / "
                            + "Étape 6 / `RegressiveTrendStrategy`).\n\n");
        } else if (fullyConverged) {
            report.append("**NO-GO en l'état, mais trivialement corrigible** — le backfill a convergé (mécanisme validé : "
                    + "le cache-aside comble bien un trou pluriannuel par appels répétés du même since/until, sans divergence "
                    + "ni doublon), mais il subsiste des trous résiduels dans la fenêtre cible listés en section 5 ci-dessus. "
                    + "Option concrète : relancer ce runner (il est idempotent) pour finir de les combler, ou réduire la fenêtre "
                    + "de calibration à la sous-plage effectivement continue si ces trous s'avèrent non résolubles côté provider.\n\n");
        } else {
            report.append("**NO-GO** — le backfill n'a pas convergé dans les garde-fous de ce run (")
                    .append(iterationsRun).append(" itération(s), timeout=").append(timedOut)
                    .append("). Options concrètes : relancer ce runner (idempotent, reprend où il s'est arrêté grâce au cache "
                            + "déjà persisté) avec un garde-fou plus large, ou réduire la fenêtre de calibration visée à ce qui "
                            + "est effectivement disponible (cf. section 5).\n\n");
        }
    }

    /** Résout le symbole nu vers l'appellation Binance (même patron que {@code RainbowDcaBacktestService}). */
    private String resolveProviderSymbol() {
        return assetProviderRepository.findByAsset_SymbolAndSource(SYMBOL, SOURCE)
                .map(AssetProvider::getProviderSymbol)
                .orElse(SYMBOL);
    }

    private DepthSnapshot snapshot(String providerSymbol, TimeFrame timeFrame, Instant since, Instant until) {
        List<CandleEntity> cached = candleRepository
                .findBySourceAndPairAndTimeFrameAndTimestampBetweenOrderByTimestampAsc(SOURCE, providerSymbol, timeFrame, since, until);
        if (cached.isEmpty()) {
            return new DepthSnapshot(0, null, null, List.of());
        }
        Instant sinceGrid = CachingMarketDataApiClient.floorToGrid(since, timeFrame);
        Instant untilGrid = CachingMarketDataApiClient.floorToGrid(until, timeFrame);
        List<MarketData> asMarketData = cached.stream()
                .map(HistoricalDataDepthManualRunnerTest::toMarketData)
                .toList();
        List<CachingMarketDataApiClient.Range> gaps = CachingMarketDataApiClient.findGaps(asMarketData, sinceGrid, untilGrid, timeFrame);
        return new DepthSnapshot(cached.size(), cached.getFirst().getTimestamp(), cached.getLast().getTimestamp(), gaps);
    }

    private void appendSnapshotSection(StringBuilder report, String label, DepthSnapshot snapshot, Instant since, Instant until) {
        report.append("### ").append(label).append(" [").append(since).append(" .. ").append(until).append("]\n\n");
        report.append("- Bougies persistées dans la fenêtre : ").append(snapshot.count()).append("\n");
        if (snapshot.count() > 0) {
            report.append("- Première : ").append(snapshot.min()).append(" — Dernière : ").append(snapshot.max()).append("\n");
        }
        report.append("- Trous détectés : ").append(snapshot.gaps().size()).append("\n");
        if (!snapshot.gaps().isEmpty()) {
            long totalMissingHours = 0;
            int shown = 0;
            for (CachingMarketDataApiClient.Range gap : snapshot.gaps()) {
                long hours = Duration.between(gap.since(), gap.until()).toHours() + 1;
                totalMissingHours += hours;
                if (shown < 20) {
                    report.append("  - ").append(gap.since()).append(" .. ").append(gap.until())
                            .append(" (~").append(hours).append(" pas manquant(s))\n");
                    shown++;
                }
            }
            if (snapshot.gaps().size() > shown) {
                report.append("  - ... et ").append(snapshot.gaps().size() - shown).append(" trou(s) supplémentaire(s)\n");
            }
            report.append("- Total approximatif de pas manquants (toutes granularités confondues, cf. TimeFrame) : ")
                    .append(totalMissingHours).append("\n");
        }
        report.append("\n");
    }

    private static long countMatching(List<ILoggingEvent> events, int fromIndexInclusive, String prefix) {
        long count = 0;
        for (int i = fromIndexInclusive; i < events.size(); i++) {
            String msg = events.get(i).getFormattedMessage();
            if (msg != null && msg.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static MarketData toMarketData(CandleEntity entity) {
        return MarketData.builder()
                .pair(entity.getPair())
                .timeFrame(entity.getTimeFrame())
                .timestamp(entity.getTimestamp())
                .open(entity.getOpen())
                .high(entity.getHigh())
                .low(entity.getLow())
                .close(entity.getClose())
                .volume(entity.getVolume())
                .build();
    }

    private record DepthSnapshot(long count, Instant min, Instant max, List<CachingMarketDataApiClient.Range> gaps) {
    }
}
