package fr.ses10doigts.tradeIO5.service.optimizer;

/**
 * Source de la configuration de l'espace de paramètres L1. Aujourd'hui les défauts ; remplaçable plus tard par une lecture depuis
 * l'interface (sélection des paramètres libres et du rayon δ par l'utilisateur / l'admin) sans toucher à l'optimiseur.
 */
@FunctionalInterface
public interface PresetSpaceSource {

    PresetSpaceConfig current();

    static PresetSpaceSource defaults() {
        return PresetSpaceConfig::defaults;
    }
}
