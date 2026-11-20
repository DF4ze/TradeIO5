package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Espace de recherche CONJOINT d'un jeu (Bull ou Bear) pour l'étape D0 : tous les réglages de l'interface Expert
 * ({@link SensitivityParam#ALL}, soit les périodes SMA / ATR, Trend et {@code baseAmount} exclus), leviers de taille d'achat inclus
 * ({@code multX2}, {@code multTriggered}, {@code athBuyMin}, {@code athBuyMax}, {@code athRefDdBuyPct}). Interrupteurs et modes sont
 * catégoriels, les numériques continus ou entiers sur les plages de l'étape A.
 * <p>
 * Ordre des bornes : chaque tirage est TRIÉ avant d'être joué ({@code atrMultDown1 ≤ atrMultDown2}, {@code atrMultUp1 ≤ atrMultUp2 ≤
 * atrMultUp3}, {@code athBuyMin ≤ athBuyMax}). Les dimensions sont donc indépendantes pour le moteur de recherche (les zones X1 / X0,5,
 * dont les bornes valent 0 dans les presets, s'activent en bougeant plusieurs bornes ensemble) et chaque jeu joué respecte les règles de
 * {@code ExpertPresetValidator}. Une valeur laissée à celle du départ redonne EXACTEMENT la valeur du preset (aucun arrondi de grille).
 */
public final class RecentTuneSpace {

    /** Pas de la grille numérique : plage / 200 ({@code ±1 cran} du plateau). Les entiers ont un pas de 1. */
    static final int GRID_STEPS = 200;
    private static final double SAME = 1e-12;

    /** Chaînes de bornes à ordonner : chaque chaîne est triée par ordre croissant sur la suite de noms. */
    private static final List<List<String>> CHAINS = List.of(List.of("atrMultDown1", "atrMultDown2"),
            List.of("atrMultUp1", "atrMultUp2", "atrMultUp3"), List.of("athBuyMin", "athBuyMax"));

    private RecentTuneSpace() {
    }

    /** Espace dont le point de départ est le jeu {@code start} (valeurs ramenées à la grille ; {@link #resolve} les restitue exactes). */
    public static ParamSpace build(RainbowAtrParamSet start) {
        List<ParamSpace.Param> params = new ArrayList<>();
        for (SensitivityParam p : SensitivityParam.ALL) {
            Object cur = p.of(start);
            switch (p.kind()) {
                case BOOLEAN -> params.add(ParamSpace.Param.categorical(p.key(), List.of("false", "true"), Boolean.TRUE.equals(cur) ? 1 : 0));
                case ENUM -> params.add(ParamSpace.Param.categorical(p.key(),
                        Arrays.stream(ReentryMode.values()).map(Enum::name).toList(), ((ReentryMode) cur).ordinal()));
                case INTEGER -> params.add(ParamSpace.Param.integer(p.key(), (int) p.min(), (int) p.max(), 1, ((Number) cur).intValue()));
                default -> params.add(ParamSpace.Param.continuous(p.key(), p.min(), p.max(), (p.max() - p.min()) / GRID_STEPS,
                        ((Number) cur).doubleValue()));
            }
        }
        return new ParamSpace(params);
    }

    /**
     * Jeu joué pour un candidat : valeur du preset là où le candidat est resté au départ, valeur tirée ailleurs, puis tri des chaînes de
     * bornes. {@code null} si le jeu reste invalide pour l'Expert (ne devrait pas arriver une fois trié).
     */
    public static RainbowAtrParamSet resolve(ParamSpace space, Candidate c, RainbowAtrParamSet base, int analysisWindowMonths) {
        RainbowAtrParamSet out = apply(space, c, base);
        return SensitivityMap.violation(out, analysisWindowMonths) == null ? out : null;
    }

    static RainbowAtrParamSet apply(ParamSpace space, Candidate c, RainbowAtrParamSet base) {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (SensitivityParam p : SensitivityParam.ALL) {
            ParamSpace.Param sp = space.param(p.key());
            double v = c.get(p.key());
            boolean atStart = Math.abs(v - sp.start()) < SAME;
            Object value = switch (p.kind()) {
                case BOOLEAN -> atStart ? p.of(base) : (Object) (v >= 0.5);
                case ENUM -> atStart ? p.of(base) : (Object) ReentryMode.values()[(int) Math.rint(v)];
                case INTEGER -> atStart ? p.of(base) : (Object) (int) Math.rint(v);
                default -> atStart ? p.of(base) : (Object) v;
            };
            values.put(p.key(), value);
        }
        for (List<String> chain : CHAINS) {
            List<Double> sorted = chain.stream().map(k -> ((Number) values.get(k)).doubleValue()).sorted().toList();
            for (int i = 0; i < chain.size(); i++) {
                values.put(chain.get(i), sorted.get(i));
            }
        }
        RainbowAtrParamSet out = base;
        for (SensitivityParam p : SensitivityParam.ALL) {
            Object value = values.get(p.key());
            if (!sameValue(p.of(base), value)) {
                out = p.apply(out, value);
            }
        }
        return out;
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Math.abs(x.doubleValue() - y.doubleValue()) < SAME;
        }
        return a.equals(b);
    }

    /** Position d'un réglage d'un jeu dans l'espace : valeur numérique, ou indice de l'option pour un interrupteur / mode. */
    static double positionOf(ParamSpace space, String key, RainbowAtrParamSet set) {
        SensitivityParam p = SensitivityParam.ALL.stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
        Object v = p.of(set);
        return switch (p.kind()) {
            case BOOLEAN -> Boolean.TRUE.equals(v) ? 1.0 : 0.0;
            case ENUM -> ((ReentryMode) v).ordinal();
            default -> ((Number) v).doubleValue();
        };
    }

    /** Déplacement d'un réglage numérique, en fraction de sa plage (NaN pour un interrupteur / mode). */
    static double move(String key, RainbowAtrParamSet before, RainbowAtrParamSet after) {
        SensitivityParam p = SensitivityParam.ALL.stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
        if (p.kind() == SensitivityParam.Kind.BOOLEAN || p.kind() == SensitivityParam.Kind.ENUM) {
            return Double.NaN;
        }
        return (((Number) p.of(after)).doubleValue() - ((Number) p.of(before)).doubleValue()) / (p.max() - p.min());
    }

    /** Réglages qui diffèrent entre deux jeux : clé → {@code [avant, après]} (libellés), dans l'ordre de l'écran. */
    public static java.util.Map<String, String[]> diff(RainbowAtrParamSet before, RainbowAtrParamSet after) {
        java.util.Map<String, String[]> out = new java.util.LinkedHashMap<>();
        for (SensitivityParam p : SensitivityParam.ALL) {
            Object a = p.of(before);
            Object b = p.of(after);
            if (!sameValue(a, b)) {
                out.put(p.key(), new String[]{SensitivityParam.label(a), SensitivityParam.label(b)});
            }
        }
        return out;
    }

    /**
     * Zones X1 / X0,5 activées par les bornes : X1 = zone entre {@code SMA − ATR × Down1} et {@code SMA + ATR × Up1} (non vide dès qu'une
     * de ces deux bornes est > 0), X0,5 = zone entre Up1 et Up2 (non vide dès que Up2 > Up1).
     */
    public static boolean zoneX1(RainbowAtrParamSet s) {
        return s.tuning().atrMultDown1() > SAME || s.tuning().atrMultUp1() > SAME;
    }

    public static boolean zoneX05(RainbowAtrParamSet s) {
        return s.tuning().atrMultUp2() > s.tuning().atrMultUp1() + SAME;
    }
}
