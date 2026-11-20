---
statut: archivé
sujet: Optimiseur de presets L1 par actif (étape 5) — walk-forward semestriel BTC / ETH / PAXG, protocole V6 : entrées, résultats par actif, budget des cadences
vérifié_le: 2026-11-17
remplacé_par: Claude
archivé_le: 2026-11-20
raison: Étude close : décision reprise dans decisions/full-autonome-v1.md
modifié_le: 2026-11-20
empreinte: 48d8616a57
---
# Étude — optimisation des presets L1 (étape 5, 2026-11-17)

> ⚠ **ARCHIVÉ le 2026-11-20** — Étude close : décision reprise dans decisions/full-autonome-v1.md. Remplacé par `Claude`. Ne jamais lire comme référence de l'état courant.

Journal daté : mécanisme dans [`../architecture/15a-optimiseur-presets-l1.md`](../../architecture/15a-optimiseur-presets-l1.md), cadre dans [`roadmap-moteur-optimisation.md`](../../etudes/roadmap-moteur-optimisation.md) (étape 5), leçons V6 dans [`etude-calibrage-politique-l2.md`](etude-calibrage-politique-l2.md).

## Entrées
Un seul run (`-Doptimizer.manual=preset`, base locale copie de prod), graine 20261116, profil BALANCED (bande d'exposition 30–70 %, plafond de drawdown −25 %), capital 10 000 USDC, référence DCA fixe autonomie 360 j, δ ±25 % (tous paramètres libérables libres), 6 plis semestriels (fins 2023-05-15 … 2025-11-15, test 6 mois), un actif à la fois. 43 s (pool 4). Rejeu reproductible (test).

**Multiplicité** : 12 recherches, **3 352 essais**, 28 rejeux hors échantillon, une seule configuration d'espace, une graine, aucune variante.

## Résultats par actif
| Actif | Statut | Plis gagnés | Entraînement vetoé | Médiane Δ OOS | Verdict |
|---|---|---|---|---|---|
| BTC | OPTIMISABLE | 1/6 | 3/6 (50 %) | 0 pt (plis vetoés comptés nuls) | GARDER_PRESET |
| ETH | OPTIMISABLE | 0/6 | 5/6 (83 %) | 0 pt | GARDER_PRESET |
| PAXG | NON_OPTIMISABLE | — | — | — | GARDER_PRESET (`HISTORIQUE_INSUFFISANT`) |

- **BTC** : plis 2024-11 / 2025-05 / 2025-11 évalués : Δ OOS −3,29 / +0,93 / −11,71 pts, chaque fois challenger `DEGENERATE` en test. Plis 2023-05 / 2023-11 : preset **et** challengers vetoés `DRAWDOWN` ; pli 2024-05 : preset vetoé `DRAWDOWN` alors qu'un challenger non vetoé existe (écarté par la règle : départ vetoé ⇒ `GARDER_PRESET`).
- **ETH** : seul pli évalué 2024-11 (Δ +0,02 pt, veto `DRAWDOWN` bloquant en test) ; les 5 autres : preset vetoé `DRAWDOWN` à l'entraînement.
- **PAXG** (Kraken) : 23 bougies disponibles / 180 requises sur la fenêtre 12 m ⇒ non optimisable, signalé.
- Paramètres gelés par la règle δ relatif : seuils ATR à 0 (Down1, Up1, Up2 selon le set), `fixedDelayDays` / `cooldownDays` sans cran entier dans ±25 %.

## Lecture
Le comparateur lui-même (preset en place) est vetoé au drawdown sur 3/6 plis BTC et 5/6 plis ETH : la part de plis inexploitables dépasse largement 30 % (porte de sortie). Parmi les 4 plis réellement évalués (BTC ×3, ETH ×1), le challenger gagne 1 fois, sans écart exploitable. Pas de signal que ré-optimiser le preset par actif batte le preset statique sur ce protocole.

## Cadences 30 / 90 / 180 j
**Non lancées** (accord demandé). Budget estimé : 2 actifs optimisables, 36 fins d'entraînement distinctes, 72 recherches, ~20 100 essais (279 / recherche), ~4 s par recherche (pool 4), ~324 rejeux OOS, **~4,3 min**.

## Verdict (provisoire)
Walk-forward : **GARDER_PRESET** sur BTC et ETH, PAXG non optimisable. Porte de sortie non atteinte (plis à entraînement vetoé 50 % / 83 % ≥ 30 %, majorité de plis non gagnée). Reste la comparaison des cadences ; un résultat nul est un résultat valide, aucune variante explorée.
