---
statut: courant
sujet: Diagnostic des switchs Bull → Bear (étape E0) — mesures sur prix et régime, hasard apparié, règle de verdict, runner switch-diagnostic
vérifié_le: 2026-11-20
modifié_le: 2026-11-20
empreinte: 69670a94a9
---
# Diagnostic des switchs Bull → Bear (`SwitchDiagnosticStudy`, `SwitchDiagnosticConfig`, `SwitchDiagnosticReport`)

Outil de mesure (hors prod, rien appliqué ni persisté, aucun preset, aucune modulation, aucun rejeu de stratégie). Il répond à une seule question : couper le bag au switch Bull → Bear de la Trend aurait-il été utile, la baisse restante étant nettement pire que le hasard ? Constats chiffrés : [`../etudes/etude-diagnostic-switchs.md`](../etudes/etude-diagnostic-switchs.md). Cadre : [`../etudes/etude-pistes-timing-sorties.md`](../etudes/etude-pistes-timing-sorties.md) (§2, §4.1), [`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md) (« Coupure au switch »).

## Régime et switchs
- Régime = **celui du rejeu Rainbow**, code unique : `LinkSignalReplay.regimes(cfg)` (Trend Mix, confirmation et retard inclus) puis `LinkSignalReplay.setOfBar(cfg, régimes)` (mapping RANGE du lien). Le constructeur de `LinkSignalReplay` appelle ces deux méthodes : le diagnostic ne duplique rien (testé : séries identiques).
- Bougies D1 = celles du rejeu (`ReplayInputsLoader.load`, cache DB, rien téléchargé), jusqu'au dernier jour clos. Historique rejouable = dès la première bougie à Trend définie.
- Switch Bull → Bear = jour S où le jeu passe de Bull à Bear (`set[S-1] = BULL`, `set[S] = BEAR`) ; retour R = premier passage Bear → Bull après S. Un switch sans retour avant la fin des données est **ouvert**. Détection causale : ne lit que `set[S-1]` et `set[S]` ; le régime à S est inchangé si les bougies sont tronquées à S (testé sur BTC réel).

## Mesures par switch (clôtures D1, rapportées à P_S)
1. Baisse consommée : P_S / plus haut de clôture depuis le dernier retour Bear → Bull (premier switch : depuis le début de l'historique rejouable) − 1.
2. Baisse restante : pire clôture sur [S, S+90 j] et [S, S+180 j].
3. Rendements à S+30 / 90 / 180 j.
4. Faux switch : une clôture > plus haut de [dernier retour Bull, S] avant S+60 j (même référence que la mesure 1).
5. Gain de coupure : vendre à P_S, racheter à P_R. Brut = P_S / P_R − 1 ; net = (1 − f) / (1 + f) × P_S / P_R − 1 avec f = frais par ordre du simulateur (`OptimizerDefaults.SIMPLIFIED_FEE_PCT`, 0,40 %, barème simplifié sans spread ni glissement). Positif = couper aurait gagné.
6. Durée de la phase Bear (S → R).
Une mesure dont la fenêtre dépasse la fin des données vaut `null` (jamais 0) et sort des médianes ; un switch ouvert sort des mesures 5 et 6.

## Hasard
`nullDraws` (1 000) dates uniformes dans l'historique rejouable, graine `OptimizerDefaults.SEED` (rapportée), mêmes mesures 2 et 3 ; mesure 5 : vendre à la date tirée, racheter après une durée tirée dans les phases Bear fermées de l'actif. Rapporté par mesure : médiane des switchs, médiane / P10 / P90 du hasard, rang de la médiane des switchs (part du hasard ≤ médiane) ; rang par switch dans le tableau. Les valeurs dont la fenêtre dépasse les données sont exclues mesure par mesure.

## Verdict (`SwitchDiagnosticStudy.verdict`, fixé avant lecture ; BTC, switchs fermés)
N = switchs BTC fermés. N < 5 → `NON_CONCLUANT` (descriptif seul, jamais « fermée »). Sinon `PISTE_OUVERTE` si (a) gain de coupure net > 0 sur ≥ 60 % des switchs **et** (b) médiane du gain net > médiane du hasard apparié **et** (c) baisse restante à 90 j : médiane des switchs plus mauvaise que celle du hasard ; sinon `PISTE_FERMÉE` avec les conditions en échec. La phase de marché (rendement de P_S sur 365 j, ≥ 0 = haussière) est rapportée pour dire si l'effet n'existe que dans un type de marché. ETH et PAXG : mêmes mesures et même lecture, hors verdict (corrélés, non indépendants).

## Lancement
`run-optimizer-manual.bat switch-diagnostic` (= `mvn test -Dtest=OptimizerManualRunnerTest -Doptimizer.manual=switch-diagnostic`, base locale copie de prod, jamais dans la suite normale ; `-Doptimizer.end=AAAA-MM-JJ`, défaut 2026-11-17). Léger : chargement des bougies + O(N), ~20 s. Sorties `target/switch-diagnostic.json|md`, `target/switch-diagnostic-plan.txt`. BTC et ETH (wallet 1), PAXG (wallet 3, rapporté si rejouable).

## Tests (`SwitchDiagnosticTest`, sans base)
Aucune donnée > S pour définir S (série tronquée, et BTC réel bougies tronquées) ; série synthétique à résultat connu (consommée, baisse restante, rendements, faux switch, gain de coupure brut et net, durée) ; faux switch sur plus haut local ; switch ouvert exclu des médianes ; reproductibilité à l'octet (même graine) ; verdicts `NON_CONCLUANT`, `PISTE_OUVERTE`, `PISTE_FERMÉE` ; rang ; BTC réel (mêmes séries de régimes que le rejeu).
