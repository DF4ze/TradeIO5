package fr.ses10doigts.tradeIO5.service.optimizer;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Un jeu de paramètres : nom → valeur (catégoriel = indice). Immuable, trié par nom (clé et JSON déterministes). */
public record Candidate(Map<String, Double> values) {

    public Candidate {
        values = Collections.unmodifiableMap(new TreeMap<>(values));
    }

    public double get(String name) {
        Double v = values.get(name);
        if (v == null) {
            throw new IllegalArgumentException("Paramètre absent du candidat : " + name);
        }
        return v;
    }

    public Candidate with(String name, double value) {
        Map<String, Double> copy = new TreeMap<>(values);
        copy.put(name, value);
        return new Candidate(copy);
    }

    /** Clé stable (mémoïsation, départage des égalités). */
    public String key() {
        return values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("|"));
    }
}
