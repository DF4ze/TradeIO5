---
statut: gap
sujet: actifs-dynamiques-lots
modifié_le: 2026-11-24
empreinte: 4d209838ca
---
# Actifs dynamiques — plan par lots

Décisions : [`../decisions/actifs-dynamiques.md`](../decisions/actifs-dynamiques.md). Ordre **A -> B -> D -> C** (UI user en dernier), déploiement lot par lot sur « go » explicite. Supprimer ce fichier quand tout est livré.

| Lot | Contenu | DB | Statut |
|---|---|---|---|
| **A** Socle | `V10` : `asset_blocklist`, réglage `min_volume_usd` (défaut 1 M$), colonne volume 24 h au catalogue, ligne `asset_base_rate` PAXG = taux BTC. Volume lu dans `InstrumentCatalog` (1 appel / exchange). `AssetEligibilityService` (stables, fiat, levier, earn par provider, liste noire, volume, route `PathFinder`, D1 ≥ 120 j, raison de refus). Aucun changement de comportement. | V10, recette | **fait** (code + tests ; V10 non appliquée en prod, recette et déploiement sur « go ») |
| **B** Moteur | Registre d'actifs dérivé, liens dynamiques, rang par ancienneté, garde « simulation seule », paire de bougies, suspension + alarme Docteur, API des actifs éligibles : [`../architecture/08c-rainbow-chemin-achat-wallet.md`](../architecture/08c-rainbow-chemin-achat-wallet.md). UI minimale (message 400, pastille « Suspendu ») : voir ci-dessous. | non | **fait** (code + tests ; aucun changement de schéma) |
| **D** Admin | Onglet « Actifs » (barre d'onglets sur 2 lignes), liste noire, seuil de volume, bouton « Recalculer », `new-asset-proposal.enabled=true` par défaut. | peut-être V11 | à faire |
| **C** Onboarding | Top 12 par volume + recherche + badges d'état, mobile + PC. | non | à faire |

Risques : encodages « earn » propres à chaque provider (Kraken xStocks non détectables) ; Binance sans `InstrumentListClient` (jamais éligible via le catalogue) ; formats des endpoints de volume Gate EU / Bitvavo / One Trading / Kraken à vérifier au 1er refresh ; volume inconnu (Revolut X) = éligible non vérifié, à confirmer ; plusieurs calculs D1b en file (un à la fois, 10 min max chacun) ; l'ordre du cash change pour les liens existants (ancienneté au lieu de BTC / ETH / PAXG).
