package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Paramètres Rainbow DCA ATR en colonnes typées (1 par paramètre) : mapping 1:1 avec
 * {@link RainbowAtrTuning} (17) + {@link RainbowAtrGlobals} (12 + 4 multiplicateurs + base).
 * Réutilisé par {@link RainbowLivePreset} (config courante) et {@link RainbowLiveRun} (snapshot).
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowAtrConfig {

    // --- Tuning
    private int smaPeriod;
    private int atrPeriod;
    private double atrMultDown2;
    private double atrMultDown1;
    private double atrMultUp1;
    private double atrMultUp2;
    private double atrMultUp3;
    @Enumerated(EnumType.STRING)
    private ReentryMode buyReentryMode;
    @Enumerated(EnumType.STRING)
    private ReentryMode sellReentryMode;
    private double trailingStopBuyPct;
    private double trailingStopSellPct;
    private int cooldownDays;
    private int fixedDelayDays;
    private double sellFraction;
    private boolean allowSellDuringCooldown;
    private boolean cooldownAfterSellOn;
    private boolean blockBuyAfterSellUntilDown2;

    // --- Globals
    private boolean athOn;
    private double athRefDdBuyPct;
    private double athRefDdSellPct;
    private double athBuyMin;
    private double athBuyMax;
    private double athSellMax;
    private double athSellMin;
    private boolean moonOn;
    private double moonReservePct;
    private boolean moonReserveRatchet;
    private double moonTrailingStopPct;
    private double moonStopSellPct;
    private double multX2;
    private double multX1;
    private double multX0_5;
    private double multTriggered;
    private double baseAmount;

    public static RainbowAtrConfig of(RainbowAtrTuning t, RainbowAtrGlobals g) {
        return RainbowAtrConfig.builder()
                .smaPeriod(t.smaPeriod()).atrPeriod(t.atrPeriod())
                .atrMultDown2(t.atrMultDown2()).atrMultDown1(t.atrMultDown1())
                .atrMultUp1(t.atrMultUp1()).atrMultUp2(t.atrMultUp2()).atrMultUp3(t.atrMultUp3())
                .buyReentryMode(t.buyReentryMode()).sellReentryMode(t.sellReentryMode())
                .trailingStopBuyPct(t.trailingStopBuyPct()).trailingStopSellPct(t.trailingStopSellPct())
                .cooldownDays(t.cooldownDays()).fixedDelayDays(t.fixedDelayDays()).sellFraction(t.sellFraction())
                .allowSellDuringCooldown(t.allowSellDuringCooldown()).cooldownAfterSellOn(t.cooldownAfterSellOn())
                .blockBuyAfterSellUntilDown2(t.blockBuyAfterSellUntilDown2())
                .athOn(g.athOn()).athRefDdBuyPct(g.athRefDdBuyPct()).athRefDdSellPct(g.athRefDdSellPct())
                .athBuyMin(g.athBuyMin()).athBuyMax(g.athBuyMax()).athSellMax(g.athSellMax()).athSellMin(g.athSellMin())
                .moonOn(g.moonOn()).moonReservePct(g.moonReservePct()).moonReserveRatchet(g.moonReserveRatchet())
                .moonTrailingStopPct(g.moonTrailingStopPct()).moonStopSellPct(g.moonStopSellPct())
                .multX2(g.multX2()).multX1(g.multX1()).multX0_5(g.multX0_5()).multTriggered(g.multTriggered())
                .baseAmount(g.baseAmount())
                .build();
    }

    public RainbowAtrTuning toTuning() {
        return new RainbowAtrTuning(smaPeriod, atrPeriod, atrMultDown2, atrMultDown1, atrMultUp1, atrMultUp2, atrMultUp3,
                buyReentryMode, sellReentryMode, trailingStopBuyPct, trailingStopSellPct,
                cooldownDays, fixedDelayDays, sellFraction,
                allowSellDuringCooldown, cooldownAfterSellOn, blockBuyAfterSellUntilDown2);
    }

    public RainbowAtrGlobals toGlobals() {
        return new RainbowAtrGlobals(athOn, athRefDdBuyPct, athRefDdSellPct, athBuyMin, athBuyMax, athSellMax, athSellMin,
                moonOn, moonReservePct, moonReserveRatchet, moonTrailingStopPct, moonStopSellPct,
                multX2, multX1, multX0_5, multTriggered, baseAmount);
    }

    /** Empreinte stable de la config (SHA-256 tronqué) : deux configs identiques ⇒ même hash. */
    public String hash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((toTuning() + "|" + toGlobals()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Préfixe des paramètres globaux dans {@link #diff}. */
    public static final String GLOBALS_PREFIX = "globals.";

    /**
     * Noms des paramètres qui diffèrent de {@code other} : composants de {@link RainbowAtrTuning} tels quels
     * (ex. {@code smaPeriod}), composants de {@link RainbowAtrGlobals} préfixés (ex. {@code globals.athRefDdBuyPct}),
     * dans l'ordre de déclaration des records. Liste vide ⇒ configs identiques.
     */
    public List<String> diff(RainbowAtrConfig other) {
        List<String> changed = new ArrayList<>();
        diffRecords(toTuning(), other.toTuning(), "", changed);
        diffRecords(toGlobals(), other.toGlobals(), GLOBALS_PREFIX, changed);
        return changed;
    }

    private static void diffRecords(Record a, Record b, String prefix, List<String> out) {
        try {
            for (RecordComponent rc : a.getClass().getRecordComponents()) {
                if (!Objects.equals(rc.getAccessor().invoke(a), rc.getAccessor().invoke(b))) {
                    out.add(prefix + rc.getName());
                }
            }
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }
}
