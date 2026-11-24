---
statut: decision
sujet: full-autonome-v1
cree_le: 2026-11-20
modifié_le: 2026-11-24
empreinte: 18e1ca2bd4
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
- **Niveaux et marge k éditables en admin (C5, 2026-11-23)** : les taux de la table ci-dessus sont éditables en admin par actif (table `asset_base_rate`, onglet « Taux de base », `V9` ; valeurs initiales = la table) ; la marge k reste dans `RainbowModulationControl` (`V6`), éditée dans le même onglet. Détail : [`../architecture/08d-rainbow-modulation.md`](../architecture/08d-rainbow-modulation.md).
- **Proposition d'onboarding : indicative, rien ne force l'utilisateur à la suivre.** Depuis C5 le défaut est le **taux du profil** (taux × cash alloué au lien) ; l'ancien calcul par autonomie (90 j) reste disponible si l'utilisateur saisit une durée, et sert de repli si son profil est absent / `CUSTOM`. Actif sans ligne propre : ligne BTC, drapeau « non calibré ». Pas de correction des utilisateurs existants.
- **Bandes de `StartingProfile` (20–50, 30–70, 40–90) conservées (décision de Clem, 2026-11-24).** Elles agissent comme planchers d'exposition et font dépasser le plafond de 13 à 18 pts sur BTC / ETH (Prudent, Équilibré, Dynamique ; Full, 0–100, est neutre) : [`../architecture/15h-mise-en-production-exposition.md`](../architecture/15h-mise-en-production-exposition.md).
- **Un chantier à la fois** (pas de parallélisme).
- **Preset de départ des nouveaux actifs = preset BTC (2026-11-23)**, réglé ensuite à la main par Clem. D1a : BTC et ETH transfèrent à égalité (90 % / 80 % des actifs), aucune règle de choix ne se démarque. Pas de réglage automatique de preset.

## Coupure au switch : option Expert (2026-11-23)
Positions de Clem après E1 / E2 : la coupure n'est **ni adoptée par défaut ni rejetée** (avantage dépendant du cycle sur ETH, gain surtout lié à la ré-entrée). Elle doit devenir une **option de paramétrage de l'Espace Expert** : activation, fraction vendue au switch Bull → Bear, taille de ré-entrée au switch Bear → Bull (en % du capital initial ; mesurée 0 / 5 / 10 / 20 %, 20 % = meilleure testée, optimum non encadré). Défaut = désactivée (comportement actuel inchangé). Mécanisme mesuré : [`../architecture/15k-coupure-switch.md`](../architecture/15k-coupure-switch.md). Cadre de l'Espace Expert : [`espace-expert.md`](espace-expert.md). Implémentation (live, rejeu, simulateur, onboarding) : **non faite**, à spécifier avec Clem (portée par lien ou par preset, entité et migration Flyway, affichage du risque : plafond de drawdown calibré sur le passé, pas une garantie).

## Reste à faire (ordre, corrigé 2026-11-20)
Le bench continue en **timing pur** (modulation ATH et de volume retirée, voir [`fil-rouge-dca-automatisation.md`](fil-rouge-dca-automatisation.md)).
1. **E0 (fait, 2026-11-20)** : `PISTE_FERMÉE` selon la règle de gain (BTC, N = 20, 50 % de gains de coupure nets > 0 pour 60 % exigés ; médiane = hasard ; baisse restante pire que le hasard) : [`../architecture/15j-diagnostic-switchs.md`](../architecture/15j-diagnostic-switchs.md), [`../etudes/etude-diagnostic-switchs.md`](../etudes/etude-diagnostic-switchs.md). E1 lancé sur arbitrage de Clem (priorité drawdown).
2. **E1 (fait, 2026-11-20)** : coupure 100 % du bag au switch Bull → Bear, ré-entrée 20 % du capital initial au switch Bear → Bull, contre preset pur re-mesuré à plafond de drawdown égal, avec et sans ré-entrée : BTC et ETH `COUPURE_UTILE` par la règle fixée mais fragile (ré-entrée = l'essentiel du gain BTC), PAXG non conclu (historique < 24 mois) : [`../architecture/15k-coupure-switch.md`](../architecture/15k-coupure-switch.md), [`../etudes/etude-coupure-switch.md`](../etudes/etude-coupure-switch.md). Suite à arbitrer par Clem (valider, ou bande de bruit / autre taille de ré-entrée / fenêtre courte PAXG / portefeuille à cash commun).
3. **E2 (fait, 2026-11-23)** : robustesse de la coupure (ré-entrée 0 / 5 / 10 / 20 %, bande de bruit sur 20 décalages, portefeuille BTC + ETH à cash commun) : `COUPURE_NON_ROBUSTE`, cause cycle dominant (ETH : le bloc 2024-2026 renverse le signe ; BTC robuste en Δ apparié mais gain médian sous le pur à −40 / −60 %) ; bruit `STABLE`, portefeuille `TIENT` (3/4 plafonds) ; gain croissant avec la taille, meilleure taille 20 % pour BTC et ETH (optimum non encadré) : [`../architecture/15k-coupure-switch.md`](../architecture/15k-coupure-switch.md), [`../etudes/etude-coupure-robustesse.md`](../etudes/etude-coupure-robustesse.md). Rien appliqué.
4. **D1a (fait, 2026-11-23)** : un preset existant joué tel quel bat le DCA à exposition égale sur la majorité des actifs jamais vus (`PRESET_GENERIQUE` : BTC 90 % et ETH 80 %, aucun choisi ; choix par volatilité non retenu ; PAXG provisoire) : [`../architecture/15l-transfert-presets.md`](../architecture/15l-transfert-presets.md), [`../etudes/etude-transfert-presets.md`](../etudes/etude-transfert-presets.md). Choix du preset de départ de D1b : à trancher par Clem.
5. **C5 (fait, 2026-11-23)** : taux par profil éditables en admin et proposition d'onboarding au taux du profil. **D1b** (pipeline nouvel actif) : **fait (2026-11-24)** : proposition calculée en asynchrone à la création d'un lien sans taux calibré (preset BTC + taux par profil, drapeaux) ; alarme Docteur « actif à valider » ; écran admin Valider / Refuser ; Valider écrit une ligne de taux dédiée (`V8`) et ne touche jamais un preset ou une Affectation déjà dédié (PAXG) ; sans action admin rien ne change : [`../architecture/15m-pipeline-nouvel-actif.md`](../architecture/15m-pipeline-nouvel-actif.md). Reste : activation du drapeau `tradeio.new-asset-proposal.enabled` (décision de Clem).
