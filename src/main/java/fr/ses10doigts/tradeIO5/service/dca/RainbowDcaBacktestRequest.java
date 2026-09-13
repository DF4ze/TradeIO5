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

    /** Rebond/repli (%) depuis l'extrême atteint pendant l'armement qui déclenche en mode {@link ReentryMode#TRAILING_STOP}. */
    @Builder.Default
    private final BigDecimal trailingStopPercent = new BigDecimal("5");

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
}
