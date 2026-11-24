---
statut: courant
sujet: Transfert des presets existants sur des actifs jamais vus (étape D1a) — mécanisme, stockage bench étiqueté, règles de verdict, runner transfer
vérifié_le: 2026-11-23
modifié_le: 2026-11-23
empreinte: 2c31b920fc
---
# Transfert des presets (`TransferStudy`, `TransferConfig`, `TransferReport`, `BenchCandleStore`)

Outil de mesure hors prod : rien appliqué ni persisté, aucun preset modifié, live et rejeu inchangés (aucun code du moteur touché). Question : un des 3 presets existants (BTC, ETH, PAXG), joué tel quel avec la Trend par défaut du live, bat-il le DCA fixe à exposition égale sur un actif qui n'a servi à régler aucun preset ? Constats chiffrés : [`../etudes/etude-transfert-presets.md`](../etudes/etude-transfert-presets.md). Cadre : [`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md) (« Nouveaux actifs », « Presets purs »), [`../decisions/full-autonome-v1.md`](../decisions/full-autonome-v1.md).

## Paire = (preset source, actif cible)
- **Presets** : jeux Bear / Bull live de BTC, ETH, PAXG lus par `ReplayInputsLoader` (DB), joués tels quels, **modulation ATH désactivée** (`SwitchCutStudy.withoutAth`, « preset pur » d'[E1](15k-coupure-switch.md)).
- **Trend** : celle que le live applique à un actif sans réglage dédié = « Trend de base » (`RainbowLiveTrendConfigs.defaults`) : `TrendMixCalculator.Params.defaults()` + `RangeMapping.KEEP_PREVIOUS`. Identique pour les 12 actifs, aucun réglage par actif.
- **Montant de base** : taux de base du lien source (montant de base du lien / capital de son wallet, invariant au capital) × capital de la paire (2 484, identique pour les 36 paires) : le preset garde son niveau d'engagement, comme en [B' / E1 ×1](15d-mesure-exposition-egale.md). La base de l'onboarding (≈ 10 à 20× plus grosse) sature le cash et fausse le timing : écartée.
- **Mesure** : `ExposureParity.run` (B', rien dupliqué) sur l'actif cible : jusqu'à 4 blocs de 24 mois non chevauchants alignés sur sa dernière bougie + historique complet (rapporté), DCA fixe calé à exposition moyenne égale, bande de bruit = 20 décalages de départ 0–60 j. Δ = gain preset − gain DCA égal (points de capital) par bloc ; largeur de bande = P80 de la bande − DCA égal (≥ 0).

## Règles (fixées avant lecture, `TransferConfig`)
1. `TRANSFERE` : ≥ 2 blocs ; Δ > 0 sur ≥ 60 % des blocs ET Δ médian > 0 ET Δ médian > médiane des largeurs de bande. Moins de 2 blocs : `PROVISOIRE` (signe du Δ du bloc ou de l'historique complet, jamais de verdict ni d'exclusion de l'actif) ; plage non rejouable : `NON_CONCLU`.
2. Preset `GENERIQUE` : `TRANSFERE` sur ≥ 70 % des actifs hors échantillon conclus. Paire dans l'échantillon (BTC→BTC, ETH→ETH, PAXG→PAXG) : rapportée, **jamais comptée**.
3. Choix par volatilité : preset de l'actif source dont l'ATR % médian (ATR 14 RMA / clôture, historique complet) est le plus proche (écart `|ln(rapport)|`, aucun paramètre libre, aucune donnée de test). Porte sur les actifs hors des 3 sources. `CHOIX_PAR_VOLATILITE` si le preset choisi `TRANSFERE` sur ≥ 70 % des actifs conclus ET bat la moyenne des deux autres (Δ médian) sur ≥ 60 %.
4. Global : `NON_CONCLUANT` si < 5 actifs hors échantillon conclus ; sinon `PRESET_GENERIQUE` (un seul GENERIQUE : nommé ; plusieurs : dit sans en choisir un a posteriori) ; sinon `CHOIX_PAR_VOLATILITE` ; sinon `RIEN_NE_TRANSFERE` (le nouvel actif ne reçoit que DCA + calage d'exposition).
5. Hors verdict, marqué « a posteriori / dans l'échantillon » : meilleur preset par actif.

## Stockage bench dédié (`BenchCandleStore`, `tools/bench-data/fetch_binance_d1.py`)
- Bougies D1 **Binance en USDT, usage bench / étude uniquement** : jamais l'exécution, jamais le live (la décision « provider du lien, sans fallback » est intacte).
- Fichiers `tools/rainbow-replay/data-bench/d1_<ACTIF>.csv` + `manifest.json` (étiquette source `BINANCE/USDT`, symbole, quote, nombre de bougies, premier / dernier jour, sha256 du CSV). Hors DB (pas de Flyway), hors tables de bougies lues par le live.
- Le chargement refuse un CSV modifié (sha256), un nombre de lignes différent ou une étiquette inattendue ; l'étiquette devient la `source` du lien rejoué et figure dans le rapport avec l'empreinte `CandleFingerprint`.
- Récupération : pages de 1 000, pause 1 s entre pages, séquentielle, bougie du jour non close écartée. Refaire : `python tools/bench-data/fetch_binance_d1.py tools/rainbow-replay/data-bench [ACTIF …]` (les CSV changent : relancer l'étude).
- Aucune classe hors `service.optimizer` ne référence le stockage bench (testé).

## Lancement
`run-optimizer-manual.bat transfer` (mode `transfer` de `OptimizerManualRunnerTest`, base locale copie de prod pour BTC / ETH / PAXG ; `-Doptimizer.end=` fixe J-1, défaut 2026-11-22). Estimation (`TransferConfig.worstCaseMinutes`, 36 paires × B') écrite dans `target/transfer-presets-plan.txt` avant le run ; arrêt si > 10 min sauf `-Doptimizer.transfer.confirm=true`. Pool dédié (`POOL`), aucun tirage : même rapport à l'octet hors durée. Sorties `target/transfer-presets.json|md` (tableaux, verdicts, empreintes, bilan de dérive, multiplicité).

## Tests (`TransferStudyTest`, sans base)
Étiquette source posée (manifeste) et propagée (paire, rapport JSON et markdown) ; CSV bench modifié refusé ; bougies bench jamais référencées hors optimiseur ; paire dans l'échantillon exclue du verdict (cas qui basculerait si elle comptait) ; règle `TRANSFERE` (seuils) ; actif à 1 bloc ou < 24 mois = `PROVISOIRE` sans blocage ni exception, hors verdict ; règle 3 : choix identique quels que soient les résultats de test ; reproductibilité (deux runs, série contre pool).
