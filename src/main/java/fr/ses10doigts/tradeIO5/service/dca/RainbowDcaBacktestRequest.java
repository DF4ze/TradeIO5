package fr.ses10doigts.tradeIO5.service.dca;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Paramètres d'un backtest DCA Rainbow (V0, résolution D1 pure — cf.
 * docs/prompts/prompt-implementation-dca-rainbow-v0.md). BTC uniquement pour ce lot : pas de
 * config DB multi-actif (cf. docs/CODING_RULES.md — "ne pas coder ce dont on n'a pas besoin"),
 * les bornes % par défaut ci-dessous sont celles calibrées pour BTC et doivent être passées
 * explicitement pour tout autre actif.
 * <p>
 * Les bornes % et les multiplicateurs sont tous paramétrables (cf. bench de calibration,
 * docs/calibration/calibration-dca-rainbow-bounds-multipliers.md) : les valeurs {@code @Builder.Default}
 * ci-dessous restent les défauts V0 d'origine, validés par 581 tests et le runner manuel BTC.
 * <p>
 * {@code buyReentryMode}/{@code sellReentryMode} ({@link ReentryMode}, 2026-09-13, cf.
 * docs/etudes/etude-dca-tool-mcp.md §12) : permettent de comparer plusieurs méthodes de sortie des
 * états ARMÉ achat/vente (trailing stop, franchissement pur, délai fixe). Défauts = comportement
 * V0 d'origine exact (ne change rien tant qu'on ne les passe pas explicitement) :
 * {@code TRAILING_STOP} côté achat, {@code IMMEDIATE} côté vente.
 */
@Builder
@Data
public class RainbowDcaBacktestRequest {

    private final String symbol;
    private final LocalDate startDate;
    private final LocalDate endDate;

    /** Période (en jours) de la SMA de référence. */
    @Builder.Default
    private final int smaPeriod = 20;

    @Builder.Default
    private final BigDecimal percDown2 = new BigDecimal("-7");
    @Builder.Default
    private final BigDecimal percDown1 = new BigDecimal("-3.5");
    @Builder.Default
    private final BigDecimal percUp1 = new BigDecimal("3.5");
    @Builder.Default
    private final BigDecimal percUp2 = new BigDecimal("7");
    @Builder.Default
    private final BigDecimal percUp3 = new BigDecimal("12.5");

    /** Multiplicateur zone X2 (achat direct, cadencé). */
    @Builder.Default
    private final BigDecimal multX2 = new BigDecimal("2");
    /** Multiplicateur zone X1 (achat direct, cadencé). */
    @Builder.Default
    private final BigDecimal multX1 = BigDecimal.ONE;
    /** Multiplicateur zone X0_5 (achat direct, cadencé). */
    @Builder.Default
    private final BigDecimal multX0_5 = new BigDecimal("0.5");
    /** Multiplicateur appliqué à l'achat ARMÉ/DÉCLENCHÉ (zone EXTREME_BAS + trailing stop). */
    @Builder.Default
    private final BigDecimal multTriggered = new BigDecimal("3");

    /** Rebond (%) depuis le plus bas atteint pendant l'armement ACHAT qui déclenche en mode {@link ReentryMode#TRAILING_STOP}. */
    @Builder.Default
    private final BigDecimal trailingStopBuyPercent = new BigDecimal("5");

    /** Repli (%) depuis le plus haut atteint pendant l'armement VENTE qui déclenche en mode {@link ReentryMode#TRAILING_STOP}. */
    @Builder.Default
    private final BigDecimal trailingStopSellPercent = new BigDecimal("5");

    /** Fraction de la position vendue au déclenchement vente (1/4 par défaut). */
    @Builder.Default
    private final BigDecimal sellFraction = new BigDecimal("0.25");

    /** Jours pendant lesquels tout achat est désactivé après une vente (cooldown). */
    @Builder.Default
    private final int cooldownDays = 3;

    /**
     * Cadence des achats "zone intermédiaire", en jours (1 = quotidien). N'affecte PAS le
     * mécanisme ARMÉ/DÉCLENCHÉ (achat déclenché / vente), qui réagit au prix indépendamment du calendrier.
     */
    @Builder.Default
    private final int cadenceDays = 1;

    /** Montant de base investi à chaque achat "zone intermédiaire" x1 (avant multiplicateur). */
    private final BigDecimal baseAmount;

    /** Méthode de sortie de l'état ARMÉ achat (sous percdown2). Défaut = comportement V0 d'origine. */
    @Builder.Default
    private final ReentryMode buyReentryMode = ReentryMode.TRAILING_STOP;

    /** Méthode de sortie de l'état ARMÉ vente (au-dessus de percup3). Défaut = comportement V0 d'origine. */
    @Builder.Default
    private final ReentryMode sellReentryMode = ReentryMode.IMMEDIATE;

    /** Jours d'armement avant déclenchement automatique en mode {@link ReentryMode#FIXED_DELAY} (achat ou vente). */
    @Builder.Default
    private final int fixedDelayDays = 10;

    /**
     * Mode de calcul des bornes de zone (2026-09-27, demande Clem) : {@link BoundsMode#PERCENT}
     * (defaut, comportement V0 d'origine, inchange) ou {@link BoundsMode#ATR} (bornes adaptees a
     * la volatilite realisee plutot qu'a un %fixe de la SMA). Les champs percDown2..percUp3
     * restent lus en mode PERCENT ; les champs atrMultDown2..atrMultUp3 et atrPeriod en mode ATR.
     */
    @Builder.Default
    private final BoundsMode boundsMode = BoundsMode.PERCENT;

    /** Periode (en jours) de l'ATR de reference, utilise seulement si boundsMode = ATR. */
    @Builder.Default
    private final int atrPeriod = 14;

    @Builder.Default
    private final BigDecimal atrMultDown2 = new BigDecimal("3");
    @Builder.Default
    private final BigDecimal atrMultDown1 = new BigDecimal("1.5");
    @Builder.Default
    private final BigDecimal atrMultUp1 = new BigDecimal("1");
    @Builder.Default
    private final BigDecimal atrMultUp2 = new BigDecimal("2.5");
    @Builder.Default
    private final BigDecimal atrMultUp3 = new BigDecimal("5");

    /** Raccourci du builder : applique la même valeur de trailing stop aux côtés achat et vente. */
    public static class RainbowDcaBacktestRequestBuilder {
        public RainbowDcaBacktestRequestBuilder trailingStopPercent(BigDecimal percent) {
            return trailingStopBuyPercent(percent).trailingStopSellPercent(percent);
        }
    }
}
