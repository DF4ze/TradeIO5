---
statut: decision
sujet: full-autonome-v1
cree_le: 2026-11-20
modifié_le: 2026-11-20
empreinte: 2a130947a6
---
# Full Autonome v1 — état courant (tranché)

> Positions de Clem. Historique des mesures : roadmap [`../etudes/roadmap-moteur-optimisation.md`](../etudes/roadmap-moteur-optimisation.md). Décisions antérieures : [`fil-rouge-dca-automatisation.md`](fil-rouge-dca-automatisation.md).

## Cible v1
**Exposition constante par profil, calée sur le pire cas avec marge, + preset Rainbow en place (timing).** Ni optimiseur de preset, ni politique dynamique, ni ré-optimisation périodique : tous mesurés, tous nuls.

## Acquis (mesurés, BTC / ETH, 2017-2026)
- Le preset en place bat un DCA fixe **à exposition égale** (BTC +4 pts, ETH +8,5 pts, médiane de blocs 24 mois). Le niveau de gain est fixé par la part engagée.
- Gain et drawdown ~proportionnels à l'exposition jusqu'à ~30 % ; saturation du cash au-delà. Le **plafond de drawdown du profil fixe l'exposition** (levier unique : montant de base).
- Réglage du preset : non améliorable de façon validée (par blocs : nul ; réglé sur 24 mois, joué 3 / 6 mois : `NON_PERSISTANT`, le « gain » est de l'exposition en plus). Politique d'exposition par régime : nulle à plafond égal.
- Le plafond est une **cible calibrée sur le passé, pas une garantie** (un seul grand bear par actif, 2021-22) : l'affichage produit doit le dire.

## Table de calage de production (bande neutre, marge k = 1,1 BTC et ETH)
Taux de base = montant de base par jour / cash alloué au lien (invariant au capital dans le modèle ; vérifié 8/8). Marge k = paramètre de configuration.

| Profil | Plafond | BTC taux/j | ETH taux/j | Gain médian attendu (BTC / ETH) |
|---|---|---|---|---|
| Prudent | −15 % | 2,774e-4 | 3,804e-4 | +11 / +14 % |
| Équilibré | −25 % | 4,736e-4 | 6,349e-4 | +19 / +23 % |
| Dynamique | −40 % | 7,712e-4 | 1,019e-3 | +31 / +37 % |
| Full | −60 % | 1,423e-3 | 1,533e-3 | +57 / +48 % |

Autonomies équivalentes : 559 à 3 090 j, hors des bornes 7–365 j de l'onboarding. Wallet BTC + ETH 50 / 50 conforme à ces taux en bande neutre (marge 4 à 15 pts). PAXG : pas de calage (historique insuffisant), traité par le pipeline nouvel actif.

## Décisions (2026-11-20)
- **Bande d'exposition neutre 0–100 % en test / bench** ; le montant de base est le seul levier. Les bandes de `StartingProfile` (20–50, 30–70, 40–90) agissent comme planchers d'exposition et dépassent le plafond : ne pas les utiliser pour le calage.
- **Niveaux et marge k en code** (constantes annotées), à rendre éditables en admin (Flyway) **si la technique est adoptée**.
- **Proposition d'onboarding à 90 j : indicative, rien ne force l'utilisateur à la suivre.** Pas de correction des utilisateurs existants ; l'écart au plafond (+9 à +43 pts) n'est pas traité en v1.
- **Un chantier à la fois** (pas de parallélisme).

## Reste à faire (ordre)
1. **C5** : niveaux par profil en code (taux de base + marge k), Full Autonome v1 lisible dans le live.
2. **D1** : pipeline d'accueil d'un nouvel actif (preset de départ parmi BTC / ETH / PAXG à exposition égale, calage d'exposition méthode C3, flag « provisoire » sans blocage ; Binance USDT = bench seulement).
3. **E0 → E1** (piste coupure au switch Bull → Bear) : [`../etudes/etude-pistes-timing-sorties.md`](../etudes/etude-pistes-timing-sorties.md). Après C5 et D1.
