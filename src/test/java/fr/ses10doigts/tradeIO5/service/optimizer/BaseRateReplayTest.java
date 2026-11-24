package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationStudy.Window;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Line;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-régression métier de C5 : la proposition d'onboarding (taux de profil par défaut x cash alloué, arrondi comme le live) rejouée par
 * {@link WalletEvaluator} (un lien, bande neutre, mêmes fenêtres de 24 mois + historique que C3 / C4) redonne le pire drawdown de la table
 * de calage à +-1 pt et reste au plafond du profil ; mêmes chiffres à un autre capital (invariance).
 */
@DisplayName("C5 : proposition au taux de profil rejouée (pire drawdown vs table de calage vs plafond)")
class BaseRateReplayTest {

    private static final LocalDate END = PresetOptimizerTestSupport.END;
    private static final double CASH_BTC = 2_484.0;
    private static final double CASH_ETH = 2_425.0;
    private static final double TOLERANCE_PTS = 1.0;
    private static final RainbowModulationControl DEFAULTS = RainbowModulationControl.defaults(Instant.EPOCH, null);

    /** Pire drawdown de la table de calage (decisions/full-autonome-v1.md, etude C4), k = 1,1, bande neutre. */
    private static final Map<String, Double> TABLE = Map.of(
            "BTC/PRUDENT", -13.72, "BTC/BALANCED", -22.70, "BTC/DYNAMIC", -36.38, "BTC/FULL", -54.52,
            "ETH/PRUDENT", -13.64, "ETH/BALANCED", -22.73, "ETH/DYNAMIC", -36.37, "ETH/FULL", -54.55);

    private static ScorerInputs btc;
    private static ScorerInputs eth;

    @BeforeAll
    static void load() throws IOException {
        var l1 = PresetOptimizerTestSupport.link("BTC", PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE));
        var l2 = PresetOptimizerTestSupport.link("ETH", PresetOptimizerTestSupport.csv("ETH", Integer.MAX_VALUE));
        l2 = new ScorerInputs.AssetInput(new LiveSlot(2L, 2L, "ETH", 1L, 2, 100.0, null), l2.template(), "TEST", l2.meanMultiplier(), l2.referenceBase());
        btc = ScorerInputs.of(List.of(l1), CASH_BTC, END, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
        eth = ScorerInputs.of(List.of(l2), CASH_ETH, END, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
    }

    /**
     * Pire drawdown (fenetres de 24 mois + historique complet jusqu'a {@code in.endDate()}) de la proposition au taux du profil sur le
     * capital de {@code in} ; reutilise par le runner manuel {@code base-rate-replay} (donnees reelles de la base).
     */
    static double worstDd(ScorerInputs in, String asset, StartingProfile profile) {
        BigDecimal rate = DEFAULTS.baseRate(profile, asset).value();
        double base = RainbowLiveOnboardingService.profileBaseAmount(in.cash0(), rate).doubleValue();
        double worst = 0.0;
        ExposureCalibrationConfig cal = ExposureCalibrationConfig.defaults(in.endDate());
        for (Window w : ExposureCalibrationStudy.windowsFor(in, cal)) {
            Line l = new WalletEvaluator(in.withEndDate(w.to()), w.from()).line(new double[]{base});
            if (l != null) {
                worst = Math.min(worst, l.maxDrawdownPct());
            }
        }
        Line full = new WalletEvaluator(in, null).line(new double[]{base});
        assertNotNull(full);
        return Math.min(worst, full.maxDrawdownPct());
    }

    /**
     * BTC : les jeux de test du repo ({@code RainbowAtrPresets}, Trend par defaut) reproduisent la table de calage. ETH : le calage a ete
     * mesure sur le preset ETH reel de la base (hors repo), que les jeux de test ne reproduisent pas (ecart d'un facteur ~2) : les 8 couples
     * sont verifies sur les donnees reelles par le runner manuel {@code base-rate-replay} ({@code OptimizerManualRunnerTest}).
     */
    @Test
    @DisplayName("BTC x 4 profils : pire drawdown rejoue = table de calage a +-1 pt et <= plafond du profil (ETH : runner manuel base-rate-replay)")
    void proposalReplayMatchesCalibrationTable() {
        StringBuilder report = new StringBuilder();
        for (String asset : List.of("BTC")) {
            ScorerInputs in = asset.equals("BTC") ? btc : eth;
            for (StartingProfile p : List.of(StartingProfile.PRUDENT, StartingProfile.BALANCED, StartingProfile.DYNAMIC, StartingProfile.FULL)) {
                double dd = worstDd(in, asset, p);
                double expected = TABLE.get(asset + "/" + p.name());
                double cap = DEFAULTS.drawdownCaps().get(p).doubleValue();
                report.append(String.format("%s %-8s rejoue %.2f table %.2f plafond %.0f%n", asset, p, dd, expected, cap));
                assertEquals(expected, dd, TOLERANCE_PTS, asset + " " + p + " : ecart a la table de calage");
                assertTrue(dd >= cap, asset + " " + p + " : " + dd + " sous le plafond " + cap);
            }
        }
        System.out.println(report);
    }

    @Test
    @DisplayName("Invariance au capital : meme taux a 1 000 et 10 000 USDC => meme pire drawdown (+-1 pt, arrondi de la base compris)")
    void invarianceToCapital() {
        for (String asset : List.of("BTC", "ETH")) {
            ScorerInputs in = asset.equals("BTC") ? btc : eth;
            for (StartingProfile p : List.of(StartingProfile.PRUDENT, StartingProfile.FULL)) {
                double small = worstDd(in.withCash0(1_000.0), asset, p);
                double large = worstDd(in.withCash0(10_000.0), asset, p);
                assertEquals(small, large, TOLERANCE_PTS, asset + " " + p);
            }
        }
    }

    @Test
    @DisplayName("Table par defaut = decisions/full-autonome-v1.md ; actif sans ligne => repli BTC non calibre")
    void defaultsAndFallback() {
        assertEquals(0, new BigDecimal("0.0002774").compareTo(DEFAULTS.baseRate(StartingProfile.PRUDENT, "BTC").value()));
        assertEquals(0, new BigDecimal("0.001533").compareTo(DEFAULTS.baseRate(StartingProfile.FULL, "ETH").value()));
        for (StartingProfile p : List.of(StartingProfile.PRUDENT, StartingProfile.BALANCED, StartingProfile.DYNAMIC, StartingProfile.FULL)) {
            var paxg = DEFAULTS.baseRate(p, "PAXG");
            assertEquals(0, DEFAULTS.baseRate(p, ModulationDefaults.FALLBACK_RATE_ASSET).value().compareTo(paxg.value()));
            assertTrue(!paxg.calibrated());
            assertTrue(DEFAULTS.baseRate(p, "BTC").calibrated() && DEFAULTS.baseRate(p, "ETH").calibrated());
        }
    }
}
