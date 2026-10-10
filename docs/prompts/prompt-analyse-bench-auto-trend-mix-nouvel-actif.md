# Prompt — Analyse : bench automatique du Trend Mix sur un nouvel actif

> À coller tel quel dans une nouvelle discussion (Claude Code / Cowork sur TradeIO-5). **Mission = ANALYSE + proposition + questions, aucun code tant que Clem n'a pas validé.**

## 0. Démarrage
1. Lis `docs/README.md`, puis `docs/architecture/09-trend.md`, `04-market-data.md`, `08-rainbow-bench-grandeur-nature.md`, `docs/known-gaps/rainbow-dca-chantiers-ouverts.md`, `docs/CODING_RULES.md`, `docs/glossary.md`, `docs/etudes/spec-composition-trend-unifie.md` (§10 : walk-forward de la Trend) et `docs/calibration/calibration-rainbow-atr-v3-regime.md` (pourquoi l'optimisation automatique du Rainbow a déçu).
2. Lis `tools/pine/rainbow_trend_dca_v1.pine` (source de vérité du Trend Mix) puis `TrendMixCalculator`, `RainbowSetSelector`, `RainbowLiveTrendConfig(s)`, `RainbowAtrReplayMain` et `RainbowAtrRegimeBench` (tests).
3. Réponds en français, court et technique (flèches/symboles bienvenus). Un point flou → **pose la question avant d'avancer** (AskUserQuestion).

## 1. Contexte
- Le Trend Mix (régression 3 fenêtres + prix vs SMA ± k×ATR, composition « 1er des deux à switcher ») pilote le choix du jeu Bear/Bull du Rainbow ATR. Ses 12 paramètres (`TrendMixCalculator.Params` : fenêtres 14/30/60, échelle 400, ENTER 0,3, EXIT 0,1, confirm 10, SMA 100, ATR 5, k 1,25, mèche basse/haute) ont été réglés **à la main** sous TradingView, pour BTC, avec le même jeu par défaut pour ETH et PAXG.
- Un actif jamais benché à la main n'a donc aucun réglage Trend validé. Clem se sentirait plus confiante avec un **bench automatique** pour un nouvel actif.
- Leçon à ne pas répéter : l'optimisation automatique du Rainbow (`RainbowAtrRegimeBench`, coordinate ascent, ~30 paramètres couplés) a donné des paysages non monotones et des jeux dégénérés (« ne vend jamais »). Les 12 paramètres du Trend Mix sont bien moins nombreux, mais le risque de surapprentissage (jeux « parfaits » in-sample) reste le même.

## 2. Décisions déjà prises (ne pas les remettre en question)
- Trend double abandonnée. Seul le Trend Mix est concerné.
- PAXG : Clem s'en occupe manuellement (hors périmètre).
- Les jeux Bull/Bear du Rainbow d'un nouvel actif ne sont **pas** l'objet de ce bench (à confirmer en §4 : le bench ne règle que la Trend, ou aussi le choix d'un jeu Bull/Bear existant ?).
- Rien n'est écrit en production par le bench : résultat = proposition de `Params`, validée par Clem avant de devenir un preset.

## 3. Mission — analyse, dans cet ordre

### 3.1 D'ABORD : définir les KPI (point d'entrée de l'analyse)
Clem est dans le flou sur ce point : **c'est la première chose à traiter, avant toute mécanique de recherche.** Sans KPI solides, un optimiseur trouvera le paramétrage qui dérive. Propose, avec justification et pour chacun : définition exacte, formule, seuil d'acceptation, risque de dérive couvert, et ce qui est mesurable avec les données dispo.
Pistes à évaluer, critiquer, compléter ou rejeter :
- **Qualité de la Trend en soi** (indépendante du Rainbow) : nombre de switchs par an (stabilité : Clem veut « une tendance large qui ne switche pas tous les 10 jours tout en restant réactive »), durée médiane d'un régime, part de régimes très courts (< N jours), retard de détection autour des retournements majeurs (écart entre le vrai sommet/creux et le switch), amplitude capturée dans le sens du régime, faux signaux (switch annulé en < N jours).
- **Qualité de bout en bout** (Trend → jeu Bull/Bear → Rainbow) : gain vs DCA fixe de même `baseAmount`, bag final, cash utilisé / pic d'exposition, drawdown de la valeur du portefeuille, nombre d'achats/ventes, jeu « dégénéré » (ne vend jamais, ne rachète jamais).
- **Robustesse** (anti-dérive, probablement le plus important) : walk-forward (réglage sur fenêtre N, test sur N+1), stabilité des paramètres d'une fenêtre à l'autre, sensibilité (le centre d'un plateau vaut mieux qu'un pic : perturber chaque paramètre de ±10-20 % et mesurer la perte), écart in-sample / out-of-sample, performance minimale sur chaque sous-période (haussière, baissière, latérale) et pas seulement en moyenne.
- **Garde-fous / contraintes dures** : bornes de plausibilité sur chaque paramètre, nombre max de switchs/an, ratio paramètres / nombre de régimes observés (taille d'échantillon), historique minimal requis pour accepter le bench (un actif jeune a peu de cycles).
- **Référence de comparaison** : le Trend Mix par défaut (réglé sur BTC) appliqué tel quel au nouvel actif = baseline à battre de façon **significative**, sinon on garde le défaut. Réfléchis aussi à un baseline « bête » (jeu unique Bull-only / Bear-only, Trend aléatoire de même fréquence de switch) pour savoir si la Trend apporte quelque chose.
- Compromis à expliciter : un score unique agrégé vs plusieurs KPI avec seuils (veto) ; comment éviter qu'un KPI soit « optimisé » au détriment d'un autre.
- Dérive spécifique aux **actifs adossés** (stablecoin, or…) ou très peu volatils : la Trend a-t-elle un sens ? KPI de « tradabilité » à prévoir pour refuser le bench plutôt que sortir un jeu absurde.
Livre un **tableau KPI** (nom, définition, seuil proposé, pourquoi) et signale ceux qui exigent une décision de Clem (seuils subjectifs : à lui poser en AskUserQuestion, avec une recommandation chiffrée).

### 3.2 Périmètre et espace de recherche
Quels paramètres libres (parmi les 12), lesquels figés ; bornes et pas ; réduction possible (par ex. échelle/EXIT/confirm figés, on ne règle que fenêtres + ENTER + SMA/ATR/k) ; taille de l'espace et stratégie (grille grossière puis affinage local, plateau plutôt que pic, évite le coordinate ascent à l'aveugle). Coût CPU (`TrendMixCalculator` est pur : quelle vitesse pour N combinaisons × historique ?).

### 3.3 Données et protocole
Historique D1 nécessaire et disponible pour un actif arbitraire (provider, fallback, politique de cache DB candles, `MarketDatasetEngine#getDatasetForAsset`, sources Binance vs autres : seul Binance a un large historique) ; découpage walk-forward ; que faire quand l'historique est trop court. Cohérence avec l'ATH par actif (`AthReference`) si le bench de bout en bout est retenu.

### 3.4 Forme de la livraison
Outil de test/CLI (comme `RainbowAtrReplayMain`) vs service applicatif vs endpoint admin ; format de sortie (rapport + CSV + éventuellement HTML autonome rejouant la Trend façon pine pour valider à l'œil) ; comment le résultat devient un preset système/utilisateur (`RainbowLiveTrendConfigs`, règle des défauts dans les Initializers) sans écriture automatique.

### 3.5 Questions ouvertes et feuille de route
Liste des questions (coquilles, ambiguïtés, décisions de Clem) puis feuille de route par lots petits et testables, avec critères d'acceptation. KPI d'abord (lot 1 = harnais de mesure sans optimisation, appliqué au BTC pour **vérifier que les KPI jugent bien le réglage manuel connu**), puis recherche, puis restitution.

## 4. Questions à poser à Clem (base, à compléter)
- Le bench règle-t-il **uniquement** les 12 paramètres Trend (jeux Bull/Bear fournis), ou aussi le choix/réglage des jeux Bull/Bear du nouvel actif ?
- Quels actifs candidats pour valider la démarche (ETH est le plus naturel : benché à la main ?) ? Un nouvel actif concret en tête ?
- Priorité entre stabilité de la Trend (peu de switchs) et réactivité (retard de détection) ?
- Seuil de « gain significatif » vs Trend Mix par défaut pour justifier un réglage spécifique ?

## 5. Règles
- Respecter `docs/CODING_RULES.md` et le style de Clem (isEmpty()/getFirst(), constantes métier mutualisées, pas de variables redondantes, assertTrue/False directs).
- Demander avant de deviner sur les points de conception ouverts.
- Toute évolution d'architecture future sera documentée dans `docs/` (sans historique : retirer ce qui n'est plus valable).
- Livrable de cette session : `docs/etudes/etude-bench-auto-trend-mix-nouvel-actif.md` (KPI en premier chapitre) + questions posées à Clem. Aucune modification de code.
