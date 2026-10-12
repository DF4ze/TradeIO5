# Graphique (GUI) — idées et besoins à traiter

Section de collecte, comme [`../securite/remarques-audit.md`](../securite/remarques-audit.md) : on y note au fil des chantiers tout besoin d'interface ou de charte graphique ; ils seront traités ensemble quand le back sera bouclé (ordre voulu, cf. [`../known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md) §5b). État décrit : **actuel** (un besoin traité est supprimé, pas historisé).

Dernière mise à jour : 2026-10-12.

## 1. Régler les `asset_provider` depuis la GUI

Aujourd'hui les lignes `asset_provider` (actif × exchange : `providerSymbol`, `priority`, `enabled`, `maxHorizonDays`) sont semées par `AssetInitializer` (upsert idempotent au démarrage, `enabled` non écrasé) et créées à la volée pour la source d'un lien ([`../architecture/04-market-data.md`](../architecture/04-market-data.md#chemin-d1-natif-rainbow-getdailydatasetforasset)). Aucun écran ne permet de les voir ni de les modifier : tout passe par SQL ou par un redémarrage.

Besoin : pouvoir régler ses `asset_provider` depuis la GUI, au minimum :
- lister par actif les exchanges, leur paire (`providerSymbol`), leur priorité (ordre des défauts), l'état `enabled` (kill switch) et `maxHorizonDays` ;
- activer / désactiver un exchange pour un actif, changer l'ordre des défauts, corriger une paire ;
- repérer les lignes **créées à la volée** (source d'un lien absente des semis) pour les valider ou les supprimer ;
- voir quel exchange a réellement servi les candles du signal d'un lien et si c'était une **substitution** (`candleSource` / `candleSourceSubstituted` du snapshot du lien, aujourd'hui stockés mais non affichés).

À cadrer : réservé à l'admin (une ligne modifiée change les candles de tous les utilisateurs) ; l'`AssetInitializer` resynchronise `providerSymbol` / `priority` / `maxHorizonDays` à chaque démarrage et écraserait une édition GUI de ces champs (seul `enabled` est préservé) — à trancher avant de construire l'écran.
