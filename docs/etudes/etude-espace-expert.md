# Étude — Espace Expert : ce qui reste ouvert

Journal de conception, pas une référence d'architecture. L'Espace Expert (modèle `TrendPreset` / `RainbowPreset` / `AssetAffectation`, API, simulation, écrans) est implémenté : voir [`../architecture/13-espace-expert.md`](../architecture/13-espace-expert.md) et [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md). Plan d'origine : [`../prompts/prompt-roadmap-espace-expert.md`](../prompts/prompt-roadmap-espace-expert.md).

## Points ouverts

- **Découverte et création des actifs** (chantier à part, hors Espace Expert qui le consomme) : à l'ajout d'un provider, récupérer les actifs proposés avec nom, décimales et paires. `InstrumentCatalog` tient déjà les paires spot par exchange (Kraken, OKX, Bitvavo, Gate.eu, One Trading, Revolut X ; pas Binance) avec `tickSz` / `lotSz` / `minSz`. Décimales en mode automatique depuis `lotSz` (surcharge Admin, fallback sur un autre provider), icônes embarquées, noms en cache. Cycle de vie visé : découvert → données chargées (simulable) → stratégies complètes (exploitable) → lié à un binding. Étude dédiée à ouvrir.
- **Vue Admin des affectations** : quel preset est affecté à quel utilisateur.
- **Persistance des simulations** : résultats aujourd'hui en mémoire (TTL 30 min, perdus au redémarrage, non partagés entre instances) et pas de liste `GET /api/expert/simulations?type=&asset=` : un écran ne retrouve pas une simulation après rechargement.
- **Résultats suivant le zoom** : rejeu serveur par plage, ou conserver le sélecteur de plage explicite (état actuel).
- **Profondeur affichée** : `GET /api/expert/assets` expose `AssetProvider.maxHorizonDays` (limite H1) alors que la simulation juge la profondeur D1 (`MarketDataProviderLimits#maxD1Candles`) : à clarifier côté DTO.
- **Histogramme de taille de position** du pine : non repris (la taille du bag est dans le graphique).
- **Nettoyage des actifs explorés jamais affectés** : voir [`../known-gaps/nettoyages.md`](../known-gaps/nettoyages.md).
