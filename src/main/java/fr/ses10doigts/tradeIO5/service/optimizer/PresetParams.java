package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Pont entre le preset en place (jeux Bear / Bull) et l'espace de recherche : construit le {@link ParamSpace} autour du preset (rayon
 * relatif δ, domaine dur, entiers arrondis), applique un {@link Candidate} à une copie des jeux et vérifie l'ordre des seuils ATR. Les
 * noms de paramètres sont {@code <jeu>.<paramètre>} ({@code bear.atrMultDown2}). Seul endroit qui connaît ces noms.
 */
public final class PresetParams {

    private static final double GRID_EPSILON = 1e-9;

    private PresetParams() {
    }

    /** Un paramètre du preset : libre (borné) ou gelé, avec la raison du gel. */
    public record ParamInfo(String name, boolean frozen, String reason, double inPlace, Double min, Double max, Double step) {
    }

    /** Espace de recherche (paramètres libres seulement : un paramètre gelé n'y figure pas) et état de chaque paramètre du preset. */
    public record Built(ParamSpace space, List<ParamInfo> info) {
    }

    /** Étiquette d'un jeu : {@code bear} / {@code bull} pour la paire standard, sinon {@code set<i>}. */
    static String role(int index, int count) {
        if (count == 2) {
            return index == RainbowAtrPresets.BEAR ? "bear" : "bull";
        }
        return "set" + index;
    }

    static String name(int index, int count, PresetParam p) {
        return role(index, count) + "." + p.key();
    }

    public static Built build(RainbowAtrParamSet[] sets, PresetSpaceConfig cfg) {
        List<ParamSpace.Param> params = new ArrayList<>();
        List<ParamInfo> info = new ArrayList<>();
        for (int i = 0; i < sets.length; i++) {
            for (PresetParam p : PresetParam.values()) {
                String name = name(i, sets.length, p);
                double v = p.of(sets[i].tuning());
                if (!cfg.isFree(p)) {
                    info.add(frozen(name, v, "gelé par la configuration"));
                    continue;
                }
                double radius = Math.abs(v) * cfg.deltaOf(p);
                if (!(radius > 0)) {
                    info.add(frozen(name, v, "valeur nulle : un rayon relatif n'a aucune marge"));
                    continue;
                }
                double step = p.integer() ? Math.max(1, Math.rint(radius / cfg.gridSteps())) : radius / cfg.gridSteps();
                int below = steps(Math.min(radius, v - p.domainMin()), step);
                int above = steps(Math.min(radius, p.domainMax() - v), step);
                if (below + above == 0) {
                    info.add(frozen(name, v, "aucun cran entier dans le rayon relatif ou bornes dures du domaine"));
                    continue;
                }
                double min = v - below * step;
                double max = v + above * step;
                params.add(p.integer() ? ParamSpace.Param.integer(name, (int) min, (int) max, (int) step, (int) v)
                        : ParamSpace.Param.continuous(name, min, max, step, v));
                info.add(new ParamInfo(name, false, null, v, min, max, step));
            }
        }
        return new Built(new ParamSpace(params), List.copyOf(info));
    }

    private static ParamInfo frozen(String name, double v, String reason) {
        return new ParamInfo(name, true, reason, v, null, null, null);
    }

    /** Nombre entier de crans qui tiennent dans {@code room} (jamais négatif). */
    private static int steps(double room, double step) {
        return Math.max(0, (int) Math.floor(room / step + GRID_EPSILON));
    }

    /** Copie des jeux avec les valeurs du candidat ; tout ce qui n'est pas dans le candidat reste celui du preset en place. */
    public static RainbowAtrParamSet[] apply(RainbowAtrParamSet[] sets, Candidate candidate) {
        RainbowAtrParamSet[] out = new RainbowAtrParamSet[sets.length];
        for (int i = 0; i < sets.length; i++) {
            RainbowAtrTuning.Builder builder = sets[i].tuning().toBuilder();
            boolean touched = false;
            for (PresetParam p : PresetParam.values()) {
                Double v = candidate.values().get(name(i, sets.length, p));
                if (v != null) {
                    builder.set(p.key(), p.integer() ? (Object) Integer.valueOf((int) Math.rint(v)) : (Object) v);
                    touched = true;
                }
            }
            out[i] = touched ? new RainbowAtrParamSet(sets[i].name(), builder.build(), sets[i].globals()) : sets[i];
        }
        return out;
    }

    /** Ordre des seuils ATR et domaine (règles de {@link RainbowAtrTuning#violations()}) ; vide = valide. Vérifié avant tout rejeu. */
    public static Function<Candidate, List<String>> validator(RainbowAtrParamSet[] sets) {
        return candidate -> {
            List<String> reasons = new ArrayList<>();
            RainbowAtrParamSet[] applied = apply(sets, candidate);
            for (int i = 0; i < applied.length; i++) {
                String role = role(i, applied.length);
                applied[i].tuning().violations().forEach((field, message) -> reasons.add(role + "." + field + " : " + message));
            }
            return reasons;
        };
    }

    /** Valeurs en place de tous les paramètres numériques (libres ou non), dans l'ordre jeu puis paramètre. */
    public static Map<String, Double> values(RainbowAtrParamSet[] sets) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < sets.length; i++) {
            for (PresetParam p : PresetParam.values()) {
                out.put(name(i, sets.length, p), p.of(sets[i].tuning()));
            }
        }
        return out;
    }
}
