package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Catalogue des réglages Rainbow de l'interface Expert balayés par la carte de sensibilité ({@code X.RAINBOW_GROUPS}) : nom, domaine
 * permis (min..max), façon de lire / écrire la valeur d'un jeu. Gelés (hors étape) : périodes SMA / ATR, montant de base, Trend,
 * fenêtre d'analyse. Les domaines reprennent les règles de {@code ExpertPresetValidator} ; quand l'interface ne borne pas le haut
 * (facteurs, multiplicateurs), la borne haute est un plafond de balayage documenté dans {@code etude-sensibilite-rainbow.md}.
 */
public final class SensitivityParam {

    /** Nature d'un réglage : numérique continu / entier (balayage min..max) ou interrupteur / mode (une variante par option). */
    public enum Kind { CONTINUOUS, INTEGER, BOOLEAN, ENUM }

    /** Une valeur testable ; {@code inPlace} = celle du preset en place. */
    public record Value(Object raw, String label, boolean inPlace) {
    }

    /** Nombre de points de la grille numérique (valeur en place incluse, dédoublonnée). */
    public static final int GRID = 9;
    private static final double SAME = 1e-9;

    private final String key;
    private final String group;
    private final Kind kind;
    private final double min;
    private final double max;
    private final Function<RainbowAtrParamSet, Object> getter;
    private final boolean globals;

    private SensitivityParam(String key, String group, Kind kind, double min, double max, boolean globals,
                             Function<RainbowAtrParamSet, Object> getter) {
        this.key = key;
        this.group = group;
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.globals = globals;
        this.getter = getter;
    }

    private static SensitivityParam num(String key, String group, double min, double max, boolean globals,
                                        Function<RainbowAtrParamSet, Object> getter) {
        return new SensitivityParam(key, group, Kind.CONTINUOUS, min, max, globals, getter);
    }

    private static SensitivityParam integer(String key, String group, int min, int max, Function<RainbowAtrParamSet, Object> getter) {
        return new SensitivityParam(key, group, Kind.INTEGER, min, max, false, getter);
    }

    private static SensitivityParam flag(String key, String group, boolean globals, Function<RainbowAtrParamSet, Object> getter) {
        return new SensitivityParam(key, group, Kind.BOOLEAN, 0, 1, globals, getter);
    }

    private static SensitivityParam mode(String key, String group, Function<RainbowAtrParamSet, Object> getter) {
        return new SensitivityParam(key, group, Kind.ENUM, 0, 0, false, getter);
    }

    private static RainbowAtrTuning t(RainbowAtrParamSet s) {
        return s.tuning();
    }

    private static RainbowAtrGlobals g(RainbowAtrParamSet s) {
        return s.globals();
    }

    /** Tous les réglages de l'interface Expert (hors gelés), dans l'ordre de l'écran. */
    public static final List<SensitivityParam> ALL = List.of(
            num("atrMultDown2", "Bornes ATR", 0, 20, false, s -> t(s).atrMultDown2()),
            num("atrMultDown1", "Bornes ATR", 0, 20, false, s -> t(s).atrMultDown1()),
            num("atrMultUp1", "Bornes ATR", 0, 20, false, s -> t(s).atrMultUp1()),
            num("atrMultUp2", "Bornes ATR", 0, 20, false, s -> t(s).atrMultUp2()),
            num("atrMultUp3", "Bornes ATR", 0, 20, false, s -> t(s).atrMultUp3()),
            mode("buyReentryMode", "Ré-entrée et stops", s -> t(s).buyReentryMode()),
            mode("sellReentryMode", "Ré-entrée et stops", s -> t(s).sellReentryMode()),
            num("trailingStopBuyPct", "Ré-entrée et stops", 0, 100, false, s -> t(s).trailingStopBuyPct()),
            num("trailingStopSellPct", "Ré-entrée et stops", 0, 100, false, s -> t(s).trailingStopSellPct()),
            integer("cooldownDays", "Rythme et règles", 0, 365, s -> t(s).cooldownDays()),
            integer("fixedDelayDays", "Rythme et règles", 1, 365, s -> t(s).fixedDelayDays()),
            num("sellFraction", "Rythme et règles", 0.01, 1.0, false, s -> t(s).sellFraction()),
            flag("allowSellDuringCooldown", "Rythme et règles", false, s -> t(s).allowSellDuringCooldown()),
            flag("cooldownAfterSellOn", "Rythme et règles", false, s -> t(s).cooldownAfterSellOn()),
            flag("blockBuyAfterSellUntilDown2", "Rythme et règles", false, s -> t(s).blockBuyAfterSellUntilDown2()),
            flag("athOn", "Modulation ATH", true, s -> g(s).athOn()),
            num("athRefDdBuyPct", "Modulation ATH", 1, 100, true, s -> g(s).athRefDdBuyPct()),
            num("athRefDdSellPct", "Modulation ATH", 1, 100, true, s -> g(s).athRefDdSellPct()),
            num("athBuyMin", "Modulation ATH", 0, 5, true, s -> g(s).athBuyMin()),
            num("athBuyMax", "Modulation ATH", 0, 5, true, s -> g(s).athBuyMax()),
            num("athSellMax", "Modulation ATH", 0, 5, true, s -> g(s).athSellMax()),
            num("athSellMin", "Modulation ATH", 0, 5, true, s -> g(s).athSellMin()),
            num("multX2", "Zones et multiplicateurs", 0.1, 6, true, s -> g(s).multX2()),
            num("multX1", "Zones et multiplicateurs", 0.1, 6, true, s -> g(s).multX1()),
            num("multX0_5", "Zones et multiplicateurs", 0.1, 6, true, s -> g(s).multX0_5()),
            num("multTriggered", "Zones et multiplicateurs", 0.1, 6, true, s -> g(s).multTriggered()),
            flag("moonOn", "To the moon", true, s -> g(s).moonOn()),
            num("moonReservePct", "To the moon", 0, 100, true, s -> g(s).moonReservePct()),
            flag("moonReserveRatchet", "To the moon", true, s -> g(s).moonReserveRatchet()),
            num("moonTrailingStopPct", "To the moon", 0, 100, true, s -> g(s).moonTrailingStopPct()),
            num("moonStopSellPct", "To the moon", 0, 100, true, s -> g(s).moonStopSellPct()));

    public String key() {
        return key;
    }

    public String group() {
        return group;
    }

    public Kind kind() {
        return kind;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    /** Valeur en place dans {@code set}. */
    public Object of(RainbowAtrParamSet set) {
        return getter.apply(set);
    }

    /** Valeur numérique pour le classement / les libellés ({@code null} pour un mode). */
    public static String label(Object raw) {
        if (raw instanceof Double d) {
            return String.format(java.util.Locale.ROOT, "%.4g", d);
        }
        return String.valueOf(raw);
    }

    /**
     * Valeurs à tester, croissantes, valeur en place incluse : numérique = {@code GRID − 1} points régulièrement répartis de min à max
     * plus la valeur en place (dédoublonnée, entiers arrondis) ; interrupteur / mode = toutes les options.
     */
    public List<Value> values(RainbowAtrParamSet set) {
        Object cur = of(set);
        List<Value> out = new ArrayList<>();
        switch (kind) {
            case BOOLEAN -> {
                out.add(new Value(false, "false", Boolean.FALSE.equals(cur)));
                out.add(new Value(true, "true", Boolean.TRUE.equals(cur)));
            }
            case ENUM -> {
                for (ReentryMode m : ReentryMode.values()) {
                    out.add(new Value(m, m.name(), m == cur));
                }
            }
            default -> {
                double inPlace = ((Number) cur).doubleValue();
                List<Double> raw = new ArrayList<>();
                for (int i = 0; i < GRID - 1; i++) {
                    raw.add(min + (max - min) * i / (GRID - 2));
                }
                raw.add(inPlace);
                raw.sort(Double::compare);
                Double previous = null;
                for (double v : raw) {
                    double x = kind == Kind.INTEGER ? Math.rint(v) : v;
                    if (previous != null && Math.abs(x - previous) < SAME) {
                        continue;
                    }
                    previous = x;
                    boolean same = Math.abs(x - inPlace) < SAME;
                    out.add(new Value(kind == Kind.INTEGER ? (Object) (int) x : (Object) x, label(kind == Kind.INTEGER ? (Object) (int) x : (Object) x), same));
                }
                if (out.stream().noneMatch(Value::inPlace)) {
                    // l'entier arrondi a pu fusionner avec un voisin : la valeur en place reste toujours testée
                    Object v = kind == Kind.INTEGER ? (Object) (int) Math.rint(inPlace) : (Object) inPlace;
                    out.add(new Value(v, label(v), true));
                    out.sort((a, b) -> Double.compare(((Number) a.raw()).doubleValue(), ((Number) b.raw()).doubleValue()));
                }
            }
        }
        return out;
    }

    /** Copie du jeu avec cette valeur (le reste du jeu est inchangé). */
    public RainbowAtrParamSet apply(RainbowAtrParamSet set, Object value) {
        if (globals) {
            return new RainbowAtrParamSet(set.name(), set.tuning(), set.globals().toBuilder().set(key, value).build());
        }
        return new RainbowAtrParamSet(set.name(), set.tuning().toBuilder().set(key, value).build(), set.globals());
    }
}
