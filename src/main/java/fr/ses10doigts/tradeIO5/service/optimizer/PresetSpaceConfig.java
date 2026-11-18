package fr.ses10doigts.tradeIO5.service.optimizer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration (pas du code en dur) de l'espace L1 : pour chaque paramètre de {@link PresetParam}, libre ou gelé et un rayon relatif δ
 * (fraction de la valeur du preset en place, défaut {@value OptimizerDefaults#PRESET_DELTA}). Un paramètre libre varie sur
 * {@code [v·(1−δ), v·(1+δ)]}, borné par le domaine valide ; une valeur nulle n'a aucune marge relative et reste gelée.
 *
 * @param defaultDelta rayon relatif par défaut, dans ]0, 1[
 * @param gridSteps    crans par côté du rayon (résolution de la grille de recherche)
 * @param rules        règle par paramètre (clé = {@link PresetParam#key()}) ; un paramètre absent est libre au δ par défaut
 */
public record PresetSpaceConfig(double defaultDelta, int gridSteps, Map<String, ParamRule> rules) {

    /** @param delta rayon relatif propre au paramètre ({@code null} = défaut) */
    public record ParamRule(boolean free, Double delta) {

        public ParamRule {
            if (delta != null && !(delta > 0 && delta < 1)) {
                throw new IllegalArgumentException("δ dans ]0, 1[ requis, reçu " + delta);
            }
        }
    }

    public PresetSpaceConfig {
        if (!(defaultDelta > 0 && defaultDelta < 1)) {
            throw new IllegalArgumentException("δ par défaut dans ]0, 1[ requis, reçu " + defaultDelta);
        }
        if (gridSteps < 1) {
            throw new IllegalArgumentException("Au moins 1 cran par côté");
        }
        Map<String, ParamRule> given = rules == null ? Map.of() : rules;
        for (String name : given.keySet()) {
            if (PresetParam.byKey(name).isEmpty()) {
                throw new IllegalArgumentException("Paramètre inconnu ou non optimisable : " + name);
            }
        }
        Map<String, ParamRule> full = new LinkedHashMap<>();
        for (PresetParam p : PresetParam.values()) {
            full.put(p.key(), given.getOrDefault(p.key(), new ParamRule(true, null)));
        }
        rules = Collections.unmodifiableMap(full);
    }

    /** Libres : seuils ATR (×5), trailing stops, cooldown, fixedDelay, sellFraction ; δ = ±25 %. */
    public static PresetSpaceConfig defaults() {
        return new PresetSpaceConfig(OptimizerDefaults.PRESET_DELTA, OptimizerDefaults.PRESET_GRID_STEPS, Map.of());
    }

    public PresetSpaceConfig withDelta(double delta) {
        return new PresetSpaceConfig(delta, gridSteps, rules);
    }

    /** Même configuration avec la règle du paramètre {@code key} remplacée. */
    public PresetSpaceConfig with(String key, boolean free, Double delta) {
        Map<String, ParamRule> copy = new LinkedHashMap<>(rules);
        copy.put(key, new ParamRule(free, delta));
        return new PresetSpaceConfig(defaultDelta, gridSteps, copy);
    }

    public boolean isFree(PresetParam p) {
        return rules.get(p.key()).free();
    }

    public double deltaOf(PresetParam p) {
        Double own = rules.get(p.key()).delta();
        return own == null ? defaultDelta : own;
    }
}
