package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;

import java.util.List;
import java.util.Map;

/**
 * Source unique des constantes du bench grandeur nature et des presets par défaut : jeu global du bench
 * Rainbow ATR v3 par actif (cf. {@code docs/calibration/calibration-rainbow-atr-v3-regime.md} et
 * {@code rainbow-atr-v3-bench-report-{btc,eth,paxg}.md}). Multiplicateurs de zone et base = valeurs du
 * pine ({@link RainbowAtrGlobals#pineDefault()}), identiques à celles du bench.
 */
public final class RainbowLiveDefaultPresets {

    /** Actifs autorisés (symboles nus d'Asset). */
    public static final List<String> ASSETS = List.of("BTC", "ETH", "PAXG");
    /** Seul stablecoin du wallet mock. */
    public static final String STABLECOIN = "USDC";
    public static final String DEFAULT_NAME = "Bench global";
    public static final String TREND_MIX_NAME = "Trend Mix";
    /** Préfixe réservé du nom des presets système (copies des templates) ; refusé sur un preset créé par l'utilisateur. */
    public static final String SYSTEM_PREFIX = "[Système] ";
    public static final int DEFAULT_ANALYSIS_WINDOW_MONTHS = 6;
    public static final double DEFAULT_INITIAL_CAPITAL_USDC = 1000.0;

    /**
     * Historique D1 demandé à {@code MarketDatasetEngine#getDatasetForAsset} : couvre tout l'historique BTC/ETH
     * (depuis 2017-08, ≈ 3300 j) pour un ATH/moon fidèles. D1 est un resampling de H1 : 4000 j × 24 = 96 000 H1,
     * sous la capacité du bucket ({@code Bucket.BASE_MAX_ITEMS} = 100 000 ; au-delà : troncature silencieuse).
     */
    public static final int D1_LOOKBACK_CANDLES = 4000;
    /** Fuseau explicite des jobs du bench (les autres jobs suivent le fuseau JVM). */
    public static final String SCHEDULER_ZONE = "UTC";
    public static final String PASS_2355_CRON_PROPERTY = "tradeio.rainbow-live.pass-2355-cron";
    public static final String PASS_0005_CRON_PROPERTY = "tradeio.rainbow-live.pass-0005-cron";
    /** Placeholders {@code @Scheduled} : défaut {@code -} (= {@code Scheduled.CRON_DISABLED}, job non enregistré). */
    public static final String PASS_2355_CRON = "${" + PASS_2355_CRON_PROPERTY + ":-}";
    public static final String PASS_0005_CRON = "${" + PASS_0005_CRON_PROPERTY + ":-}";

    /** Âge maximal d'une lecture du portefeuille réel au-delà duquel elle est {@code STALE} (durée ISO-8601). */
    public static final String READING_STALE_AFTER_PROPERTY = "tradeio.rainbow-live.reading-stale-after";
    public static final String DEFAULT_READING_STALE_AFTER = "PT30M";

    private static final RainbowAtrGlobals PINE = RainbowAtrGlobals.pineDefault();

    /** BTC : « Jeu global recommandé BTC » (centre des plateaux), moon OFF. */
    private static final RainbowAtrConfig BTC = RainbowAtrConfig.of(
            new RainbowAtrTuning(50, 21, 5.0, 0.5, 2.0, 3.0, 3.5,
                    ReentryMode.TRAILING_STOP, ReentryMode.FIXED_DELAY, 3.0, 5.0,
                    1, 15, 0.25, false, true, true),
            globals(30.0, 15.0, 0.25, 4.0, 1.0, 1.0, false, 0.0, false, 15.0, 100.0));

    /** ETH : optimum global plein échantillon, moon ON 50 %. */
    private static final RainbowAtrConfig ETH = RainbowAtrConfig.of(
            new RainbowAtrTuning(36, 21, 4.0, 0.5, 2.0, 3.0, 3.5,
                    ReentryMode.FIXED_DELAY, ReentryMode.FIXED_DELAY, 3.0, 5.0,
                    7, 3, 0.20, false, true, true),
            globals(60.0, 15.0, 0.0, 5.0, 3.0, 1.0, true, 50.0, false, 10.0, 50.0));

    /** PAXG : optimum global plein échantillon, moon ON 75 %. */
    private static final RainbowAtrConfig PAXG = RainbowAtrConfig.of(
            new RainbowAtrTuning(36, 28, 6.0, 0.5, 2.0, 2.0, 3.0,
                    ReentryMode.FIXED_DELAY, ReentryMode.TRAILING_STOP, 5.0, 2.0,
                    1, 10, 0.20, true, true, true),
            globals(15.0, 10.0, 1.0, 5.0, 2.0, 0.5, true, 75.0, false, 8.0, 50.0));

    private static final Map<String, RainbowAtrConfig> BY_ASSET = Map.of("BTC", BTC, "ETH", ETH, "PAXG", PAXG);

    private RainbowLiveDefaultPresets() {
    }

    /** Copie de la config par défaut de l'actif (jamais l'instance partagée). */
    public static RainbowAtrConfig configFor(String assetSymbol) {
        RainbowAtrConfig c = BY_ASSET.get(assetSymbol);
        if (c == null) {
            throw new IllegalArgumentException("Actif non autorisé : " + assetSymbol + " (autorisés : " + ASSETS + ")");
        }
        return RainbowAtrConfig.of(c.toTuning(), c.toGlobals());
    }

    private static RainbowAtrGlobals globals(double refBuy, double refSell, double buyMin, double buyMax,
                                             double sellMax, double sellMin, boolean moonOn, double moonReserve,
                                             boolean ratchet, double moonTrail, double moonStop) {
        return new RainbowAtrGlobals(true, refBuy, refSell, buyMin, buyMax, sellMax, sellMin,
                moonOn, moonReserve, ratchet, moonTrail, moonStop,
                PINE.multX2(), PINE.multX1(), PINE.multX0_5(), PINE.multTriggered(), PINE.baseAmount());
    }
}
