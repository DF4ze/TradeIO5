---
statut: journal
sujet: Roadmap — moteur d'optimisation automatique (presets Rainbow par actif + politique de pilotage utilisateur)
modifié_le: 2026-11-16
empreinte: e832cafdfd
---
# Roadmap — moteur d'optimisation automatique

Cadre et décisions : [`etude-optimisation-mode-autonome.md`](etude-optimisation-mode-autonome.md) §11, [`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md). Simulateur existant : [`../architecture/13-espace-expert.md`](../architecture/13-espace-expert.md). Règles de code : [`../CODING_RULES.md`](../CODING_RULES.md).

## Méthode (chaque étape)
1. Prompt d'étape fourni dans la conversation (non conservé dans le repo).
2. Réalisation → tests verts → doc mise à jour dans la même session.
3. **Bilan de dérive** avant l'étape suivante : diff réel vs périmètre prévu, contrats modifiés, parité du rejeu inchangée, hypothèses invalidées, doc à jour. Le prompt suivant est rédigé d'après ce bilan, pas d'après ce plan.
4. Porte de sortie = critère chiffré ; échec ⇒ on s'arrête et on décide.

Règles transverses : tout en USDC ; rejeu = code du live (jamais de logique dupliquée) ; aucune donnée > J-1 dans une décision ; DB modifiée ⇒ Flyway `V<n>__*.sql` idempotent + recette (sinon rien) ; bench coûteux ⇒ demander avant de lancer ; crons désactivés par défaut (`-`).

## Étapes

| # | Étape | Livrable | Porte de sortie |
|---|---|---|---|
| 1 | **Hook de politique + politiques manuelles + test H0** | `ModulationPolicy` (interface pure : état du jour → `ModulationSettings`) appelée dans `MultiLinkReplayEngine` en plus de `settingsByDay` ; politique constante = comportement actuel ; réglages du jour journalisés dans le résultat ; clamp `RainbowModulationControl` conservé. État d'entrée : régime par lien, exposition, valeur / plus haut / drawdown du wallet, PnL. 2–3 politiques manuelles (bande par régime ; biais selon l'écart à l'exposition cible ; dé-risque à la bascule Bear), option `policy` dans `FULL`, comparaison à la constante (valeur finale, drawdown, exposition / timing / frais) sur BTC, ETH, PAXG | Rejeu identique avec politique constante (parité et tests existants inchangés) ; **verdict H0 chiffré** : la couche exposition pèse-t-elle plus que le réglage fin ? Décide de l'ordre 4 / 5 |
| 2 | **ATH détaché du preset (rejeu d'abord)** | Décisions ouvertes tranchées (portée globale / par actif, défaut, plafond, simulation) ; réglage utilisateur plafonné (mise max = k × base) appliqué dans le **rejeu** via `LinkSignalReplay` ; live inchangé | Plafond respecté (aucun achat > k × base) ; sans plafond = résultats actuels |
| 3 | **Évaluateur + moteur de recherche headless** | `ReplayScorer` pur (fenêtres ancrées pondérées : récente ×3, 24 mois ×2, historique ×1 ; valeur finale vs DCA fixe à capital égal ; vetos drawdown du profil / jeu dégénéré / capital dépassé ; modes frais simplifiés et complet) + `ParamSpace` (bornes, gel), tri par sensibilité, Sobol + successive halving, raffinement du plateau, bousculades (date ±30 j, provider, voisins), compteur d'essais, pool dédié ; sortie `OptimizationReport` JSON | Scorer déterministe (golden tests) ; retrouve un optimum connu sur fonction test ; coût en ms / run mesuré, budget de calcul de 4 et 5 estimé |
| 4 | **PolicyOptimizer (L2)** | Espace de ~10–14 paramètres de politique ; évaluation sur l'état du wallet utilisateur, contrainte de drawdown du profil ; validation hors échantillon | Bat DCA fixe **hors échantillon** ; plateau stable |
| 5 | **PresetOptimizer (L1, par actif)** | Rainbow Bull / Bear de chaque actif, Trend figée, dérive bornée δ autour du preset en place, challenger non appliqué ; walk-forward imbriqué (lancé seulement après accord) | Gain du processus (ré-optimiser tous les X j) vs preset statique démontré, ou abandon |
| 6 | **Orchestrateur + promotion auto-bornée** | Entités run / candidat / promotion (Flyway), cron désactivé par défaut, règles de promotion (gain > 2σ, δ, hystérésis), journal, retour arrière, page admin de visibilité / veto, alarme Docteur | Promotion simulée de bout en bout sur copie de prod (recette) |
| 7 | **Mode Full Auto (live)** | Politique appliquée automatiquement aux réglages du wallet utilisateur, via le même `SizingCore` ; ATH utilisateur plafonné en live ; bornes dures | Parité rejeu ↔ live ; validation de Clem avant activation |

## Dépendances
1 → 2 → 3 → 4 → 5 → 6 → 7. L'étape 2 ne bloque pas 3 (ATH figé tant que non détaché). Le verdict de l'étape 1 peut inverser 4 et 5.

## Ouvert (à trancher en route)
Cadence X ; rayon de dérive δ ; forme de la politique (règles par régime vs fonction continue) ; budget de calcul du walk-forward imbriqué ; décisions ATH (étape 2).
