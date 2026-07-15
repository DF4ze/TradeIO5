package fr.ses10doigts.tradeIO5.service.tree.indicator.external.twelvedata;

/**
 * Résultat "propre" d'une consultation {@code GET /quote} Twelve Data (étude
 * "indicateurs-macro-externes" §14, items E/F) : prix courant + donnée de fraîcheur.
 * <p>
 * Contrairement à {@code /price} (utilisé pour les 6 paires forex de l'item E, cf.
 * {@link TwelveDataQuoteProvider#fetchPrices}), l'endpoint {@code /quote} porte un timestamp de
 * dernière transaction — nécessaire pour les indices actions (item F, SP500/NASDAQ) qui ne
 * tradent pas 24/7 : un consommateur en aval doit pouvoir distinguer une valeur fraîche d'une
 * clôture de vendredi soir reconduite telle quelle pendant tout le week-end.
 * <p>
 * <b>Champs non vérifiés contre un appel réel</b> (aucune clé Twelve Data disponible au moment de
 * cette implémentation) : {@code timestampEpochSeconds}/{@code marketOpen} sont mappés depuis les
 * champs {@code timestamp}/{@code is_market_open} documentés publiquement par Twelve Data pour
 * {@code /quote}, à confirmer/ajuster au premier appel réel avant de committer un consommateur qui
 * en dépendrait de façon critique.
 * <p>
 * {@code previousClose} (étude "nouvelles-opinions-indicateurs-non-branches" §2.1, ajouté pour
 * {@code DxyIndicator}) : mappé depuis le champ {@code previous_close}, documenté publiquement par
 * Twelve Data pour {@code /quote} sur toutes les classes d'instrument (forex compris) — <b>non plus
 * vérifié contre un appel réel que {@code timestampEpochSeconds}/{@code marketOpen}</b> ci-dessus,
 * même réserve. {@code DxyIndicator} traite ce champ en best-effort (jamais bloquant pour la valeur
 * DXY courante, déjà fonctionnelle via {@code /price}).
 */
public record TwelveDataQuote(
        double price,
        Long timestampEpochSeconds,
        Boolean marketOpen,
        Double previousClose
) {
}
