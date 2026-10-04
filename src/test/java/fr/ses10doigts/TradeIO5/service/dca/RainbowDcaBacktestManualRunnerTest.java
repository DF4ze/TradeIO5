package fr.ses10doigts.tradeIO5.service.dca;

import fr.ses10doigts.tradeIO5.model.dto.dca.DcaResult;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.extern.java.Log;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Runner manuel (réseau + DB réels) pour explorer le paramétrage du DCA Rainbow sur BTC — cf.
 * docs/prompts/prompt-implementation-dca-rainbow-v0.md, section "Comment lancer le backtest" : pas
 * de tool MCP ni d'endpoint REST dans ce lot (coût d'itération d'un tool MCP = un appel LLM par
 * essai). Désactivé par défaut ({@link Disabled}), même convention que {@code YoutubeManualNetworkTest}.
 * <p>
 * Réécriture du 2026-09-13 (cf. docs/etudes/etude-dca-tool-mcp.md §12), suite au constat du run
 * initial (§11) : aucune combinaison ne battait le DCA fixe classique. Ce constat venait d'un biais
 * de protocole, pas d'un verdict sur la stratégie — une seule fenêtre testée (BTC, 3 dernières
 * années, bull run quasi continu), sur laquelle une stratégie qui désinvestit en zone haute perd
 * mécaniquement contre un DCA qui reste engagé à 100 %. Corrections apportées ici :
 * <ol>
 *     <li>Trois fenêtres historiques FIXES (bull / bear / sideways, dates explicites plutôt que
 *     "3 dernières années glissantes") : la vraie proposition de valeur de Rainbow (vendre en haut,
 *     racheter plus bas) ne peut se voir que sur un cycle complet, et des dates fixes rendent le
 *     run reproductible d'une exécution à l'autre.</li>
 *     <li>Deux bancs d'essai séparés, BTC uniquement (demande explicite) :
 *     {@link #runBoundsCalibration_realBtcHistory()} cherche les bornes % valides (percDown2 →
 *     percUp3), {@link #runReentryMethodsComparison_realBtcHistory()} compare les méthodes de
 *     sortie des états ARMÉ ({@link ReentryMode} : trailing stop / franchissement pur / délai fixe)
 *     à bornes V0 fixées.</li>
 *     <li>Classement par ROBUSTESSE (delta de PnL% vs DCA fixe, minimum sur les 3 fenêtres) plutôt
 *     que par le seul meilleur PnL% d'une fenêtre — évite de retenir un jeu de paramètres qui n'a
 *     brillé qu'une fois par hasard sur une seule période (même défaut que la calibration
 *     rejection-zone avant son walk-forward, cf. mémoire projet). Le tableau affiche aussi le delta
 *     MAXIMUM (meilleure fenêtre) et, pour chaque fenêtre, le PnL% Rainbow ET le PnL% du DCA fixe
 *     côte à côte (pas seulement leur différence) — cf. §14 de l'étude, demande explicite de Clem
 *     après une première lecture du tableau où seul le delta de la pire fenêtre était visible.</li>
 *     <li>Max drawdown de l'exposition marché, calculé ici (pas ajouté à {@link RainbowDcaBacktestResult},
 *     qui n'en a pas besoin ailleurs) : une stratégie qui réduit l'exposition en haut de cycle peut
 *     perdre du PnL brut tout en réduisant le risque — invisible si on ne regarde que le PnL final
 *     sur un bull run.</li>
 *     <li>Export CSV (un fichier détail + un fichier agrégé par banc, sous
 *     {@code target/rainbow-dca-bench/}) en plus du résumé console — l'ancienne mise en page
 *     (largeur de colonne différente entre l'en-tête et les lignes, un seul run BTC) rendait
 *     l'analyse illisible ; {@link #printTable} calcule sa largeur de colonne sur le contenu réel.</li>
 *     <li>Exécution parallélisée (pool de threads = nb de coeurs) : chaque (jeu de paramètres,
 *     fenêtre) est un backtest indépendant (aucun état partagé mutable dans
 *     {@link RainbowDcaBacktestService#backtest}, uniquement des variables locales). Pour éviter
 *     que plusieurs threads ne déclenchent en même temps le même fetch réseau + insertion DB pour
 *     une fenêtre pas encore en cache, {@link #warmUpCache} lance UN backtest séquentiel par
 *     fenêtre avant de paralléliser — après quoi tous les threads lisent uniquement le cache DB
 *     ({@code CachingMarketDataApiClient}, déjà peuplé) pour cette plage H1.</li>
 *     <li>Bug corrigé le 2026-09-13 (cf. étude §14) : le bloc comparatif "DCA fixe" valorisait son
 *     PnL au prix BINANCE LIVE (comportement historique de {@code DcaCalculatorService.calculate},
 *     voulu pour le tool MCP {@code calculate_dca}), alors que le PnL Rainbow lui-même est valorisé
 *     au prix de clôture d'{@code endDate} de la fenêtre testée — sur une fenêtre qui ne se termine
 *     pas "aujourd'hui" (les 3 fenêtres de ce runner, toutes closes il y a des années), ça comparait
 *     un PnL borné dans le temps à un PnL gonflé par toute l'appréciation du prix depuis. Ce runner
 *     appelle désormais l'overload {@code calculate(..., valuationInstant)} avec la borne haute de
 *     la fenêtre, pour valoriser les deux côtés au même instant.</li>
 *     <li>Plus-value réalisée vs potentielle (2026-09-13, cf. étude §15, demande explicite de
 *     Clem) : le DCA fixe ne vend jamais rien, mais Rainbow sécurise une partie des fonds en
 *     vendant en zone haute — {@code pnlPercent} seul mélange gain déjà encaissé et gain encore en
 *     position (pas garanti tant que non vendu). Le tableau agrégé/CSV affiche désormais, en plus,
 *     le total des ventes ({@code totalSaleProceeds}), la plus-value réalisée en % de l'investi
 *     ({@code realizedGainPercent}) et la plus-value potentielle en % de l'investi
 *     ({@code potentialGainPercent}) — calculées dans {@link RainbowDcaBacktestService} via un
 *     coût de revient à coût moyen pondéré (WAC).</li>
 *     <li>Croisement bornes x reentry (2026-09-13, cf. étude §16, demande explicite de Clem) :
 *     {@link #runReentryWithBestBoundsPerTrend_realBtcHistory()} croise les deux bancs ci-dessus au
 *     lieu de les garder indépendants — {@link #findBestBoundsPerWindow()} cherche d'abord, pour
 *     CHAQUE fenêtre (donc chaque tendance bull/bear/sideways) indépendamment, les bornes % qui
 *     maximisent le delta de PnL% vs le DCA fixe SUR CETTE fenêtre (pas de robustesse cross-fenêtre
 *     à cette étape : chaque tendance a ses propres bornes optimales, contrairement au banc
 *     {@link #runBoundsCalibration_realBtcHistory()} qui cherche un seul jeu robuste sur les 3).
 *     Puis la grille de méthodes de reentry (identique à
 *     {@link #runReentryMethodsComparison_realBtcHistory()}) est rejouée par fenêtre avec CES
 *     bornes-là fixées (au lieu des bornes V0 par défaut), et les résultats sont regroupés par
 *     description de reentry à travers les 3 fenêtres comme d'habitude — ce qui répond à la
 *     question "une fois qu'on a pris les meilleures bornes par tendance, quelle méthode de reentry
 *     est la plus robuste dessus ?". Extraction de {@link #boundsGrid()} (grille de bornes
 *     partagée par ce nouveau banc et par {@link #runBoundsCalibration_realBtcHistory()}, sans
 *     changement de valeurs) et de {@link ScenarioSpec}/{@link #runScenariosAndReport} (moteur
 *     d'exécution+agrégation+reporting commun aux 3 bancs, {@link #runAcrossWindowsAndReport} n'en
 *     est plus qu'une fine couche de construction des scénarios pour le cas "mêmes bornes sur
 *     toutes les fenêtres").</li>
 *     <li>Rapports par tendance (2026-09-13, cf. étude §17, demande explicite de Clem) :
 *     {@link #writePerWindowReports} produit, pour CHACUNE des 3 fenêtres, un CSV et un résumé
 *     console dédiés (triés par delta de PnL% vs DCA fixe décroissant SUR CETTE fenêtre) — la vue
 *     agrégée existante moyenne {@code realizedGainPercent}/{@code potentialGainPercent} sur les 3
 *     fenêtres, ce qui masque la décomposition réalisé/potentiel propre à chaque tendance (un bear
 *     déjà terminé peut avoir presque tout vendu, un bull en cours presque rien). Appelé pour les 3
 *     bancs (aucun changement de comportement de la vue agrégée/robustesse existante, ce rapport
 *     s'ajoute).</li>
 *     <li>Valeur absolue + contexte de variation du prix (2026-09-13, cf. étude §18/§19, demande
 *     explicite de Clem) : {@link ScenarioRow} gagne {@code fixedTotalInvested}/{@code fixedPnl}
 *     (lus sur {@code DcaResult}, déjà calculés par {@link RainbowDcaBacktestService}, jusqu'ici
 *     jetés après extraction du seul {@code fixedPnlPercent}) — les rapports par tendance
 *     affichent maintenant {@code totalInvested} des deux côtés et le gain en valeur absolue à
 *     côté des %, pour ne pas laisser un % plus faible sur un investissement plus gros masquer un
 *     gain réel plus élevé (Rainbow investit structurellement moins que le DCA fixe, cf. §12).
 *     Chaque rapport par fenêtre affiche aussi, dans son en-tête, la variation du prix BTC
 *     début→fin ({@link #computePriceChangePercent}) ET l'amplitude min→max sur la fenêtre
 *     ({@link #computePriceAmplitudePercent}) : la première seule peut suggérer un marché plat
 *     alors qu'il a chuté puis rebondi violemment sur la même fenêtre — un DCA périodique qui
 *     achète pendant le creux peut alors afficher un PnL supérieur à ce que suggère la seule
 *     variation début/fin, sans que ce soit un bug de calcul.</li>
 *     <li>Rapport équilibre réalisé/potentiel (2026-09-13, cf. étude §20, demande explicite de
 *     Clem) : le classement par robustesse ({@link #printLeaderboard}) et le rapport par tendance
 *     trié par delta ({@link #printWindowLeaderboard}) favorisent tous deux mécaniquement les jeux
 *     de paramètres qui vendent peu — tout le gain reste {@code potentialGain} (non sécurisé, pas
 *     garanti tant que non vendu), ce qui gonfle artificiellement la performance affichée sans
 *     rien avoir encaissé. {@link #computeBalanceGapPercent} calcule l'écart absolu entre
 *     {@code realizedGainPercent} et {@code potentialGainPercent} (0 = parfait équilibre) ;
 *     {@link #writeBalancedGainCsv}/{@link #printBalancedGainLeaderboard}, appelés depuis
 *     {@link #writePerWindowReports} pour les 3 bancs comme les rapports existants, trient les
 *     scénarios déjà calculés par cet écart croissant au lieu du delta vs DCA fixe — un classement
 *     complémentaire (pas un remplacement) pour repérer les jeux de paramètres qui sécurisent une
 *     partie du gain au fil de l'eau sans sacrifier excessivement la performance totale (affichée à
 *     côté pour arbitrer le compromis soi-même).</li>
 *     <li>Boucle coordinate ascent bornes {@literal <->} reentry par tendance (2026-09-13, cf.
 *     étude §21, demande explicite de Clem) : {@link #runReentryWithBestBoundsPerTrend_realBtcHistory()}
 *     (§16) ne fait qu'UN aller (bornes optimales à reentry V0 fixé, puis reentry optimal sur ces
 *     bornes) — un choix arbitraire de point de départ, puisque de meilleures bornes changent la
 *     technique de reentry optimale, qui change à son tour les bornes optimales, etc.
 *     {@link #runCoordinateAscentPerTrend_realBtcHistory()} boucle, indépendamment pour chacune des
 *     3 fenêtres : reentry_0 = défauts V0 ({@link #defaultReentryCandidate()}), puis en alternance
 *     bornes_i = meilleures bornes sachant reentry_(i-1) ({@link #findBestBoundsForWindow}),
 *     reentry_i = meilleur reentry sachant bornes_i ({@link #findBestReentryForWindow}), jusqu'à ce
 *     que (bornes, reentry) se stabilisent d'une itération à l'autre ou jusqu'à
 *     {@link #COORDINATE_ASCENT_MAX_ITERATIONS} itérations (garde-fou anti-oscillation). Chaque
 *     itération est exportée en CSV de traçabilité, plus un CSV final (un paramétrage bornes+reentry
 *     stabilisé par tendance).</li>
 *     <li>SMA comme 3e dimension du coordinate ascent (2026-09-17, cf. étude §23, demande
 *     explicite de Clem : {@code smaPeriod} était resté fixé à 20 par défaut, jamais remis en
 *     question) : {@link #runCoordinateAscentForWindow} boucle désormais bornes
 *     {@literal <->} reentry {@literal <->} sma — {@link #findBestSmaForWindow} cherche la
 *     meilleure période de SMA sur {@link #smaGrid()} à bornes+reentry fixés, à la suite des deux
 *     autres recherches de la même itération (donc sur les valeurs déjà mises à jour cette
 *     itération-là). S'applique aux deux boucles existantes
 *     ({@link #runCoordinateAscentPerTrend_realBtcHistory()} et
 *     {@link #runCoordinateAscentBalancedPerTrend_realBtcHistory()}), pas aux bancs statiques
 *     §12/§16 qui gardent {@code smaPeriod} au défaut V0. Comme {@code smaPeriod} change la plage
 *     de warm-up ({@code startDate - smaPeriod} jours), {@link #warmUpCacheForSmaGrid()} remplace
 *     {@link #warmUpCache} en tête de ces deux tests — pré-chauffe avec la plus GRANDE période de
 *     {@link #smaGrid()} uniquement (sa plage englobe celle de toute SMA plus courte, donc un seul
 *     appel suffit à mettre en cache DB tout ce dont les valeurs plus courtes auront besoin).</li>
 * </ol>
 * Volontairement pas fait ici (cf. docs/CODING_RULES.md — "ne pas coder ce dont on n'a pas
 * besoin") : rendement pondéré dans le temps (XIRR) pour comparer des calendriers de cash-flows
 * différents entre Rainbow et le DCA fixe, multi-actif, split in-sample/out-of-sample formel.
 * <p>
 * {@link MemoizingDcaCalculatorServiceConfig} : mémoïse {@code DcaCalculatorService.calculate(...)}
 * par jeu d'arguments exact, pour ne payer le calcul qu'une seule fois par fenêtre/montant sur tout
 * le run, au lieu d'une fois par scénario. Depuis le fix ci-dessus, l'appel réseau "prix de
 * valorisation" est lui-même déjà borné à {@code endDate} (donc lui aussi mis en cache par
 * {@code CachingMarketDataApiClient}/sa mémoïsation de trous H1 — cf. étude §13) : cette couche de
 * mémoïsation supplémentaire évite surtout de refaire le calcul (boucle sur le calendrier
 * d'échéances) à chaque scénario. Le cache interne est un {@code ConcurrentHashMap} : thread-safe
 * pour l'exécution parallélisée ci-dessus. DcaCalculatorService.java lui-même n'est modifié que
 * pour ajouter l'overload {@code valuationInstant} (rétrocompatible, cf. étude §14).
 */
@Disabled("Runner manuel réseau+DB réel — commenter cette annotation pour lancer les backtests BTC réels en local")
@SpringBootTest(properties = "logging.level.fr.ses10doigts.tradeIO5=INFO")
@DisplayName("RainbowDcaBacktestService - runners manuels BTC (réseau réel)")
@Log 
class RainbowDcaBacktestManualRunnerTest {

    @Autowired
    private RainbowDcaBacktestService rainbowDcaBacktestService;

    private static final BigDecimal BASE_AMOUNT = BigDecimal.valueOf(100);
    private static final String SYMBOL = "BTC";
    private static final Path OUTPUT_DIR = Path.of("target", "rainbow-dca-bench");
    private static final int THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());
    /** Garde-fou anti-oscillation/anti-boucle infinie pour {@link #runCoordinateAscentForWindow} (cf. étude §21). */
    private static final int COORDINATE_ASCENT_MAX_ITERATIONS = 6;

    /**
     * Fenêtres historiques fixes (pas de "3 dernières années glissantes") pour que le run soit
     * reproductible et couvre les 3 régimes de marché où la mécanique Rainbow peut réellement
     * s'exprimer : un bull soutenu, un bear complet (top -> bottom), une phase sideways.
     */
    private record Window(String label, LocalDate startDate, LocalDate endDate) {
    }

    private static final List<Window> WINDOWS = List.of(
            new Window("BULL_2023_2024", LocalDate.of(2023, 1, 1), LocalDate.of(2024, 12, 31)),
            new Window("BEAR_2021_2022", LocalDate.of(2021, 11, 10), LocalDate.of(2022, 12, 31)),
            //new Window("SIDEWAYS_2018_2019", LocalDate.of(2018, 7, 1), LocalDate.of(2019, 6, 30))
            new Window("SIDEWAYS_2018_2019", LocalDate.of(2022, 6, 20), LocalDate.of(2023, 3, 8))
    );

    /** Un jeu de bornes % candidat de la grille de calibration (cf. {@link #boundsGrid()}). */
    private record BoundsCandidate(
            BigDecimal down2, BigDecimal down1, BigDecimal up1, BigDecimal up2, BigDecimal up3, String description
    ) {
    }

    /** Un jeu de reentry candidat de la grille (cf. {@link #reentryGrid()}), pour la boucle coordinate ascent (étude §21). */
    private record ReentryCandidate(
            ReentryMode buyMode, ReentryMode sellMode, BigDecimal trailingStopPercent,
            int cooldownDays, int fixedDelayDays, BigDecimal sellFraction, String description
    ) {
    }

    /** Une période de SMA candidate de la grille (cf. {@link #smaGrid()}), 3e dimension du coordinate ascent (étude §23). */
    private record SmaCandidate(int period, String description) {
    }

    @Test
    @DisplayName("Bornes % valides pour BTC : grille sur percDown2/percDown1/percUp1/percUp2/percUp3, classée par robustesse sur 3 fenêtres")
    void runBoundsCalibration_realBtcHistory() throws IOException {
        List<RainbowDcaBacktestRequest.RainbowDcaBacktestRequestBuilder> builders = new ArrayList<>();
        for (BoundsCandidate c : boundsGrid()) {
            builders.add(RainbowDcaBacktestRequest.builder()
                    .symbol(SYMBOL).baseAmount(BASE_AMOUNT)
                    .percDown2(c.down2()).percDown1(c.down1()).percUp1(c.up1()).percUp2(c.up2()).percUp3(c.up3()));
        }

        runAcrossWindowsAndReport("bounds-calibration", builders, this::describeBounds);
    }

    @Test
    @DisplayName("Méthodes de sortie ARMÉ (trailing stop / franchissement pur / délai fixe) : bornes V0 fixées, classé par robustesse sur 3 fenêtres")
    void runReentryMethodsComparison_realBtcHistory() throws IOException {
        List<ReentryMode> l_buyMode = List.of(ReentryMode.TRAILING_STOP, ReentryMode.IMMEDIATE, ReentryMode.FIXED_DELAY);
        List<ReentryMode> l_sellMode = List.of(ReentryMode.TRAILING_STOP, ReentryMode.IMMEDIATE, ReentryMode.FIXED_DELAY);
        List<BigDecimal> l_trailingStopPercent = List.of(bd(3), bd(5), bd(7));
        List<Integer> l_cooldown = List.of(3, 7);
        List<Integer> l_fixedDelayDays = List.of(5, 10, 15);
        List<BigDecimal> l_sellFraction = List.of(new BigDecimal("0.25"), new BigDecimal("0.5"));

        List<RainbowDcaBacktestRequest.RainbowDcaBacktestRequestBuilder> builders = new ArrayList<>();
        for (ReentryMode buyMode : l_buyMode) {
            for (ReentryMode sellMode : l_sellMode) {
                for (BigDecimal ts : l_trailingStopPercent) {
                    for (int cd : l_cooldown) {
                        for (int fd : l_fixedDelayDays) {
                            for (BigDecimal sf : l_sellFraction) {
                                builders.add(RainbowDcaBacktestRequest.builder()
                                        .symbol(SYMBOL).baseAmount(BASE_AMOUNT)
                                        .buyReentryMode(buyMode).sellReentryMode(sellMode)
                                        .trailingStopPercent(ts).cooldownDays(cd)
                                        .fixedDelayDays(fd).sellFraction(sf));
                            }
                        }
                    }
                }
            }
        }

        runAcrossWindowsAndReport("reentry-methods", builders, this::describeReentry);
    }

    /**
     * Croise les deux bancs ci-dessus : cherche d'abord, indépendamment pour chacune des 3
     * fenêtres (donc pour chaque tendance bull/bear/sideways), le jeu de bornes % qui maximise le
     * delta de PnL% vs le DCA fixe SUR CETTE fenêtre (cf. {@link #findBestBoundsPerWindow()} —
     * volontairement pas de robustesse cross-fenêtre à cette étape, chaque tendance a ses propres
     * bornes optimales). Rejoue ensuite la même grille de méthodes de reentry que
     * {@link #runReentryMethodsComparison_realBtcHistory()}, mais avec CES bornes par-fenêtre
     * fixées au lieu des bornes V0 par défaut, pour répondre à : une fois les meilleures bornes
     * retenues par tendance, quelle méthode de sortie ARMÉ (achat/vente) est la plus robuste
     * dessus à travers les 3 régimes ?
     */
    @Test
    @DisplayName("Croisement bornes x reentry : meilleures bornes % par tendance, puis méthodes de reentry sur ces bornes")
    void runReentryWithBestBoundsPerTrend_realBtcHistory() throws IOException {
        Map<Window, BestBoundsForWindow> bestBoundsPerWindow = findBestBoundsPerWindow();
        writeBestBoundsPerWindowCsv(bestBoundsPerWindow);

        List<ReentryMode> l_buyMode = List.of(ReentryMode.TRAILING_STOP, ReentryMode.IMMEDIATE, ReentryMode.FIXED_DELAY);
        List<ReentryMode> l_sellMode = List.of(ReentryMode.TRAILING_STOP, ReentryMode.IMMEDIATE, ReentryMode.FIXED_DELAY);
        List<BigDecimal> l_trailingStopPercent = List.of(bd(3), bd(5), bd(7));
        List<Integer> l_cooldown = List.of(1, 3, 7, 12);
        List<Integer> l_fixedDelayDays = List.of(3, 5, 10, 15);
        List<BigDecimal> l_sellFraction = List.of(new BigDecimal("0.10"), new BigDecimal("0.25"), new BigDecimal("0.33"), new BigDecimal("0.5"), new BigDecimal("0.75"));

        List<ScenarioSpec> specs = new ArrayList<>();
        for (Window window : WINDOWS) {
            BoundsCandidate bounds = bestBoundsPerWindow.get(window).bounds();
            for (ReentryMode buyMode : l_buyMode) {
                for (ReentryMode sellMode : l_sellMode) {
                    for (BigDecimal ts : l_trailingStopPercent) {
                        for (int cd : l_cooldown) {
                            for (int fd : l_fixedDelayDays) {
                                for (BigDecimal sf : l_sellFraction) {
                                    RainbowDcaBacktestRequest request = RainbowDcaBacktestRequest.builder()
                                            .symbol(SYMBOL).baseAmount(BASE_AMOUNT)
                                            .percDown2(bounds.down2()).percDown1(bounds.down1()).percUp1(bounds.up1())
                                            .percUp2(bounds.up2()).percUp3(bounds.up3())
                                            .buyReentryMode(buyMode).sellReentryMode(sellMode)
                                            .trailingStopPercent(ts).cooldownDays(cd)
                                            .fixedDelayDays(fd).sellFraction(sf)
                                            .startDate(window.startDate()).endDate(window.endDate())
                                            .build();
                                    specs.add(new ScenarioSpec(request, describeReentry(request), window));
                                }
                            }
                        }
                    }
                }
            }
        }

        // Cache H1 déjà chaud pour les 3 fenêtres (peuplé par findBestBoundsPerWindow ci-dessus,
        // la plage H1 fetchée ne dépend que de startDate/endDate/smaPeriod, pas des bornes %) :
        // pas de warmUpCache supplémentaire ici.
        Map<String, String> boundsPerWindowLabel = new LinkedHashMap<>();
        for (Window window : WINDOWS) {
            boundsPerWindowLabel.put(window.label(), bestBoundsPerWindow.get(window).bounds().description());
        }
        runScenariosAndReport("bounds-per-trend-then-reentry", specs, boundsPerWindowLabel);
    }

    /**
     * Boucle coordinate ascent bornes {@literal <->} reentry, indépendamment pour chacune des 3
     * fenêtres (cf. étude §21, demande explicite de Clem) : un vrai paramétrage optimisé par
     * tendance, plutôt que l'unique aller bornes -> reentry de
     * {@link #runReentryWithBestBoundsPerTrend_realBtcHistory()}.
     */
    @Test
    @DisplayName("Coordinate ascent bornes <-> reentry par tendance : boucle jusqu'à stabilisation, paramétrage optimisé par Trend")
    void runCoordinateAscentPerTrend_realBtcHistory() throws IOException {
        warmUpCacheForSmaGrid();

        // Phase 1 (la moulinette) : on calcule TOUT — les 3 fenêtres — avant tout affichage de
        // résultat final. Seuls les logs de progression par itération de runCoordinateAscentForWindow
        // sortent pendant cette phase (ce sont des logs de calcul/traçabilité, pas le résultat final :
        // cf. demande de Clem de ne pas mélanger calcul et affichage des résultats).
        Map<Window, List<CoordinateAscentIteration>> tracePerWindow = new LinkedHashMap<>();
        Map<Window, CoordinateAscentIteration> finalPerWindow = new LinkedHashMap<>();
        for (Window window : WINDOWS) {
            AnchoredCoordinateAscentResult anchored = runCoordinateAscentWithSmaAnchor(
                    window, ScenarioRow::deltaVsFixed, true, "delta vs DCA fixe");
            tracePerWindow.put(window, anchored.retainedTrace());
            finalPerWindow.put(window, anchored.retainedTrace().getLast());
        }

        // Phase 2 : exports CSV, une fois tout calculé.
        for (Window window : WINDOWS) {
            writeCoordinateAscentTraceCsv("coordinate-ascent", window, tracePerWindow.get(window));
        }
        writeCoordinateAscentFinalCsv("coordinate-ascent", finalPerWindow);

        // Phase 3 : affichage des résultats, uniquement une fois la moulinette terminée pour les 3
        // fenêtres — même format de ligne que précédemment (une ligne par fenêtre), précédé d'un
        // entête encadré qui met en évidence le meilleur paramétrage stabilisé par tendance.
        log.info("=====================================================================================");
        log.info("===== COORDINATE ASCENT : MEILLEUR PARAMÉTRAGE STABILISÉ PAR TENDANCE (bornes <-> reentry) =====");
        log.info("=====================================================================================");
        for (Window window : WINDOWS) {
            CoordinateAscentIteration last = finalPerWindow.get(window);
            log.info(String.format(
                    "%s : coordinate ascent terminé après %d itération(s) (%s) -> bornes=%s reentry=%s sma=%s "
                            + "(PnL Rainbow=%s%%, PnL DCA fixe=%s%%, delta=%s%%)",
                    window.label(), last.iteration(), last.converged() ? "stabilisé" : "limite atteinte",
                    last.bounds().description(), last.reentry().description(), last.sma().description(),
                    str(last.scenarioRow().result().getPnlPercent()), str(last.scenarioRow().fixedPnlPercent()),
                    str(last.scenarioRow().deltaVsFixed())));
        }
        log.info("=====================================================================================");
    }

    /**
     * Variante du coordinate ascent ci-dessus qui optimise un objectif différent à chaque étape de
     * la boucle : au lieu de maximiser {@code deltaVsFixed} (robustesse), minimise
     * {@link #computeBalanceGapPercent} — l'écart absolu entre plus-value réalisée et potentielle
     * (cf. étude §20/§22, demande explicite de Clem : arriver à un quasi 50/50 entre gain déjà
     * encaissé et gain encore en position). Même mécanique de boucle bornes {@literal <->} reentry,
     * indépendamment pour chacune des 3 fenêtres, jusqu'à stabilisation ou
     * {@link #COORDINATE_ASCENT_MAX_ITERATIONS} — seul l'objectif optimisé à chaque étape change,
     * donc le paramétrage trouvé ici n'a aucune raison de coïncider avec celui de
     * {@link #runCoordinateAscentPerTrend_realBtcHistory()} (les deux boucles répondent à des
     * questions différentes : "le plus robuste vs DCA fixe" contre "le plus équilibré
     * réalisé/potentiel").
     */
    @Test
    @DisplayName("Coordinate ascent bornes <-> reentry par tendance, objectif équilibre réalisé/potentiel (~50/50)")
    void runCoordinateAscentBalancedPerTrend_realBtcHistory() throws IOException {
        warmUpCacheForSmaGrid();

        Map<Window, List<CoordinateAscentIteration>> tracePerWindow = new LinkedHashMap<>();
        Map<Window, CoordinateAscentIteration> finalPerWindow = new LinkedHashMap<>();
        for (Window window : WINDOWS) {
            AnchoredCoordinateAscentResult anchored = runCoordinateAscentWithSmaAnchor(
                    window, RainbowDcaBacktestManualRunnerTest::computeBalanceGapPercent, false, "écart réalisé/potentiel");
            tracePerWindow.put(window, anchored.retainedTrace());
            finalPerWindow.put(window, anchored.retainedTrace().getLast());
        }

        for (Window window : WINDOWS) {
            writeCoordinateAscentTraceCsv("coordinate-ascent-balanced", window, tracePerWindow.get(window));
        }
        writeCoordinateAscentFinalCsv("coordinate-ascent-balanced", finalPerWindow);

        log.info("=====================================================================================");
        log.info("===== COORDINATE ASCENT ÉQUILIBRÉ : MEILLEUR PARAMÉTRAGE PAR TENDANCE (écart réalisé/potentiel ~0) =====");
        log.info("=====================================================================================");
        for (Window window : WINDOWS) {
            CoordinateAscentIteration last = finalPerWindow.get(window);
            RainbowDcaBacktestResult r = last.scenarioRow().result();
            log.info(String.format(
                    "%s : coordinate ascent équilibré terminé après %d itération(s) (%s) -> bornes=%s reentry=%s sma=%s",
                    window.label(), last.iteration(), last.converged() ? "stabilisé" : "limite atteinte",
                    last.bounds().description(), last.reentry().description(), last.sma().description()));
            log.info(String.format(
                    "   Rainbow  : investi=%s (%d achats déclenchés), vendu=%s (%d ventes déclenchées), valeur position restante=%s,"
                            + " PnL=%s (%s%%) [réalisé=%s (%s%%), potentiel=%s (%s%%)], écart réalisé/potentiel=%s%%",
                    str(r.getTotalInvested()), r.getBuyTriggeredCount(), str(r.getTotalSaleProceeds()), r.getSellTriggeredCount(),
                    str(r.getCurrentValue()), str(r.getPnl()), str(r.getPnlPercent()),
                    str(r.getRealizedGain()), str(r.getRealizedGainPercent()),
                    str(r.getPotentialGain()), str(r.getPotentialGainPercent()),
                    str(computeBalanceGapPercent(last.scenarioRow()))));
            log.info(String.format(
                    "   DCA fixe : investi=%s, PnL=%s (%s%%)  |  delta Rainbow vs DCA fixe = %s%%",
                    str(last.scenarioRow().fixedTotalInvested()), str(last.scenarioRow().fixedPnl()),
                    str(last.scenarioRow().fixedPnlPercent()), str(last.scenarioRow().deltaVsFixed())));
        }
        log.info("=====================================================================================");
    }

    // ---------------------------------------------------------------------------------------
    // Moteur commun : pré-chauffe le cache H1 par fenêtre (séquentiel), lance chaque (jeu de
    // paramètres x fenêtre) en parallèle, calcule le PnL Rainbow ET le PnL du DCA fixe (valorisés
    // au même instant, cf. §14 de l'étude) + le max drawdown + la plus-value réalisée/potentielle
    // (cf. §15), classe par robustesse (pire delta sur les 3 fenêtres, meilleur delta aussi
    // affiché), exporte tout en CSV et affiche un top 15 lisible en console avec les 2 PnL côte à
    // côte pour chaque fenêtre.
    // ---------------------------------------------------------------------------------------

    private record ScenarioRow(
            String description, String window, RainbowDcaBacktestResult result,
            BigDecimal fixedPnlPercent, BigDecimal deltaVsFixed, BigDecimal maxDrawdownPercent,
            BigDecimal fixedTotalInvested, BigDecimal fixedPnl
    ) {
    }

    /**
     * PnL Rainbow et PnL du DCA fixe pour une fenêtre donnée, valorisés au même instant (endDate
     * de la fenêtre), plus la décomposition réalisé/potentiel du PnL Rainbow (cf. étude §15 — le
     * DCA fixe ne vend jamais rien, donc cette décomposition n'a de sens que côté Rainbow).
     */
    private record WindowOutcome(
            BigDecimal rainbowPnlPercent, BigDecimal fixedPnlPercent, BigDecimal delta,
            BigDecimal realizedGainPercent, BigDecimal potentialGainPercent
    ) {
    }

    private record AggregatedRow(
            String description, Map<String, WindowOutcome> byWindow,
            BigDecimal minDelta, BigDecimal maxDelta, BigDecimal avgDelta,
            BigDecimal avgRealizedGainPercent, BigDecimal avgPotentialGainPercent
    ) {
    }

    private record TaskResult(ScenarioRow row, String description, String windowLabel, String errorMessage) {
    }

    /** Une requête déjà entièrement construite (bornes + reentry + fenêtre), prête à exécuter. */
    private record ScenarioSpec(RainbowDcaBacktestRequest request, String description, Window window) {
    }

    /** Meilleures bornes % trouvées pour une fenêtre donnée, avec le résultat qui a justifié le choix. */
    private record BestBoundsForWindow(BoundsCandidate bounds, ScenarioRow scenarioRow) {
    }

    /**
     * Construit les scénarios (mêmes bornes/reentry sur les 3 fenêtres, seule startDate/endDate
     * varie) puis délègue à {@link #runScenariosAndReport} — cas d'usage des bancs
     * {@link #runBoundsCalibration_realBtcHistory()} et {@link #runReentryMethodsComparison_realBtcHistory()}.
     */
    private void runAcrossWindowsAndReport(
            String benchName,
            List<RainbowDcaBacktestRequest.RainbowDcaBacktestRequestBuilder> builders,
            Function<RainbowDcaBacktestRequest, String> describe
    ) throws IOException {
        warmUpCache(builders.getFirst());

        List<ScenarioSpec> specs = new ArrayList<>();
        for (RainbowDcaBacktestRequest.RainbowDcaBacktestRequestBuilder builder : builders) {
            for (Window window : WINDOWS) {
                RainbowDcaBacktestRequest request = builder.startDate(window.startDate()).endDate(window.endDate()).build();
                specs.add(new ScenarioSpec(request, describe.apply(request), window));
            }
        }

        runScenariosAndReport(benchName, specs);
    }

    /**
     * Moteur commun aux 3 bancs : exécute en parallèle une liste de scénarios déjà construits
     * (utile quand les paramètres varient PAR fenêtre, comme
     * {@link #runReentryWithBestBoundsPerTrend_realBtcHistory()} — sinon
     * {@link #runAcrossWindowsAndReport} suffit), regroupe par description à travers les fenêtres,
     * classe par robustesse (minimum du delta de PnL% vs DCA fixe sur les fenêtres où cette
     * description apparaît), exporte les CSV détail/agrégé, affiche le leaderboard console et
     * produit les rapports par tendance (cf. {@link #writePerWindowReports}).
     */
    private void runScenariosAndReport(String benchName, List<ScenarioSpec> specs) throws IOException {
        runScenariosAndReport(benchName, specs, Map.of());
    }

    /**
     * Variante avec {@code fixedBoundsPerWindowLabel} (demande explicite de Clem, cf. étude §22) :
     * quand un jeu de bornes % est fixé PAR FENÊTRE avant de lancer ce moteur (cas de
     * {@link #runReentryWithBestBoundsPerTrend_realBtcHistory()}, où {@code description} ne porte
     * plus que le reentry — les bornes ne varient pas d'une ligne à l'autre pour une même fenêtre),
     * on ne peut pas déduire les bornes utilisées depuis les lignes de résultat elles-mêmes. Cette
     * map (fenêtre -> description des bornes) est alors affichée une fois dans le titre du rapport
     * par fenêtre (CSV + console), pour permettre de la comparer directement au log "meilleures
     * bornes pour ..." de {@link #findBestBoundsPerWindow()} sans devoir croiser un autre CSV.
     * {@code Map.of()} (bancs {@link #runBoundsCalibration_realBtcHistory()}/
     * {@link #runReentryMethodsComparison_realBtcHistory()}) : bornes variables par ligne, rien à
     * afficher en plus.
     */
    private void runScenariosAndReport(String benchName, List<ScenarioSpec> specs,
                                        Map<String, String> fixedBoundsPerWindowLabel) throws IOException {
        log.info(String.format("%s : %d scénario(s) à exécuter (pool de %d threads)",
                benchName, specs.size(), THREAD_POOL_SIZE));

        List<Callable<TaskResult>> tasks = new ArrayList<>();
        for (ScenarioSpec spec : specs) {
            tasks.add(() -> runOneScenario(spec.request(), spec.description(), spec.window()));
        }

        List<ScenarioRow> allRows = new ArrayList<>();
        Map<String, Map<String, WindowOutcome>> resultsByDescription = new LinkedHashMap<>();
        int errorCount = 0;

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        try {
            List<Future<TaskResult>> futures = executor.invokeAll(tasks);
            for (Future<TaskResult> future : futures) {
                TaskResult taskResult = future.get();
                if (taskResult.row() != null) {
                    ScenarioRow row = taskResult.row();
                    allRows.add(row);
                    BigDecimal rainbowPnl = row.result().getPnlPercent();
                    if (rainbowPnl != null && row.fixedPnlPercent() != null) {
                        WindowOutcome outcome = new WindowOutcome(rainbowPnl, row.fixedPnlPercent(), row.deltaVsFixed(),
                                row.result().getRealizedGainPercent(), row.result().getPotentialGainPercent());
                        resultsByDescription.computeIfAbsent(taskResult.description(), d -> new LinkedHashMap<>())
                                .put(taskResult.windowLabel(), outcome);
                    }
                } else {
                    errorCount++;
                    log.warning("ERROR : " + taskResult.description() + " / " + taskResult.windowLabel() + " : " + taskResult.errorMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompu pendant l'exécution parallèle de " + benchName, e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Échec inattendu pendant l'exécution parallèle de " + benchName, e.getCause());
        } finally {
            executor.shutdown();
        }

        List<AggregatedRow> aggregated = new ArrayList<>();
        for (Map.Entry<String, Map<String, WindowOutcome>> entry : resultsByDescription.entrySet()) {
            Map<String, WindowOutcome> byWindow = entry.getValue();
            if (byWindow.size() < WINDOWS.size()) {
                continue; // exclut les jeux de paramètres qui ont échoué sur au moins une fenêtre
            }
            BigDecimal min = byWindow.values().stream().map(WindowOutcome::delta).min(BigDecimal::compareTo).orElse(null);
            BigDecimal max = byWindow.values().stream().map(WindowOutcome::delta).max(BigDecimal::compareTo).orElse(null);
            BigDecimal avg = byWindow.values().stream().map(WindowOutcome::delta)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(byWindow.size()), 4, RoundingMode.HALF_UP);
            BigDecimal avgRealized = averageOrNull(byWindow.values().stream().map(WindowOutcome::realizedGainPercent).toList());
            BigDecimal avgPotential = averageOrNull(byWindow.values().stream().map(WindowOutcome::potentialGainPercent).toList());
            aggregated.add(new AggregatedRow(entry.getKey(), byWindow, min, max, avg, avgRealized, avgPotential));
        }
        aggregated.sort(Comparator.comparing(
                (AggregatedRow row) -> row.minDelta(),
                Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder())));

        writeDetailCsv(benchName, allRows);
        writeAggregatedCsv(benchName, aggregated);
        printLeaderboard(benchName, aggregated, 15);
        writePerWindowReports(benchName, allRows, 15, fixedBoundsPerWindowLabel);

        log.info(String.format("%s : %d runs, %d erreur(s), %d jeu(x) de paramètres valides sur les %d fenêtres",
                benchName, allRows.size(), errorCount, aggregated.size(), WINDOWS.size()));
    }

    /** Moyenne d'une liste de {@code BigDecimal} pouvant contenir des {@code null} (ignorés) ; {@code null} si tous null. */
    private static BigDecimal averageOrNull(List<BigDecimal> values) {
        List<BigDecimal> nonNull = values.stream().filter(java.util.Objects::nonNull).toList();
        if (nonNull.isEmpty()) {
            return null;
        }
        return nonNull.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(nonNull.size()), 4, RoundingMode.HALF_UP);
    }

    /**
     * Grille de bornes % (percDown2 → percUp3) partagée par {@link #runBoundsCalibration_realBtcHistory()}
     * et par {@link #findBestBoundsPerWindow()} — mêmes valeurs qu'avant l'extraction, ne filtre
     * que les combinaisons dont les bornes sont bien ordonnées ({@link #isOrdered}).
     */
    private static List<BoundsCandidate> boundsGrid() {
        List<BigDecimal> l_percDown2 = List.of(bd(-13), bd(-11), bd(-9), bd(-7), bd(-5));
        List<BigDecimal> l_percDown1 = List.of(bd(-6), bd(-5), bd(-4), bd(-3), bd(-2));
        List<BigDecimal> l_percUp1 = List.of(bd(2), bd(3), bd(4), bd(6), bd(8));
        List<BigDecimal> l_percUp2 = List.of(bd(5), bd(7), bd(9), bd(11), bd(13));
        List<BigDecimal> l_percUp3 = List.of(bd(10), bd(12), bd(14), bd(16), bd(18));

        List<BoundsCandidate> grid = new ArrayList<>();
        for (BigDecimal down2 : l_percDown2) {
            for (BigDecimal down1 : l_percDown1) {
                for (BigDecimal up1 : l_percUp1) {
                    for (BigDecimal up2 : l_percUp2) {
                        for (BigDecimal up3 : l_percUp3) {
                            if (isOrdered(down2, down1, up1, up2, up3)) {
                                grid.add(new BoundsCandidate(down2, down1, up1, up2, up3,
                                        describeBoundsValues(down2, down1, up1, up2, up3)));
                            }
                        }
                    }
                }
            }
        }
        return grid;
    }

    /**
     * Pour chacune des 3 fenêtres indépendamment, lance toute la grille de bornes
     * ({@link #boundsGrid()}) et retient celle qui maximise le delta de PnL% vs le DCA fixe SUR
     * CETTE fenêtre. Volontairement pas de robustesse cross-fenêtre ici (contrairement à
     * {@link #runScenariosAndReport}) : le but est justement d'obtenir des bornes différentes par
     * tendance, pas un compromis unique — cf. {@link #runReentryWithBestBoundsPerTrend_realBtcHistory()}.
     */
    private Map<Window, BestBoundsForWindow> findBestBoundsPerWindow() {
        List<BoundsCandidate> grid = boundsGrid();
        Map<String, BoundsCandidate> byDescription = new LinkedHashMap<>();
        for (BoundsCandidate c : grid) {
            byDescription.put(c.description(), c);
        }

        warmUpCache(RainbowDcaBacktestRequest.builder().symbol(SYMBOL).baseAmount(BASE_AMOUNT));

        log.info(String.format("recherche des meilleures bornes par tendance : %d jeu(x) de bornes x %d fenêtres = %d runs (pool de %d threads)",
                grid.size(), WINDOWS.size(), grid.size() * WINDOWS.size(), THREAD_POOL_SIZE));

        List<Callable<TaskResult>> tasks = new ArrayList<>();
        for (BoundsCandidate c : grid) {
            for (Window window : WINDOWS) {
                RainbowDcaBacktestRequest request = RainbowDcaBacktestRequest.builder()
                        .symbol(SYMBOL).baseAmount(BASE_AMOUNT)
                        .percDown2(c.down2()).percDown1(c.down1()).percUp1(c.up1()).percUp2(c.up2()).percUp3(c.up3())
                        .startDate(window.startDate()).endDate(window.endDate())
                        .build();
                tasks.add(() -> runOneScenario(request, c.description(), window));
            }
        }

        Map<String, ScenarioRow> bestByWindowLabel = new LinkedHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        try {
            List<Future<TaskResult>> futures = executor.invokeAll(tasks);
            for (Future<TaskResult> future : futures) {
                TaskResult taskResult = future.get();
                ScenarioRow row = taskResult.row();
                if (row == null || row.deltaVsFixed() == null) {
                    continue;
                }
                bestByWindowLabel.merge(row.window(), row,
                        (current, candidate) -> candidate.deltaVsFixed().compareTo(current.deltaVsFixed()) > 0 ? candidate : current);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompu pendant la recherche des meilleures bornes par tendance", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Échec inattendu pendant la recherche des meilleures bornes par tendance", e.getCause());
        } finally {
            executor.shutdown();
        }

        Map<Window, BestBoundsForWindow> result = new LinkedHashMap<>();
        for (Window window : WINDOWS) {
            ScenarioRow best = bestByWindowLabel.get(window.label());
            if (best == null) {
                throw new IllegalStateException("Aucun jeu de bornes valide n'a produit de résultat exploitable pour la fenêtre " + window.label());
            }
            BoundsCandidate bounds = byDescription.get(best.description());
            result.put(window, new BestBoundsForWindow(bounds, best));
            log.info(String.format("meilleures bornes pour %s : %s (delta vs DCA fixe = %s%%, PnL Rainbow = %s%%, PnL DCA fixe = %s%%)",
                    window.label(), bounds.description(), str(best.deltaVsFixed()), str(best.result().getPnlPercent()), str(best.fixedPnlPercent())));
        }
        return result;
    }

    /** CSV de traçabilité des bornes retenues par {@link #findBestBoundsPerWindow()}, une ligne par fenêtre. */
    private void writeBestBoundsPerWindowCsv(Map<Window, BestBoundsForWindow> bestBoundsPerWindow) throws IOException {
        List<String> headers = List.of("window", "percDown2", "percDown1", "percUp1", "percUp2", "percUp3",
                "deltaVsFixedSurCetteFenetre", "rainbowPnlPercent", "fixedPnlPercent");
        List<List<String>> rows = new ArrayList<>();
        for (Window window : WINDOWS) {
            BestBoundsForWindow best = bestBoundsPerWindow.get(window);
            BoundsCandidate bounds = best.bounds();
            ScenarioRow row = best.scenarioRow();
            rows.add(List.of(window.label(), str(bounds.down2()), str(bounds.down1()), str(bounds.up1()),
                    str(bounds.up2()), str(bounds.up3()), str(row.deltaVsFixed()),
                    str(row.result().getPnlPercent()), str(row.fixedPnlPercent())));
        }
        writeCsv("bounds-per-trend-then-reentry-best-bounds", headers, rows);
    }

    // ---------------------------------------------------------------------------------------
    // Boucle coordinate ascent bornes <-> reentry par tendance (cf. étude §21, demande explicite de
    // Clem) : contrairement à findBestBoundsPerWindow()/runReentryWithBestBoundsPerTrend_realBtcHistory
    // (un seul aller bornes -> reentry), on alterne les deux recherches jusqu'à stabilisation, pour
    // chaque fenêtre indépendamment.
    // ---------------------------------------------------------------------------------------

    /** Une itération de la boucle coordinate ascent pour une fenêtre donnée (cf. étude §21). */
    private record CoordinateAscentIteration(
            int iteration, BoundsCandidate bounds, ReentryCandidate reentry, SmaCandidate sma, ScenarioRow scenarioRow, boolean converged
    ) {
    }

    /** Résultat de {@link #findBestReentryForWindow} : meilleur reentry trouvé + le scénario qui l'a justifié. */
    private record BestReentryForWindow(ReentryCandidate reentry, ScenarioRow scenarioRow) {
    }

    /** Résultat de {@link #findBestSmaForWindow} : meilleure période de SMA trouvée + le scénario qui l'a justifiée. */
    private record BestSmaForWindow(SmaCandidate sma, ScenarioRow scenarioRow) {
    }

    /**
     * Exécute en parallèle les scénarios donnés (tous supposés sur la même fenêtre) et retient
     * celui qui maximise {@code deltaVsFixed} — factorisation du cœur de
     * {@link #findBestBoundsPerWindow()}, réutilisée par la boucle coordinate ascent qui a besoin
     * du même "meilleur par delta" tantôt sur la grille de bornes, tantôt sur celle de reentry.
     */
    private ScenarioRow findBestScenarioForWindow(String logLabel, Window window, List<ScenarioSpec> specs,
                                                   Function<ScenarioRow, BigDecimal> objective, boolean maximize) {
        List<Callable<TaskResult>> tasks = new ArrayList<>();
        for (ScenarioSpec spec : specs) {
            tasks.add(() -> runOneScenario(spec.request(), spec.description(), spec.window()));
        }

        ScenarioRow best = null;
        BigDecimal bestScore = null;
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        try {
            List<Future<TaskResult>> futures = executor.invokeAll(tasks);
            for (Future<TaskResult> future : futures) {
                TaskResult taskResult = future.get();
                ScenarioRow row = taskResult.row();
                if (row == null) {
                    continue;
                }
                BigDecimal score = objective.apply(row);
                if (score == null) {
                    continue;
                }
                boolean better = best == null || (maximize ? score.compareTo(bestScore) > 0 : score.compareTo(bestScore) < 0);
                if (better) {
                    best = row;
                    bestScore = score;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompu pendant " + logLabel, e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Échec inattendu pendant " + logLabel, e.getCause());
        } finally {
            executor.shutdown();
        }
        if (best == null) {
            throw new IllegalStateException("Aucun scénario valide pour " + logLabel + " sur " + window.label());
        }
        return best;
    }

    /**
     * Grille de reentry (mêmes 6 dimensions et mêmes valeurs que la grille "large" utilisée par
     * {@link #runReentryWithBestBoundsPerTrend_realBtcHistory()}, 3x3x3x4x4x5 = 2160 combinaisons),
     * extraite pour être réutilisée par la boucle coordinate ascent — cette méthode de test-là garde
     * volontairement sa propre grille inline pour ne rien changer à son comportement existant.
     */
    private static List<ReentryCandidate> reentryGrid() {
        List<ReentryMode> l_buyMode = List.of(ReentryMode.TRAILING_STOP, ReentryMode.IMMEDIATE, ReentryMode.FIXED_DELAY);
        List<ReentryMode> l_sellMode = List.of(ReentryMode.TRAILING_STOP, ReentryMode.IMMEDIATE, ReentryMode.FIXED_DELAY);
        List<BigDecimal> l_trailingStopPercent = List.of(bd(3), bd(5), bd(7));
        List<Integer> l_cooldown = List.of(1, 3, 7, 12);
        List<Integer> l_fixedDelayDays = List.of(3, 5, 10, 15);
        List<BigDecimal> l_sellFraction = List.of(new BigDecimal("0.10"), new BigDecimal("0.25"), new BigDecimal("0.33"), new BigDecimal("0.5"), new BigDecimal("0.75"));

        List<ReentryCandidate> grid = new ArrayList<>();
        for (ReentryMode buyMode : l_buyMode) {
            for (ReentryMode sellMode : l_sellMode) {
                for (BigDecimal ts : l_trailingStopPercent) {
                    for (int cd : l_cooldown) {
                        for (int fd : l_fixedDelayDays) {
                            for (BigDecimal sf : l_sellFraction) {
                                grid.add(new ReentryCandidate(buyMode, sellMode, ts, cd, fd, sf,
                                        describeReentryValues(buyMode, sellMode, ts, cd, fd, sf)));
                            }
                        }
                    }
                }
            }
        }
        return grid;
    }

    /**
     * Jeu de reentry par défaut = défauts V0 exacts de {@link RainbowDcaBacktestRequest} (point de
     * départ, {@code reentry_0}, de la boucle coordinate ascent).
     */
    private static ReentryCandidate defaultReentryCandidate() {
        ReentryMode buyMode = ReentryMode.TRAILING_STOP;
        ReentryMode sellMode = ReentryMode.IMMEDIATE;
        BigDecimal trailingStopPercent = bd(5);
        int cooldownDays = 3;
        int fixedDelayDays = 10;
        BigDecimal sellFraction = new BigDecimal("0.25");
        return new ReentryCandidate(buyMode, sellMode, trailingStopPercent, cooldownDays, fixedDelayDays, sellFraction,
                describeReentryValues(buyMode, sellMode, trailingStopPercent, cooldownDays, fixedDelayDays, sellFraction));
    }

    /**
     * Grille de périodes de SMA (jours, résolution D1) testée comme 3e dimension du coordinate
     * ascent (cf. étude §23, demande explicite de Clem : le {@code smaPeriod=20} par défaut
     * n'avait jamais été remis en question). Couvre une SMA courte/réactive à longue/lissée ; 20
     * reste le défaut V0 ({@link RainbowDcaBacktestRequest#getSmaPeriod()}) et fait partie de la
     * grille (point de départ {@link #defaultSmaCandidate()}).
     */
    private static List<SmaCandidate> smaGrid() {
        List<Integer> l_smaPeriod = List.of(10, 14, 16, 18, 20, 22, 24, 26);
        List<SmaCandidate> grid = new ArrayList<>();
        for (int period : l_smaPeriod) {
            grid.add(new SmaCandidate(period, describeSmaValue(period)));
        }
        return grid;
    }

    private static String describeSmaValue(int period) {
        return String.format("sma=%dj", period);
    }

    /** SMA par défaut = défaut V0 exact (point de départ, {@code sma_0}, de la boucle coordinate ascent). */
    private static SmaCandidate defaultSmaCandidate() {
        return new SmaCandidate(20, describeSmaValue(20));
    }

    /**
     * Comme {@link #findBestBoundsPerWindow()} mais pour une seule fenêtre et avec les paramètres
     * de reentry FIXÉS à {@code reentry} (au lieu des défauts V0) — brique de la boucle coordinate
     * ascent : "à reentry donné, quelles sont les meilleures bornes pour cette tendance ?". Objectif
     * pluggable ({@code objective}/{@code maximize}, cf. étude §22, demande explicite de Clem) :
     * {@link #runCoordinateAscentPerTrend_realBtcHistory()} maximise {@code deltaVsFixed} (robustesse),
     * {@link #runCoordinateAscentBalancedPerTrend_realBtcHistory()} minimise
     * {@link #computeBalanceGapPercent} (équilibre réalisé/potentiel).
     */
    private BestBoundsForWindow findBestBoundsForWindow(Window window, ReentryCandidate reentry, SmaCandidate sma,
                                                          Function<ScenarioRow, BigDecimal> objective, boolean maximize) {
        List<BoundsCandidate> grid = boundsGrid();
        Map<String, BoundsCandidate> byDescription = new LinkedHashMap<>();
        List<ScenarioSpec> specs = new ArrayList<>();
        for (BoundsCandidate c : grid) {
            byDescription.put(c.description(), c);
            RainbowDcaBacktestRequest request = RainbowDcaBacktestRequest.builder()
                    .symbol(SYMBOL).baseAmount(BASE_AMOUNT)
                    .percDown2(c.down2()).percDown1(c.down1()).percUp1(c.up1()).percUp2(c.up2()).percUp3(c.up3())
                    .buyReentryMode(reentry.buyMode()).sellReentryMode(reentry.sellMode())
                    .trailingStopPercent(reentry.trailingStopPercent()).cooldownDays(reentry.cooldownDays())
                    .fixedDelayDays(reentry.fixedDelayDays()).sellFraction(reentry.sellFraction())
                    .smaPeriod(sma.period())
                    .startDate(window.startDate()).endDate(window.endDate())
                    .build();
            specs.add(new ScenarioSpec(request, c.description(), window));
        }
        ScenarioRow best = findBestScenarioForWindow(
                "recherche des meilleures bornes (reentry+sma fixés) pour " + window.label(), window, specs, objective, maximize);
        return new BestBoundsForWindow(byDescription.get(best.description()), best);
    }

    /**
     * Symétrique de {@link #findBestBoundsForWindow} : pour une seule fenêtre, avec les bornes %
     * FIXÉES à {@code bounds}, cherche la meilleure combinaison de reentry sur
     * {@link #reentryGrid()} — "à bornes données, quelle méthode de sortie ARMÉ est la plus robuste
     * pour cette tendance ?". Objectif pluggable, cf. {@link #findBestBoundsForWindow} (étude §22).
     */
    private BestReentryForWindow findBestReentryForWindow(Window window, BoundsCandidate bounds, SmaCandidate sma,
                                                            Function<ScenarioRow, BigDecimal> objective, boolean maximize) {
        List<ReentryCandidate> grid = reentryGrid();
        Map<String, ReentryCandidate> byDescription = new LinkedHashMap<>();
        List<ScenarioSpec> specs = new ArrayList<>();
        for (ReentryCandidate c : grid) {
            byDescription.put(c.description(), c);
            RainbowDcaBacktestRequest request = RainbowDcaBacktestRequest.builder()
                    .symbol(SYMBOL).baseAmount(BASE_AMOUNT)
                    .percDown2(bounds.down2()).percDown1(bounds.down1()).percUp1(bounds.up1())
                    .percUp2(bounds.up2()).percUp3(bounds.up3())
                    .buyReentryMode(c.buyMode()).sellReentryMode(c.sellMode())
                    .trailingStopPercent(c.trailingStopPercent()).cooldownDays(c.cooldownDays())
                    .fixedDelayDays(c.fixedDelayDays()).sellFraction(c.sellFraction())
                    .smaPeriod(sma.period())
                    .startDate(window.startDate()).endDate(window.endDate())
                    .build();
            specs.add(new ScenarioSpec(request, c.description(), window));
        }
        ScenarioRow best = findBestScenarioForWindow(
                "recherche du meilleur reentry (bornes+sma fixées) pour " + window.label(), window, specs, objective, maximize);
        return new BestReentryForWindow(byDescription.get(best.description()), best);
    }

    /**
     * Symétrique de {@link #findBestBoundsForWindow}/{@link #findBestReentryForWindow} : pour une
     * seule fenêtre, avec bornes ET reentry FIXÉS, cherche la meilleure période de SMA sur
     * {@link #smaGrid()} — 3e dimension du coordinate ascent (cf. étude §23, demande explicite de
     * Clem : vérifier qu'on n'est pas restés sur une SMA=20 arbitraire jamais questionnée).
     * Objectif pluggable, cf. {@link #findBestBoundsForWindow}.
     */
    private BestSmaForWindow findBestSmaForWindow(Window window, BoundsCandidate bounds, ReentryCandidate reentry,
                                                   Function<ScenarioRow, BigDecimal> objective, boolean maximize) {
        List<SmaCandidate> grid = smaGrid();
        Map<String, SmaCandidate> byDescription = new LinkedHashMap<>();
        List<ScenarioSpec> specs = new ArrayList<>();
        for (SmaCandidate c : grid) {
            byDescription.put(c.description(), c);
            RainbowDcaBacktestRequest request = RainbowDcaBacktestRequest.builder()
                    .symbol(SYMBOL).baseAmount(BASE_AMOUNT)
                    .percDown2(bounds.down2()).percDown1(bounds.down1()).percUp1(bounds.up1())
                    .percUp2(bounds.up2()).percUp3(bounds.up3())
                    .buyReentryMode(reentry.buyMode()).sellReentryMode(reentry.sellMode())
                    .trailingStopPercent(reentry.trailingStopPercent()).cooldownDays(reentry.cooldownDays())
                    .fixedDelayDays(reentry.fixedDelayDays()).sellFraction(reentry.sellFraction())
                    .smaPeriod(c.period())
                    .startDate(window.startDate()).endDate(window.endDate())
                    .build();
            specs.add(new ScenarioSpec(request, c.description(), window));
        }
        ScenarioRow best = findBestScenarioForWindow(
                "recherche de la meilleure SMA (bornes+reentry fixés) pour " + window.label(), window, specs, objective, maximize);
        return new BestSmaForWindow(byDescription.get(best.description()), best);
    }

    /**
     * Boucle coordinate ascent bornes {@literal <->} reentry {@literal <->} sma pour UNE fenêtre
     * (demande explicite de Clem, 2026-09-13 pour bornes/reentry cf. étude §21, 3e dimension sma
     * ajoutée le 2026-09-17 cf. étude §23) : reentry_0 = défauts V0
     * ({@link #defaultReentryCandidate()}), sma_0 = défaut V0 ({@link #defaultSmaCandidate()}),
     * puis à chaque itération, dans cet ordre (chaque étape utilise les valeurs déjà mises à jour
     * cette itération-là) : bornes_i = meilleures bornes sachant reentry_(i-1)/sma_(i-1)
     * ({@link #findBestBoundsForWindow}), reentry_i = meilleur reentry sachant bornes_i/sma_(i-1)
     * ({@link #findBestReentryForWindow}), et, si {@code varySma} est vrai, sma_i = meilleure SMA
     * sachant bornes_i/reentry_i ({@link #findBestSmaForWindow}) — jusqu'à ce que (bornes, reentry,
     * sma) ne bougent plus d'une itération à l'autre (comparaison sur la description, donc sur les
     * valeurs exactes) ou jusqu'à {@link #COORDINATE_ASCENT_MAX_ITERATIONS} itérations (garde-fou
     * anti-oscillation : une oscillation stricte entre plusieurs états ne convergerait jamais
     * sinon). Chaque itération est journalisée dans la trace retournée (dernière ligne = état final,
     * {@code converged=true} si arrêt par stabilisation plutôt que par la limite).
     *
     * <p>{@code varySma=false} (cf. étude §24, demande explicite de Clem après le §23 : "je veux
     * qu'on améliore les résultats de façon sûre, pas en prenant des raccourcis") gèle la SMA à
     * {@link #defaultSmaCandidate()} (20j) sur toute la boucle et ignore sa description dans le
     * test de convergence — reproduit EXACTEMENT l'algorithme 2D du §21/§22 (avant l'ajout de la
     * SMA comme 3e dimension). {@link #runCoordinateAscentWithSmaAnchor} appelle cette méthode une
     * fois avec chaque valeur de {@code varySma} pour la même fenêtre et ne retient le résultat
     * "SMA libre" que s'il bat STRICTEMENT l'ancre "SMA=20 fixe" sur l'objectif optimisé —
     * garantit qu'ajouter la SMA comme dimension ne peut jamais dégrader le résultat par rapport à
     * avant son ajout.
     */
    private List<CoordinateAscentIteration> runCoordinateAscentForWindow(
            Window window, Function<ScenarioRow, BigDecimal> objective, boolean maximize, String objectiveLabel,
            boolean varySma) {
        ReentryCandidate reentry = defaultReentryCandidate();
        SmaCandidate sma = defaultSmaCandidate();
        String previousBoundsDescription = null;
        String previousReentryDescription = null;
        String previousSmaDescription = null;
        List<CoordinateAscentIteration> trace = new ArrayList<>();

        for (int iteration = 1; iteration <= COORDINATE_ASCENT_MAX_ITERATIONS; iteration++) {
            BestBoundsForWindow boundsStep = findBestBoundsForWindow(window, reentry, sma, objective, maximize);
            BestReentryForWindow reentryStep = findBestReentryForWindow(window, boundsStep.bounds(), sma, objective, maximize);
            SmaCandidate iterationSma;
            ScenarioRow iterationScenarioRow;
            if (varySma) {
                BestSmaForWindow smaStep = findBestSmaForWindow(window, boundsStep.bounds(), reentryStep.reentry(), objective, maximize);
                iterationSma = smaStep.sma();
                iterationScenarioRow = smaStep.scenarioRow();
            } else {
                iterationSma = sma;
                iterationScenarioRow = reentryStep.scenarioRow();
            }

            boolean converged = boundsStep.bounds().description().equals(previousBoundsDescription)
                    && reentryStep.reentry().description().equals(previousReentryDescription)
                    && iterationSma.description().equals(previousSmaDescription);
            trace.add(new CoordinateAscentIteration(iteration, boundsStep.bounds(), reentryStep.reentry(), iterationSma,
                    iterationScenarioRow, converged));
            RainbowDcaBacktestResult iterationResult = iterationScenarioRow.result();
            log.info(String.format(
                    "%s coordinate ascent itération %d (varySma=%s) : bornes=%s reentry=%s sma=%s"
                            + " | Rainbow investi=%s (%d achats), vendu=%s (%d ventes), PnL=%s (%s%%)"
                            + " [réalisé=%s (%s%%), potentiel=%s (%s%%)]"
                            + " | DCA fixe investi=%s PnL=%s (%s%%) | delta=%s%% | %s = %s%%%s",
                    window.label(), iteration, varySma, boundsStep.bounds().description(), reentryStep.reentry().description(),
                    iterationSma.description(),
                    str(iterationResult.getTotalInvested()), iterationResult.getBuyTriggeredCount(),
                    str(iterationResult.getTotalSaleProceeds()), iterationResult.getSellTriggeredCount(),
                    str(iterationResult.getPnl()), str(iterationResult.getPnlPercent()),
                    str(iterationResult.getRealizedGain()), str(iterationResult.getRealizedGainPercent()),
                    str(iterationResult.getPotentialGain()), str(iterationResult.getPotentialGainPercent()),
                    str(iterationScenarioRow.fixedTotalInvested()), str(iterationScenarioRow.fixedPnl()),
                    str(iterationScenarioRow.fixedPnlPercent()), str(iterationScenarioRow.deltaVsFixed()),
                    objectiveLabel, str(objective.apply(iterationScenarioRow)),
                    converged ? " -> STABILISÉ" : ""));

            if (converged) {
                break;
            }
            previousBoundsDescription = boundsStep.bounds().description();
            previousReentryDescription = reentryStep.reentry().description();
            previousSmaDescription = iterationSma.description();
            reentry = reentryStep.reentry();
            sma = iterationSma;
        }

        if (!trace.getLast().converged()) {
            log.warning(window.label() + " : coordinate ascent (" + objectiveLabel + ", varySma=" + varySma + ") non stabilisé après "
                    + COORDINATE_ASCENT_MAX_ITERATIONS
                    + " itération(s) — conservation du dernier état atteint (oscillation possible entre plusieurs jeux de paramètres).");
        }
        return trace;
    }

    /** Résultat de {@link #runCoordinateAscentWithSmaAnchor} : trajectoire retenue + détail des deux comparés (cf. étude §24). */
    private record AnchoredCoordinateAscentResult(
            List<CoordinateAscentIteration> retainedTrace, boolean freeSmaWins,
            CoordinateAscentIteration freeLast, CoordinateAscentIteration anchorLast) {
    }

    /**
     * Lance {@link #runCoordinateAscentForWindow} deux fois pour la même fenêtre — une fois SMA
     * libre ({@code varySma=true}), une fois SMA gelée à 20 ({@code varySma=false}, ancre =
     * algorithme 2D exact du §21/§22, avant l'ajout de la SMA) — et ne retient le résultat SMA
     * libre que s'il bat STRICTEMENT l'ancre sur l'objectif optimisé (sinon l'ancre est conservée
     * telle quelle). Garantit qu'ajouter la SMA comme 3e dimension du coordinate ascent (§23) ne
     * peut jamais afficher un résultat pire que celui obtenu avant son ajout (cf. étude §24,
     * demande explicite de Clem après avoir constaté une régression : "je veux qu'on améliore les
     * résultats de façon sûre... pas en prenant des raccourcis").
     */
    private AnchoredCoordinateAscentResult runCoordinateAscentWithSmaAnchor(
            Window window, Function<ScenarioRow, BigDecimal> objective, boolean maximize, String objectiveLabel) {
        List<CoordinateAscentIteration> freeTrace = runCoordinateAscentForWindow(window, objective, maximize, objectiveLabel, true);
        List<CoordinateAscentIteration> anchorTrace = runCoordinateAscentForWindow(window, objective, maximize, objectiveLabel, false);
        CoordinateAscentIteration freeLast = freeTrace.getLast();
        CoordinateAscentIteration anchorLast = anchorTrace.getLast();
        BigDecimal freeScore = objective.apply(freeLast.scenarioRow());
        BigDecimal anchorScore = objective.apply(anchorLast.scenarioRow());
        boolean freeWins = maximize ? freeScore.compareTo(anchorScore) > 0 : freeScore.compareTo(anchorScore) < 0;
        log.info(String.format(
                "%s : comparaison SMA libre vs SMA=20 fixe (objectif = %s) -> SMA libre = %s%% (sma=%s) | SMA=20 fixe = %s%% -> retenu : %s",
                window.label(), objectiveLabel, str(freeScore), freeLast.sma().description(), str(anchorScore),
                freeWins ? "SMA libre" : "SMA=20 fixe (la SMA libre n'améliore pas)"));
        return new AnchoredCoordinateAscentResult(freeWins ? freeTrace : anchorTrace, freeWins, freeLast, anchorLast);
    }

    /** CSV de traçabilité complète d'une boucle coordinate ascent pour une fenêtre (une ligne par itération). */
    private void writeCoordinateAscentTraceCsv(String csvPrefix, Window window, List<CoordinateAscentIteration> trace) throws IOException {
        List<String> headers = List.of("iteration", "boundsDescription", "reentryDescription", "smaDescription",
                "pnlPercent", "fixedPnlPercent", "deltaVsFixed", "realizedGainPercent", "potentialGainPercent",
                "balanceGapPercent", "converged");
        List<List<String>> rows = new ArrayList<>();
        for (CoordinateAscentIteration it : trace) {
            RainbowDcaBacktestResult r = it.scenarioRow().result();
            rows.add(List.of(String.valueOf(it.iteration()), it.bounds().description(), it.reentry().description(),
                    it.sma().description(), str(r.getPnlPercent()), str(it.scenarioRow().fixedPnlPercent()),
                    str(it.scenarioRow().deltaVsFixed()), str(r.getRealizedGainPercent()), str(r.getPotentialGainPercent()),
                    str(computeBalanceGapPercent(it.scenarioRow())), String.valueOf(it.converged())));
        }
        writeCsv(csvPrefix + "-" + window.label() + "-trace", headers, rows);
    }

    /** CSV final : une ligne par fenêtre, le paramétrage (bornes + reentry) stabilisé par {@link #runCoordinateAscentForWindow}. */
    private void writeCoordinateAscentFinalCsv(String csvPrefix, Map<Window, CoordinateAscentIteration> finalPerWindow) throws IOException {
        List<String> headers = List.of("window", "iterations", "converged", "boundsDescription", "reentryDescription",
                "smaDescription", "pnlPercent", "fixedPnlPercent", "deltaVsFixed", "realizedGainPercent",
                "potentialGainPercent", "balanceGapPercent");
        List<List<String>> rows = new ArrayList<>();
        for (Window window : WINDOWS) {
            CoordinateAscentIteration last = finalPerWindow.get(window);
            RainbowDcaBacktestResult r = last.scenarioRow().result();
            rows.add(List.of(window.label(), String.valueOf(last.iteration()), String.valueOf(last.converged()),
                    last.bounds().description(), last.reentry().description(), last.sma().description(),
                    str(r.getPnlPercent()), str(last.scenarioRow().fixedPnlPercent()), str(last.scenarioRow().deltaVsFixed()),
                    str(r.getRealizedGainPercent()), str(r.getPotentialGainPercent()),
                    str(computeBalanceGapPercent(last.scenarioRow()))));
        }
        writeCsv(csvPrefix + "-final-per-trend", headers, rows);
    }

    /**
     * Lance UN backtest séquentiel par fenêtre avant la parallélisation, pour peupler le cache DB
     * H1 ({@code CachingMarketDataApiClient}) une seule fois par fenêtre. Sans ça, les premiers
     * threads du pool qui tombent sur la même fenêtre pas encore en cache déclencheraient tous en
     * même temps le même fetch réseau + la même insertion DB. La plage H1 fetchée ne dépend que de
     * {@code startDate}/{@code endDate}/{@code smaPeriod} (identiques pour tous les jeux de
     * paramètres d'un même banc), donc n'importe quel builder du banc convient pour le pré-chauffage.
     */
    private void warmUpCache(RainbowDcaBacktestRequest.RainbowDcaBacktestRequestBuilder templateBuilder) {
        for (Window window : WINDOWS) {
            try {
                rainbowDcaBacktestService.backtest(templateBuilder.startDate(window.startDate()).endDate(window.endDate()).build());
                log.info("Cache H1 pré-chauffé pour " + window.label());
            } catch (Exception e) {
                log.warning("Pré-chauffe cache échouée pour " + window.label() + " (les threads parallèles retenteront individuellement) : " + e.getMessage());
            }
        }
    }

    /**
     * Comme {@link #warmUpCache} mais avec la plus GRANDE période de {@link #smaGrid()} au lieu du
     * défaut V0 — nécessaire depuis que la boucle coordinate ascent fait varier {@code smaPeriod}
     * (étude §23) : la plage H1 de warm-up est {@code [startDate - smaPeriod, endDate]}, donc la
     * plage de la SMA la plus longue englobe celle de toute SMA plus courte de la grille — un seul
     * pré-chauffage avec le maximum suffit à couvrir toutes les valeurs testées (les candles déjà
     * en cache DB pour la plage large couvrent toute sous-plage), pas besoin de boucler sur
     * {@link #smaGrid()} entière. Appelée en tête de
     * {@link #runCoordinateAscentPerTrend_realBtcHistory()} et
     * {@link #runCoordinateAscentBalancedPerTrend_realBtcHistory()} à la place de
     * {@link #warmUpCache} seul.
     */
    private void warmUpCacheForSmaGrid() {
        int maxSmaPeriod = smaGrid().stream().mapToInt(SmaCandidate::period).max().orElseThrow();
        warmUpCache(RainbowDcaBacktestRequest.builder().symbol(SYMBOL).baseAmount(BASE_AMOUNT).smaPeriod(maxSmaPeriod));
    }

    private TaskResult runOneScenario(RainbowDcaBacktestRequest request, String description, Window window) {
        try {
            RainbowDcaBacktestResult result = rainbowDcaBacktestService.backtest(request);
            DcaResult fixed = result.getFixedDcaComparison();
            BigDecimal fixedPnlPercent = fixed.getPnlPercent();
            BigDecimal rainbowPnlPercent = result.getPnlPercent();
            BigDecimal delta = (rainbowPnlPercent != null && fixedPnlPercent != null)
                    ? rainbowPnlPercent.subtract(fixedPnlPercent)
                    : null;
            BigDecimal maxDrawdown = computeMaxDrawdownPercent(result.getOccurrences());
            ScenarioRow row = new ScenarioRow(description, window.label(), result, fixedPnlPercent, delta, maxDrawdown,
                    fixed.getTotalInvested(), fixed.getPnl());
            return new TaskResult(row, description, window.label(), null);
        } catch (Exception e) {
            return new TaskResult(null, description, window.label(), e.getMessage());
        }
    }

    private String describeBounds(RainbowDcaBacktestRequest r) {
        return describeBoundsValues(r.getPercDown2(), r.getPercDown1(), r.getPercUp1(), r.getPercUp2(), r.getPercUp3());
    }

    private static String describeBoundsValues(BigDecimal down2, BigDecimal down1, BigDecimal up1, BigDecimal up2, BigDecimal up3) {
        return String.format("pd2=%s pd1=%s pu1=%s pu2=%s pu3=%s", down2, down1, up1, up2, up3);
    }

    private String describeReentry(RainbowDcaBacktestRequest r) {
        return describeReentryValues(r.getBuyReentryMode(), r.getSellReentryMode(), r.getTrailingStopBuyPercent(),
                r.getCooldownDays(), r.getFixedDelayDays(), r.getSellFraction());
    }

    private static String describeReentryValues(ReentryMode buyMode, ReentryMode sellMode, BigDecimal trailingStopPercent,
                                                  int cooldownDays, int fixedDelayDays, BigDecimal sellFraction) {
        return String.format("buy=%s sell=%s ts=%s%% cd=%dj fd=%dj sf=%s",
                buyMode, sellMode, trailingStopPercent, cooldownDays, fixedDelayDays, sellFraction);
    }

    private static boolean isOrdered(BigDecimal down2, BigDecimal down1, BigDecimal up1, BigDecimal up2, BigDecimal up3) {
        return down2.compareTo(down1) < 0 && down1.compareTo(up1) < 0 && up1.compareTo(up2) < 0 && up2.compareTo(up3) < 0;
    }

    private static BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }

    /**
     * Max drawdown (%) de l'exposition marché (position * close), calculé ici plutôt que dans
     * {@link RainbowDcaBacktestResult} : ne vaut que pour l'analyse de ce runner, ne fait pas
     * partie du contrat du service (cf. docs/CODING_RULES.md — ne pas coder ce dont on n'a pas
     * besoin ailleurs). Ignore volontairement le cash issu des ventes (non réinvesti) : mesure le
     * risque de l'exposition réellement au marché, pas la richesse totale.
     */
    private static BigDecimal computeMaxDrawdownPercent(List<RainbowDcaOccurrence> occurrences) {
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (RainbowDcaOccurrence occ : occurrences) {
            BigDecimal equity = occ.getPositionAfter().multiply(occ.getClose());
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }
            if (peak.signum() > 0) {
                BigDecimal drawdown = peak.subtract(equity).divide(peak, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                if (drawdown.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = drawdown;
                }
            }
        }
        return maxDrawdown;
    }

    /**
     * Variation du prix BTC entre le premier et le dernier jour de la fenêtre — contexte pour
     * juger l'ampleur du régime testé (cf. étude §18/§19, demande explicite de Clem). Indépendant
     * des paramètres Rainbow testés (même série de prix pour tous les scénarios d'une fenêtre
     * donnée) : calculé une seule fois par fenêtre dans {@link #writePerWindowReports}, à partir
     * des occurrences d'un seul {@code ScenarioRow} de cette fenêtre.
     */
    private static BigDecimal computePriceChangePercent(List<RainbowDcaOccurrence> occurrences) {
        if (occurrences == null || occurrences.isEmpty()) {
            return null;
        }
        BigDecimal firstClose = occurrences.getFirst().getClose();
        BigDecimal lastClose = occurrences.getLast().getClose();
        if (firstClose == null || lastClose == null || firstClose.signum() == 0) {
            return null;
        }
        return lastClose.subtract(firstClose).divide(firstClose, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    /**
     * Amplitude du prix BTC sur la fenêtre : {@code (max - min) / min}, en %. Complète
     * {@link #computePriceChangePercent} plutôt que de le remplacer : une variation début→fin
     * quasi nulle peut cacher une chute puis un rebond violents (ou l'inverse) sur la même
     * fenêtre — cette métrique révèle ce que la variation début/fin ne peut pas voir, et explique
     * qu'un DCA périodique (qui achète pendant le creux) affiche un PnL supérieur à ce que
     * suggère la seule variation début/fin, sans que ce soit un bug de calcul (cf. étude §19,
     * question explicite de Clem sur des PnL qui lui semblaient trop élevés).
     */
    private static BigDecimal computePriceAmplitudePercent(List<RainbowDcaOccurrence> occurrences) {
        if (occurrences == null || occurrences.isEmpty()) {
            return null;
        }
        BigDecimal min = null;
        BigDecimal max = null;
        for (RainbowDcaOccurrence occ : occurrences) {
            BigDecimal close = occ.getClose();
            if (close == null) {
                continue;
            }
            if (min == null || close.compareTo(min) < 0) {
                min = close;
            }
            if (max == null || close.compareTo(max) > 0) {
                max = close;
            }
        }
        if (min == null || max == null || min.signum() == 0) {
            return null;
        }
        return max.subtract(min).divide(min, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    /**
     * Écart absolu entre plus-value réalisée et potentielle (en % de l'investi) pour un scénario —
     * 0 = parfait équilibre entre gain déjà encaissé et gain encore en position (cf. étude §20,
     * demande explicite de Clem). Le classement par robustesse ({@link #printLeaderboard}) et le
     * rapport par tendance trié par delta ({@link #printWindowLeaderboard}) favorisent tous deux
     * mécaniquement les jeux de paramètres qui vendent peu (gain purement potentiel, non garanti
     * tant que non vendu) — cette métrique permet à l'inverse de repérer les jeux de paramètres qui
     * sécurisent une partie du gain sans regarder uniquement la performance brute.
     */
    private static BigDecimal computeBalanceGapPercent(ScenarioRow row) {
        RainbowDcaBacktestResult r = row.result();
        BigDecimal realized = r.getRealizedGainPercent();
        BigDecimal potential = r.getPotentialGainPercent();
        if (realized == null || potential == null) {
            return null;
        }
        return realized.subtract(potential).abs();
    }

    // ---------------------------------------------------------------------------------------
    // Sorties : CSV complet (une ligne par scénario x fenêtre, avec total des ventes + plus-value
    // réalisée/potentielle par fenêtre) + CSV agrégé (une ligne par jeu de paramètres, avec le PnL
    // Rainbow ET le PnL du DCA fixe par fenêtre, pas seulement leur delta, plus la moyenne de
    // plus-value réalisée/potentielle sur les 3 fenêtres) + résumé console à largeur de colonne
    // calculée dynamiquement (l'ancien format avait une largeur d'en-tête différente de celle des
    // lignes -> colonnes désalignées).
    // ---------------------------------------------------------------------------------------

    private void writeDetailCsv(String benchName, List<ScenarioRow> rows) throws IOException {
        List<String> headers = List.of("description", "window", "totalInvested", "zoneBuyCount", "buyTriggeredCount",
                "sellTriggeredCount", "avgBuyPrice", "pnlPercent", "fixedPnlPercent", "deltaVsFixed", "maxDrawdownPercent",
                "totalSaleProceeds", "realizedGainPercent", "potentialGainPercent");
        List<List<String>> csvRows = new ArrayList<>();
        for (ScenarioRow row : rows) {
            RainbowDcaBacktestResult r = row.result();
            csvRows.add(List.of(
                    row.description(), row.window(), str(r.getTotalInvested()), String.valueOf(r.getZoneBuyCount()),
                    String.valueOf(r.getBuyTriggeredCount()), String.valueOf(r.getSellTriggeredCount()),
                    str(r.getAvgBuyPrice()), str(r.getPnlPercent()), str(row.fixedPnlPercent()),
                    str(row.deltaVsFixed()), str(row.maxDrawdownPercent()),
                    str(r.getTotalSaleProceeds()), str(r.getRealizedGainPercent()), str(r.getPotentialGainPercent())
            ));
        }
        writeCsv(benchName + "-detail", headers, csvRows);
    }

    private void writeAggregatedCsv(String benchName, List<AggregatedRow> rows) throws IOException {
        List<String> headers = new ArrayList<>(List.of("description"));
        for (Window w : WINDOWS) {
            headers.add("rainbowPnl_" + w.label());
            headers.add("fixedPnl_" + w.label());
            headers.add("delta_" + w.label());
        }
        headers.add("minDelta_pireFenetre");
        headers.add("maxDelta_meilleureFenetre");
        headers.add("avgDelta");
        headers.add("avgRealizedGainPercent");
        headers.add("avgPotentialGainPercent");

        List<List<String>> csvRows = new ArrayList<>();
        for (AggregatedRow row : rows) {
            List<String> csvRow = new ArrayList<>();
            csvRow.add(row.description());
            for (Window w : WINDOWS) {
                WindowOutcome outcome = row.byWindow().get(w.label());
                csvRow.add(str(outcome.rainbowPnlPercent()));
                csvRow.add(str(outcome.fixedPnlPercent()));
                csvRow.add(str(outcome.delta()));
            }
            csvRow.add(str(row.minDelta()));
            csvRow.add(str(row.maxDelta()));
            csvRow.add(str(row.avgDelta()));
            csvRow.add(str(row.avgRealizedGainPercent()));
            csvRow.add(str(row.avgPotentialGainPercent()));
            csvRows.add(csvRow);
        }
        writeCsv(benchName + "-aggregated", headers, csvRows);
    }

    private void printLeaderboard(String benchName, List<AggregatedRow> aggregated, int topN) {
        List<String> headers = new ArrayList<>(List.of("description"));
        for (Window w : WINDOWS) {
            headers.add(w.label() + "_rainbow%");
            headers.add(w.label() + "_fixe%");
        }
        headers.add("minDelta(pire)");
        headers.add("maxDelta(meilleure)");
        headers.add("avgDelta");
        headers.add("avgRealizedGain%");
        headers.add("avgPotentialGain%");

        List<List<String>> rows = new ArrayList<>();
        for (AggregatedRow row : aggregated.subList(0, Math.min(topN, aggregated.size()))) {
            List<String> r = new ArrayList<>();
            r.add(row.description());
            for (Window w : WINDOWS) {
                WindowOutcome outcome = row.byWindow().get(w.label());
                r.add(str(outcome.rainbowPnlPercent()));
                r.add(str(outcome.fixedPnlPercent()));
            }
            r.add(str(row.minDelta()));
            r.add(str(row.maxDelta()));
            r.add(str(row.avgDelta()));
            r.add(str(row.avgRealizedGainPercent()));
            r.add(str(row.avgPotentialGainPercent()));
            rows.add(r);
        }
        log.info("===== " + benchName + " : top " + rows.size() + " par robustesse — PnL% Rainbow vs PnL% DCA fixe par "
                + "fenêtre, minDelta = pire fenêtre (critère de tri), maxDelta = meilleure fenêtre, avgRealizedGain%/"
                + "avgPotentialGain% = plus-value Rainbow déjà encaissée / encore en position (moyenne sur les 3 fenêtres) =====");
        printTable(headers, rows);
    }

    /**
     * Un rapport par tendance (BULL/BEAR/SIDEWAYS), demande explicite de Clem (2026-09-13, cf.
     * étude §17) : la vue agrégée ({@link #printLeaderboard}/{@link #writeAggregatedCsv}) moyenne
     * {@code realizedGainPercent}/{@code potentialGainPercent} sur les 3 fenêtres, ce qui masque
     * la décomposition réalisé/potentiel propre à CHAQUE tendance (un bear peut avoir beaucoup
     * vendu — {@code realizedGainPercent} élevé — quand un bull encore en cours n'aura presque rien
     * vendu). Un CSV + un résumé console par fenêtre, triés par delta de PnL% vs DCA fixe
     * décroissant (pas par robustesse, qui n'a de sens qu'à travers les fenêtres) : chaque rapport
     * montre le PnL Rainbow/fixe, le delta, ET la plus-value réalisée/potentielle EXACTE de cette
     * tendance (pas une moyenne).
     */
    private void writePerWindowReports(String benchName, List<ScenarioRow> allRows, int topN) throws IOException {
        writePerWindowReports(benchName, allRows, topN, Map.of());
    }

    private void writePerWindowReports(String benchName, List<ScenarioRow> allRows, int topN,
                                        Map<String, String> fixedBoundsPerWindowLabel) throws IOException {
        for (Window window : WINDOWS) {
            List<ScenarioRow> windowRows = allRows.stream()
                    .filter(r -> r.window().equals(window.label()))
                    .filter(r -> r.deltaVsFixed() != null)
                    .sorted(Comparator.comparing(ScenarioRow::deltaVsFixed, Comparator.reverseOrder()))
                    .toList();
            List<RainbowDcaOccurrence> sampleOccurrences = windowRows.isEmpty()
                    ? null
                    : windowRows.getFirst().result().getOccurrences();
            BigDecimal priceChangePercent = computePriceChangePercent(sampleOccurrences);
            BigDecimal priceAmplitudePercent = computePriceAmplitudePercent(sampleOccurrences);
            String fixedBounds = fixedBoundsPerWindowLabel.get(window.label());
            writeWindowCsv(benchName, window.label(), windowRows, priceChangePercent, priceAmplitudePercent, fixedBounds);
            printWindowLeaderboard(benchName, window.label(), windowRows, topN, priceChangePercent, priceAmplitudePercent, fixedBounds);

            List<ScenarioRow> balancedRows = windowRows.stream()
                    .filter(r -> computeBalanceGapPercent(r) != null)
                    .sorted(Comparator.comparing(RainbowDcaBacktestManualRunnerTest::computeBalanceGapPercent))
                    .toList();
            writeBalancedGainCsv(benchName, window.label(), balancedRows, fixedBounds);
            printBalancedGainLeaderboard(benchName, window.label(), balancedRows, topN, fixedBounds);
        }
    }

    private void writeWindowCsv(String benchName, String windowLabel, List<ScenarioRow> rows,
                                 BigDecimal priceChangePercent, BigDecimal priceAmplitudePercent,
                                 String fixedBoundsDescription) throws IOException {
        List<String> headers = new ArrayList<>(List.of("description", "btcPriceChangePercent", "btcPriceAmplitudePercent",
                "totalInvested", "fixedTotalInvested", "pnlPercent", "fixedPnlPercent", "deltaVsFixed",
                "realizedGain", "potentialGain", "pnl", "fixedGain", "realizedGainPercent", "potentialGainPercent",
                "totalSaleProceeds", "maxDrawdownPercent"));
        if (fixedBoundsDescription != null) {
            headers.add("boundsFixedForThisWindow");
        }
        List<List<String>> csvRows = new ArrayList<>();
        for (ScenarioRow row : rows) {
            RainbowDcaBacktestResult r = row.result();
            List<String> csvRow = new ArrayList<>(List.of(row.description(), str(priceChangePercent), str(priceAmplitudePercent),
                    str(r.getTotalInvested()), str(row.fixedTotalInvested()), str(r.getPnlPercent()),
                    str(row.fixedPnlPercent()), str(row.deltaVsFixed()), str(r.getRealizedGain()),
                    str(r.getPotentialGain()), str(r.getPnl()), str(row.fixedPnl()), str(r.getRealizedGainPercent()),
                    str(r.getPotentialGainPercent()), str(r.getTotalSaleProceeds()), str(row.maxDrawdownPercent())));
            if (fixedBoundsDescription != null) {
                csvRow.add(fixedBoundsDescription);
            }
            csvRows.add(csvRow);
        }
        writeCsv(benchName + "-" + windowLabel + "-report", headers, csvRows);
    }

    private void printWindowLeaderboard(String benchName, String windowLabel, List<ScenarioRow> rows, int topN,
                                         BigDecimal priceChangePercent, BigDecimal priceAmplitudePercent,
                                         String fixedBoundsDescription) {
        List<String> headers = List.of("description", "totalInvested", "fixedTotalInvested", "pnl", "fixedGain",
                "pnlPercent", "fixedPnlPercent", "deltaVsFixed", "realizedGainPercent", "potentialGainPercent");
        List<List<String>> tableRows = new ArrayList<>();
        for (ScenarioRow row : rows.subList(0, Math.min(topN, rows.size()))) {
            RainbowDcaBacktestResult r = row.result();
            tableRows.add(List.of(row.description(), str(r.getTotalInvested()), str(row.fixedTotalInvested()),
                    str(r.getPnl()), str(row.fixedPnl()), str(r.getPnlPercent()), str(row.fixedPnlPercent()),
                    str(row.deltaVsFixed()), str(r.getRealizedGainPercent()), str(r.getPotentialGainPercent())));
        }
        String boundsContext = fixedBoundsDescription != null
                ? " — bornes fixées pour cette fenêtre : " + fixedBoundsDescription
                : "";
        log.info("===== " + benchName + " / " + windowLabel + boundsContext + " (variation BTC début->fin : " + str(priceChangePercent)
                + "%, amplitude min->max : " + str(priceAmplitudePercent) + "%) : top " + tableRows.size()
                + " par delta de PnL% vs DCA fixe SUR CETTE FENÊTRE (pas de robustesse cross-fenêtre ici) — "
                + "totalInvested/pnl/fixedGain en valeur absolue à côté des % : Rainbow investit structurellement "
                + "moins que le DCA fixe (cf. étude §12), donc un % de rendement seul ne dit pas qui a le plus "
                + "gagné en valeur ; fixedGain = gain du DCA fixe (= son potentialGain, il ne vend jamais donc son "
                + "realizedGain est nul) ; realizedGain%/potentialGain% = plus-value Rainbow déjà encaissée / "
                + "encore en position sur cette tendance précise (pas une moyenne) =====");
        printTable(headers, tableRows);
    }

    /**
     * CSV "équilibre réalisé/potentiel" par tendance (2026-09-13, cf. étude §20, demande explicite
     * de Clem) : mêmes scénarios que {@link #writeWindowCsv} pour cette fenêtre, mais triés par
     * {@link #computeBalanceGapPercent} croissant (0 = parfait équilibre) au lieu du delta vs DCA
     * fixe — un tri complémentaire, pas un remplacement (aucune ligne filtrée en plus de celles
     * déjà exclues faute de realizedGainPercent/potentialGainPercent).
     */
    private void writeBalancedGainCsv(String benchName, String windowLabel, List<ScenarioRow> rows,
                                       String fixedBoundsDescription) throws IOException {
        List<String> headers = new ArrayList<>(List.of("description", "balanceGapPercent", "realizedGainPercent",
                "potentialGainPercent", "pnlPercent", "fixedPnlPercent", "deltaVsFixed", "totalInvested", "pnl"));
        if (fixedBoundsDescription != null) {
            headers.add("boundsFixedForThisWindow");
        }
        List<List<String>> csvRows = new ArrayList<>();
        for (ScenarioRow row : rows) {
            RainbowDcaBacktestResult r = row.result();
            List<String> csvRow = new ArrayList<>(List.of(row.description(), str(computeBalanceGapPercent(row)), str(r.getRealizedGainPercent()),
                    str(r.getPotentialGainPercent()), str(r.getPnlPercent()), str(row.fixedPnlPercent()),
                    str(row.deltaVsFixed()), str(r.getTotalInvested()), str(r.getPnl())));
            if (fixedBoundsDescription != null) {
                csvRow.add(fixedBoundsDescription);
            }
            csvRows.add(csvRow);
        }
        writeCsv(benchName + "-" + windowLabel + "-balanced-report", headers, csvRows);
    }

    /**
     * Leaderboard console "équilibre réalisé/potentiel" par tendance — top N par
     * {@link #computeBalanceGapPercent} croissant. Volontairement PAS un critère de performance en
     * soi : {@code pnlPercent}/{@code deltaVsFixed} restent affichés à côté pour que Clem arbitre
     * lui-même le compromis équilibre vs performance (un jeu de paramètres parfaitement équilibré
     * mais qui rapporte peu n'est pas forcément préférable à un jeu légèrement déséquilibré mais
     * bien plus performant).
     */
    private void printBalancedGainLeaderboard(String benchName, String windowLabel, List<ScenarioRow> rows, int topN,
                                               String fixedBoundsDescription) {
        List<String> headers = List.of("description", "balanceGapPercent", "realizedGainPercent",
                "potentialGainPercent", "pnlPercent", "deltaVsFixed", "totalInvested", "pnl");
        List<List<String>> tableRows = new ArrayList<>();
        for (ScenarioRow row : rows.subList(0, Math.min(topN, rows.size()))) {
            RainbowDcaBacktestResult r = row.result();
            tableRows.add(List.of(row.description(), str(computeBalanceGapPercent(row)), str(r.getRealizedGainPercent()),
                    str(r.getPotentialGainPercent()), str(r.getPnlPercent()), str(row.deltaVsFixed()),
                    str(r.getTotalInvested()), str(r.getPnl())));
        }
        String boundsContext = fixedBoundsDescription != null
                ? " — bornes fixées pour cette fenêtre : " + fixedBoundsDescription
                : "";
        log.info("===== " + benchName + " / " + windowLabel + boundsContext + " : top " + tableRows.size()
                + " par équilibre réalisé/potentiel (balanceGapPercent croissant, 0 = parfait équilibre gain "
                + "encaissé/gain en position) — PAS un critère de performance en soi (le classement robustesse "
                + "favorise au contraire les paramètres qui ne vendent presque rien, gain purement potentiel donc "
                + "non garanti) : regarder pnlPercent/deltaVsFixed à côté pour juger le compromis équilibre vs "
                + "performance =====");
        printTable(headers, tableRows);
    }

    private static String str(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Écrit un CSV séparé par ";" (Excel FR) sous {@code target/rainbow-dca-bench/<name>-<timestamp>.csv}. */
    private void writeCsv(String name, List<String> headers, List<List<String>> rows) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path path = OUTPUT_DIR.resolve(name + "-" + timestamp + ".csv");
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(";", headers)).append(System.lineSeparator());
        for (List<String> row : rows) {
            sb.append(String.join(";", row)).append(System.lineSeparator());
        }
        Files.writeString(path, sb.toString());
        log.info("CSV écrit : " + path.toAbsolutePath());
    }

    /** Tableau console à largeur de colonne calculée sur le contenu réel (en-tête + lignes). */
    private static void printTable(List<String> headers, List<List<String>> rows) {
        int[] widths = new int[headers.size()];
        for (int c = 0; c < headers.size(); c++) {
            widths[c] = headers.get(c).length();
        }
        for (List<String> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                widths[c] = Math.max(widths[c], row.get(c).length());
            }
        }
        log.info(formatRow(headers, widths));
        for (List<String> row : rows) {
            log.info(formatRow(row, widths));
        }
    }

    private static String formatRow(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cells.size(); c++) {
            sb.append(String.format("%-" + (widths[c] + 2) + "s", cells.get(c)));
        }
        return sb.toString();
    }

    /**
     * Substitue, uniquement pour ce test, un {@link DcaCalculatorService} qui mémoïse
     * {@code calculate(..., valuationInstant)} par jeu d'arguments exact (record {@link CalculateKey}).
     * Repose sur les mêmes beans que le vrai service (mêmes qualifiers) — seule la méthode
     * {@code calculate} change de comportement, via override d'une sous-classe anonyme qui délègue à
     * {@code super.calculate(...)} au premier appel pour chaque jeu d'arguments puis sert le
     * résultat en cache ensuite. N'override que l'overload à 9 arguments ({@code valuationInstant}) :
     * c'est le seul appelé par {@link RainbowDcaBacktestService} depuis le fix du 2026-09-13 (étude
     * §14) — l'overload à 8 arguments de la classe de base délègue déjà dynamiquement à celui-ci
     * avec {@code valuationInstant = null}, donc reste mémoïsé automatiquement pour tout autre
     * appelant. {@code @Primary} : remplace le bean {@code @Service} standard pour tout le contexte
     * Spring de CE test uniquement (aucun impact hors de ce test). Le cache interne
     * ({@code ConcurrentHashMap}) est thread-safe pour l'exécution parallélisée ci-dessus.
     */
    @TestConfiguration
    static class MemoizingDcaCalculatorServiceConfig {

        @Bean
        @Primary
        DcaCalculatorService memoizingDcaCalculatorService(
                @Qualifier("cachingBinanceMarketDataApiClient") MarketDataApiClient binanceClient,
                @Qualifier("cachingKrakenMarketDataApiClient") MarketDataApiClient krakenClient,
                @Qualifier("cachingOkxMarketDataApiClient") MarketDataApiClient okxClient,
                DomainClock clock,
                AssetProviderRepository assetProviderRepository
        ) {
            return new DcaCalculatorService(binanceClient, krakenClient, okxClient, clock, assetProviderRepository) {
                private final Map<CalculateKey, DcaResult> cache = new ConcurrentHashMap<>();

                @Override
                public DcaResult calculate(
                        String symbol, LocalDate startDate, LocalDate endDate, TimeFrame frequency,
                        int purchaseHourUtc, BigDecimal amount, BigDecimal feePercent, MarketDataSource source,
                        Instant valuationInstant
                ) {
                    CalculateKey key = new CalculateKey(
                            symbol, startDate, endDate, frequency, purchaseHourUtc, amount, feePercent, source, valuationInstant);
                    return cache.computeIfAbsent(key, k -> super.calculate(
                            symbol, startDate, endDate, frequency, purchaseHourUtc, amount, feePercent, source, valuationInstant));
                }
            };
        }

        private record CalculateKey(
                String symbol, LocalDate startDate, LocalDate endDate, TimeFrame frequency,
                int purchaseHourUtc, BigDecimal amount, BigDecimal feePercent, MarketDataSource source,
                Instant valuationInstant
        ) {
        }
    }
}
