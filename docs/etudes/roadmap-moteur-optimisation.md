---
statut: journal
sujet: Roadmap — moteur d'optimisation et Full Autonome v1 (bilan des étapes, reste à faire)
modifié_le: 2026-11-23
empreinte: fa07696cd7
---
# Roadmap — moteur d'optimisation / Full Autonome v1

État courant et décisions : [`../decisions/full-autonome-v1.md`](../decisions/full-autonome-v1.md). Mécanismes du code : [`../architecture/15-optimiseur.md`](../architecture/15-optimiseur.md) et 15a–15i. Règles de code : [`../CODING_RULES.md`](../CODING_RULES.md).

## Méthode (chaque étape)
Prompt d'étape (non conservé) → réalisation, tests verts, doc à jour dans la même session → **bilan de dérive** avant l'étape suivante → verdict chiffré fixé avant lecture, un run, une configuration. Bench > 10 min : annoncé, accord demandé. Un chantier à la fois. Tout en USDC.

## Bilan (études archivées dans `../archive/etudes/`)
| Étape | Verdict | Retenu |
|---|---|---|
| 4 : politique L2 auto | NUL | abandon |
| 5 : preset L1 auto | NUL (`GARDER_PRESET` BTC / ETH) | code seul, rien à promouvoir |
| A : sensibilité BTC | gains sur une seule fenêtre | information seulement |
| B : réglage par blocs | NUL | le preset n'est pas améliorable par réglage |
| B' : exposition égale | `TIMING_UTILE` | le signal Rainbow est bon ; gain = exposition |
| C1 : frontière | `AVANTAGE_PERSISTANT` | le plafond de drawdown fixe l'exposition |
| C2 : politique par régime | NUL | exposition constante par profil |
| C3 : pire cas | k = 1,1 ; niveaux par profil | table de calage |
| C4 : mise en prod | onboarding 90 j et bandes de profil dépassent le plafond | bande neutre ; onboarding indicatif |
| D0 : réglage récent | `NON_PERSISTANT` (BTC) | pas de ré-optimisation périodique |
| E0 : diagnostic des switchs Bull → Bear | `PISTE_FERMÉE` (BTC, N = 20, cause a : 50 % de gains nets > 0) ; baisse restante pire que le hasard, gain de coupure = hasard | E1 lancé sur arbitrage de Clem (ci-dessous) |

Détail : [`15b`](../architecture/15b-carte-sensibilite-rainbow.md), [`15c`](../architecture/15c-optimisation-par-blocs.md), [`15d`](../architecture/15d-mesure-exposition-egale.md), [`15f`](../architecture/15f-politique-exposition-regime.md), [`15g`](../architecture/15g-calage-exposition-pire-cas.md), [`15h`](../architecture/15h-mise-en-production-exposition.md), [`15i`](../architecture/15i-reglage-recent.md), [`15j`](../architecture/15j-diagnostic-switchs.md) ([résultats E0](etude-diagnostic-switchs.md)), [`15k`](../architecture/15k-coupure-switch.md) ([résultats E1](etude-coupure-switch.md), [résultats E2](etude-coupure-robustesse.md)).

## Reste à faire (ordre)
Bench en timing pur (sans modulation ATH ni de volume).
| # | Étape | Porte de sortie |
|---|---|---|
| E1 | Coupure du bag au switch Bear, ré-entrée (20 % du cash) au switch Bull, à plafond de drawdown égal ; comparateur « preset pur » (références re-mesurées sans modulation) | Fait (vente 100 %, ré-entrée 20 % du capital initial ou 0 %) : BTC et ETH `COUPURE_UTILE` mais fragile (BTC : gain tiré par 2018-2020, 2022-2024 négatif ; sans ré-entrée gain médian sous le pur), PAXG non conclu (historique < 24 mois) ; [`etude-coupure-switch.md`](etude-coupure-switch.md) |
| E2 | Robustesse de la coupure (E1) : taille de ré-entrée 5 / 10 %, bande de bruit (20 décalages), portefeuille BTC + ETH à cash commun, ablation d'un bloc | Fait : `COUPURE_NON_ROBUSTE`, cause cycle dominant (ETH fragile : le bloc 2024-2026 renverse le signe ; BTC robuste en Δ apparié mais gain médian sous le pur) ; bruit `STABLE`, portefeuille `TIENT` (3/4 plafonds) ; gain croissant avec la taille (meilleure : 20 %, optimum non encadré) ; [`etude-coupure-robustesse.md`](etude-coupure-robustesse.md) ; décision de Clem : option de l'Espace Expert, désactivée par défaut ([`full-autonome-v1.md`](../decisions/full-autonome-v1.md)) |
| C5 | Niveaux d'exposition par profil, éditables en admin, proposition d'onboarding au taux du profil | Fait (2026-11-23) : 8 taux + k en admin (`V6`), proposition indicative `taux × cash alloué` (repli BTC « non calibré »), autonomie conservée sur saisie ; bandes de `StartingProfile` inchangées (dépassement 13 à 18 pts, arbitrage de Clem) ; [`full-autonome-v1.md`](../decisions/full-autonome-v1.md) |
| D1a | Transfert des presets existants (BTC, ETH, PAXG) sur 11 actifs jamais vus, DCA à exposition égale, aucun réglage | Fait : `PRESET_GENERIQUE`, BTC (90 %) et ETH (80 %) tous deux `GENERIQUE`, aucun choisi ; choix par volatilité ne tient pas ; PAXG provisoire ; [`etude-transfert-presets.md`](etude-transfert-presets.md) |
| D1b | Pipeline nouvel actif (Binance USDT bench seulement, flag « provisoire ») | fait (2026-11-24 : lot 1 calcul de la proposition, lot 2 alerte Docteur + validation admin) ; reste recette V6 + V7 + V8 |
| 6, 7 | Orchestrateur, mode Full Auto live | en pause |

## Questions ouvertes
- Pourquoi les améliorations manuelles de Clem ne sont pas reproduites par le moteur (critère « gel » sur-ajusté, exposition en plus, leviers couplés).
- Preset en place battu par le DCA fixe sur l'historique complet (exposition 10 % contre 70 %) : sans objet à exposition égale.
- D0b (contrôle « preset pur ») : non retenu, D0 étant déjà `NON_PERSISTANT`.
