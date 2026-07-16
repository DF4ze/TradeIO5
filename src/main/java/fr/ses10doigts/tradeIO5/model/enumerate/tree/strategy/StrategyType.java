package fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy;

public enum StrategyType {

    /**
     * Strategy qui vote sur la direction du marché (score directionnel agrégé de façon additive
     * par {@code StrategyAggregator}).
     * <p>
     * Anciennement scindé en {@code ENTRY}/{@code EXIT}/{@code RISK} : {@code EXIT} n'a jamais été
     * porté par la moindre Strategy, et {@code RISK} était volontairement non implémenté (une
     * vraie taille de position / distance de stop a besoin du wallet et du profil de risque de
     * l'utilisateur — {@code WalletSnapshot}/{@code UserProfile} — qui n'existent que dans
     * {@code OpinionContext} ; {@link fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy#evaluate}
     * ne reçoit qu'un {@code MarketContext} et ne peut donc pas porter cette logique
     * correctement — le sizing/stop reste un sujet de la couche Decision/exécution, pas de la
     * couche Strategy). En pratique, aucun code ne distinguait jamais ces trois valeurs entre
     * elles ; seule l'opposition avec {@link #CONFIDENCE_MODULATOR} était (et reste) porteuse de
     * sens — d'où la fusion en une seule valeur.
     */
    DIRECTIONAL,

    /**
     * Strategy qui ne vote pas sur la direction du marché, mais qualifie la fiabilité d'un
     * mouvement déjà voté par les Strategies {@link #DIRECTIONAL} (ex : {@code MovementQualificationStrategy}
     * — cascade de liquidations/sur-effet-de-levier vs conviction spot ; {@code OrderFlowStrategy}
     * — flush confirmé vs épuisement). Les deux javadoc documentaient la même dette : "agrégée
     * comme une Strategy ENTRY classique... alors qu'elle joue plutôt un rôle de modulateur de
     * confiance... dette partagée, à résoudre pour les deux Strategy en même temps le jour où un
     * vrai mécanisme de modulation existe, pas en bricolant une solution ad hoc pour une seule."
     * <p>
     * {@link fr.ses10doigts.tradeIO5.service.tree.opinion.AbstractMarketOpinion#decide} sépare
     * désormais les {@code StrategyKey} par type : les {@code CONFIDENCE_MODULATOR} ne rejoignent
     * jamais la somme additive de {@code StrategyAggregator} (donc jamais l'échec all-or-nothing
     * d'une Strategy DIRECTIONAL mature à cause d'un flux de données récent/instable) ; leur score
     * est converti via {@code MarketOpinionHelper#computeConfidenceModulationFactor} en un facteur
     * multiplié uniquement à la confidence finale — jamais au score directionnel, même principe
     * que {@code computeSentimentShiftDampening}/{@code computeStalenessDampening}.
     */
    CONFIDENCE_MODULATOR
}