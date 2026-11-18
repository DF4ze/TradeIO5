package fr.ses10doigts.tradeIO5.service.optimizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Espace de paramètres typés (continu, entier, catégoriel) avec bornes, « cran » (pas de voisinage / de bousculade), valeur de
 * départ et état gelé. Toute valeur produite est alignée sur la grille {@code min + k × step} : « ±1 cran » est donc exact.
 * Sérialisable en JSON (records).
 */
public record ParamSpace(List<Param> params) {

    public enum Kind { CONTINUOUS, INTEGER, CATEGORICAL }

    private static final double SNAP_SCALE = 1e9;

    /**
     * @param step    cran (> 0) ; catégoriel : 1
     * @param start   valeur de départ (sur la grille) ; gelé = valeur conservée
     * @param choices libellés des catégories (indice = valeur) ; vide sinon
     */
    public record Param(String name, Kind kind, double min, double max, double step, double start, boolean frozen,
                        List<String> choices) {

        public Param {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Nom de paramètre obligatoire");
            }
            choices = choices == null ? List.of() : List.copyOf(choices);
            if (kind == Kind.CATEGORICAL) {
                if (choices.size() < 2) {
                    throw new IllegalArgumentException(name + " : au moins 2 catégories");
                }
                min = 0;
                max = choices.size() - 1;
                step = 1;
            }
            if (!(max > min) || !(step > 0)) {
                throw new IllegalArgumentException(name + " : bornes (min < max) et cran > 0 obligatoires");
            }
            if (kind == Kind.INTEGER && (step != Math.rint(step) || min != Math.rint(min))) {
                throw new IllegalArgumentException(name + " : entier => min et cran entiers");
            }
            start = snap(min, max, step, start);
        }

        public static Param continuous(String name, double min, double max, double step, double start) {
            return new Param(name, Kind.CONTINUOUS, min, max, step, start, false, List.of());
        }

        public static Param integer(String name, int min, int max, int step, int start) {
            return new Param(name, Kind.INTEGER, min, max, step, start, false, List.of());
        }

        public static Param categorical(String name, List<String> choices, int start) {
            return new Param(name, Kind.CATEGORICAL, 0, choices.size() - 1, 1, start, false, choices);
        }

        /** Valeur ramenée dans les bornes et sur la grille. */
        public double snap(double v) {
            return snap(min, max, step, v);
        }

        /** Valeur à {@code n} crans de {@code v} (négatif : vers le bas), bornée. */
        public double shifted(double v, int n) {
            return snap(v + n * step);
        }

        /** Nombre de valeurs de la grille. */
        public int gridSize() {
            return (int) Math.floor((max - min) / step + 1e-9) + 1;
        }

        public Param withStart(double v) {
            return new Param(name, kind, min, max, step, v, frozen, choices);
        }

        public Param asFrozen() {
            return new Param(name, kind, min, max, step, start, true, choices);
        }

        private static double snap(double min, double max, double step, double v) {
            double clamped = Math.clamp(v, min, max);
            double snapped = min + Math.rint((clamped - min) / step) * step;
            double bounded = Math.min(snapped, min + Math.floor((max - min) / step + 1e-9) * step);
            return Math.rint(bounded * SNAP_SCALE) / SNAP_SCALE;
        }
    }

    public ParamSpace {
        Set<String> names = new LinkedHashSet<>();
        for (Param p : params) {
            if (!names.add(p.name())) {
                throw new IllegalArgumentException("Paramètre en double : " + p.name());
            }
        }
        params = List.copyOf(params);
    }

    public Param param(String name) {
        return params.stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Paramètre inconnu : " + name));
    }

    public List<Param> free() {
        return params.stream().filter(p -> !p.frozen()).toList();
    }

    /** Candidat de départ : valeur de départ de chaque paramètre. */
    public Candidate startCandidate() {
        Map<String, Double> values = new LinkedHashMap<>();
        params.forEach(p -> values.put(p.name(), p.start()));
        return new Candidate(values);
    }

    /** Même espace dont les paramètres nommés sont gelés (à leur valeur de départ). */
    public ParamSpace freezing(Collection<String> names) {
        return new ParamSpace(params.stream().map(p -> names.contains(p.name()) ? p.asFrozen() : p).toList());
    }

    /**
     * Candidat dont les paramètres libres prennent {@code unit[i] ∈ [0, 1[} mis à l'échelle de leurs bornes (catégoriel : indice
     * tiré uniformément) ; les paramètres gelés gardent la valeur de {@code base}.
     */
    public Candidate fromUnit(double[] unit, Candidate base) {
        return fromUnit(unit, base, null);
    }

    /** Idem, mais chaque paramètre libre reste à {@code radiusSteps} crans de {@code base} (raffinement local) quand le rayon est donné. */
    public Candidate fromUnit(double[] unit, Candidate base, Integer radiusSteps) {
        List<Param> free = free();
        if (unit.length != free.size()) {
            throw new IllegalArgumentException("Dimension " + unit.length + " pour " + free.size() + " paramètres libres");
        }
        Map<String, Double> values = new LinkedHashMap<>(base.values());
        for (int i = 0; i < free.size(); i++) {
            Param p = free.get(i);
            double lo = p.min();
            double hi = p.max();
            if (radiusSteps != null) {
                lo = Math.max(lo, base.get(p.name()) - radiusSteps * p.step());
                hi = Math.min(hi, base.get(p.name()) + radiusSteps * p.step());
            }
            double raw = p.kind() == Kind.CATEGORICAL ? lo + Math.floor(unit[i] * (hi - lo + 1)) : lo + unit[i] * (hi - lo);
            values.put(p.name(), p.snap(raw));
        }
        return new Candidate(values);
    }

    /** Voisins à ±1 cran sur chaque paramètre libre (bord : un seul voisin), sans doublon, dans l'ordre des paramètres. */
    public List<Candidate> neighbors(Candidate c) {
        List<Candidate> out = new ArrayList<>();
        for (Param p : free()) {
            double v = c.get(p.name());
            for (int dir : new int[]{-1, 1}) {
                double n = p.shifted(v, dir);
                if (n != v) {
                    out.add(c.with(p.name(), n));
                }
            }
        }
        return out;
    }
}
