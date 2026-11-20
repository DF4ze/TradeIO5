---
statut: journal
sujet: Roadmap — moteur d'optimisation et Full Autonome v1 (bilan des étapes, reste à faire)
modifié_le: 2026-11-20
empreinte: 291de0534a
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

Détail : [`15b`](../architecture/15b-carte-sensibilite-rainbow.md), [`15c`](../architecture/15c-optimisation-par-blocs.md), [`15d`](../architecture/15d-mesure-exposition-egale.md), [`15f`](../architecture/15f-politique-exposition-regime.md), [`15g`](../architecture/15g-calage-exposition-pire-cas.md), [`15h`](../architecture/15h-mise-en-production-exposition.md), [`15i`](../architecture/15i-reglage-recent.md).

## Reste à faire (ordre)
| # | Étape | Porte de sortie |
|---|---|---|
| C5 | Niveaux d'exposition par profil en code (taux de base, marge k) | Full Autonome v1 actif dans le live, plafond affiché comme cible |
| D1 | Pipeline d'accueil d'un nouvel actif (preset de départ, calage méthode C3, flag « provisoire », Binance USDT bench seulement) | un nouvel actif obtient preset + niveaux sans passage à la main |
| E0 → E1 | Coupure au switch Bull → Bear : diagnostic descriptif puis test à plafond égal ([`etude-pistes-timing-sorties.md`](etude-pistes-timing-sorties.md)) | décale la frontière gain / drawdown, sinon abandon |
| 6, 7 | Orchestrateur, mode Full Auto live | en pause (rien à promouvoir) |

## Questions ouvertes
- Pourquoi les améliorations manuelles de Clem ne sont pas reproduites par le moteur (critère « gel » sur-ajusté, exposition en plus, leviers couplés).
- Preset en place battu par le DCA fixe sur l'historique complet (exposition 10 % contre 70 %) : sans objet à exposition égale.
- D0b (contrôle « preset pur ») : non retenu, D0 étant déjà `NON_PERSISTANT`.
