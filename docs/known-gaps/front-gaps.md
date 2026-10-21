# Front — écarts restants

Vérifié le : 2026-10-21.

| Écart | État |
|---|---|
| Écran **Données** (`asset_provider`, `candleSource`) | bloqué : aucun endpoint ; `AssetInitializer` écrase les valeurs au démarrage (à résoudre avant) |
| Page **Santé** (état des sources, jobs) | aucun back |
| Page **Stratégies** | reste dans `/user` (espace Expert) |
| Admin en pages séparées | non fait : `/admin` garde ses 6 onglets, habillés charte |
| Parité `/user` | `/user` conservé (presets, stratégies) ; pas retiré tant que Stratégies n'est pas migrée |
| Dépôts / retraits | non suivis : PnL portefeuille « hors dépôts/retraits » |
