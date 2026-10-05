# Prompt d'analyse — Fil rouge : automatiser le DCA intelligent (Rainbow ATR)

## Contexte
Objectif n°1 : automatiser les achats/ventes du DCA intelligent Rainbow ATR. Lis, dans l'ordre :
1. `docs/README.md` puis `docs/known-gaps/rainbow-dca-chantiers-ouverts.md` (état de la chaîne, questions ouvertes) ;
2. `docs/architecture/04-market-data.md` (moteur Rainbow ATR), `08-rainbow-bench-grandeur-nature.md`, `09-trend.md` ;
3. `docs/calibration/calibration-rainbow-atr-v3-regime.md` (verdict du bench invalidé, pourquoi) ;
4. `docs/known-gaps/decision-to-order-gap.md` ;
5. `tools/pine/rainbow_dca_v4_atr_moon.pine` : **source de vérité** de la logique (presets `<ACTIF> Perso Bear/Bull` = jeux réglés à la main par Clem, BTC/ETH/PAXG Kraken) ; le Java doit s'y conformer.

## Décisions acquises
- Paramétrage par trend : **2 jeux Bull/Bear par actif, pas de Sideways**. « Un jeu global par actif » est abandonné.
- Le bench d'optimisation automatique n'est pas fiable (paysage non monotone, jeux dégénérés) : ce n'est pas la source des paramètres. Paramétrage automatique d'un actif quelconque = priorité moindre.
- Un testeur live tourne chaque jour sur le VPS (dry-run, wallet fictif USDC, résultats de tous les presets en base).
- `TrendAnalyzer` existe (UP/DOWN/RANGE, régression 7/14/30 + hystérésis) mais n'est branché ni au Rainbow ni au bench live. Le « double indicateur » global/local n'est pas implémenté.

## Ce que je veux
Une **étude de conception** (aucun code) du fil rouge, de bout en bout :
Trend → choix du jeu Bull/Bear → Rainbow ATR → sizing (+ exposition/risque) → wallet réel → ordre, avec dry-run avant toute exécution réelle.
1. Priorité n°1 : comment brancher la Trend sur les 2 jeux Bull/Bear (mapping de `RANGE`, rôle d'un éventuel double indicateur pour limiter le retard, transitions pendant un armement, validation sans bench fiable : rejeu pine, testeur live).
2. Séquencer les autres briques (wallet réel, exposition, risque macro, exécution) en étapes livrables.
3. Un point avec moi après chaque étape, comme pour le bench grandeur nature (`docs/prompts/prompt-roadmap-rainbow-atr-bench-grandeur-nature.md`).

## Méthode
- Pose d'abord les briques conceptuelles et **demande-moi les points ambigus** avant de proposer une architecture ; ne tranche pas à ma place.
- Sortie : roadmap dans `docs/prompts/` (trace de la réflexion, conservée), décisions actées dans `docs/`.
- Doc à jour sans historique : ce qui n'est plus valable est supprimé, toute modification d'architecture est documentée.
- Réponses courtes et techniques.
