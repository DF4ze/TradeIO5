---
statut: journal
sujet: Pistes de timing et de sorties après D0 — vente du bag au switch Bull vers Bear, ré-entrée, diagnostic avant tout code ; paramètres nouveaux candidats ; étapes proposées à la roadmap
modifié_le: 2026-11-20
empreinte: 74f8a42ef0
---
# Étude — pistes de timing et de sorties (après D0, 2026-11-20)

Discussion parallèle avec Clem, à reprendre par la discussion principale. Position de Clem : [`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md) (« Presets purs »). Faits de départ : [`etude-reglage-recent.md`](../archive/etudes/etude-reglage-recent.md) (D0), constats B / B' / C1 / C2 de la même décision. Mécanisme des ré-entrées existantes : [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md) (`blockBuyAfterSellUntilDown2`, `cooldownAfterSellOn`, `allowSellDuringCooldown`). **E0 codé et lancé (2026-11-20) : [`etude-diagnostic-switchs.md`](etude-diagnostic-switchs.md) ; E1 codé et lancé (BTC) : [`etude-coupure-switch.md`](etude-coupure-switch.md).**

## 1. Ce que les résultats disent déjà
- **Le timing du preset est bon, le gain est fixé par la part engagée** (B / B' / C1). Régler les paramètres du preset n'ajoute rien (B, D0 : NON_PERSISTANT).
- **Moduler le volume par régime est nul à plafond de drawdown égal** (C2) ; dans D0 le « gain » du réglé était de l'exposition en plus (douleur en bear, aubaine en bull). D'où la position de Clem : retirer la modulation de volume des tests de presets, ne tester que du **timing**.
- **Ce qui n'a jamais été testé** : agir sur le **bag déjà détenu** au moment où le régime bascule. Rainbow ne vend que sur les zones de hausse (Up) et ses stops ; au switch Bull → Bear, le jeu Bear prend la main **sans rien liquider**. Le bag traverse donc la chute. C'est la source de perte restante la plus identifiable (drawdowns des phases baissières de D0 : −20 à −24 pts à 6 mois).

## 2. Piste principale (Clem) : couper l'exposition au switch Bull → Bear
**Idée.** Au passage Bull → Bear de la Trend, vendre tout (ou une fraction) du bag ; ne racheter qu'après une condition de ré-entrée. Raison : en Bear, statistiquement, il y a de la chute ; couper limite le drawdown.

**Pourquoi c'est différent de ce qui a échoué.** C2 bornait l'exposition des achats par régime (volume) ; ici on **sort d'une position détenue sur un événement** (timing). Ça agit sur le drawdown sans réduire l'exposition en Bull.

**Réponses à l'intuition, chiffrées d'avance (à ne pas deviner, à mesurer).**
- **Retard du signal** (accepté par Clem) : la Trend confirme (10 j de confirmation, régressions longues). Une part de la chute est déjà consommée au switch ; ce qu'on évite est seulement la suite.
- **Faux signaux** (acceptés par Clem, repère : 11-23 mars 2023) : un switch Bear suivi d'un nouveau plus haut coûte (prix de rachat − prix de vente) + frais, sans rien éviter.
- **Seuil de rentabilité** : la coupure ne vaut que si P(vrai bear) × baisse évitée > (1 − P) × coût d'un aller-retour. C'est ce que le diagnostic de §4 mesure.
- **Bande L2** : la vente doit rester bornée par le plancher d'exposition de la bande de l'utilisateur ([`../decisions/modulation-exposition-puissance.md`](../decisions/modulation-exposition-puissance.md)) ; « tout vendre » = descendre au plancher.
- **Fiscalité** : crypto → USDC, aucun nœud fiat, cadre inchangé.

**Cadre retenu par Clem** ([`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md), « Coupure au switch ») : vente paramétrable, testée à 100 % du bag ; ré-entrée au switch Bear → Bull par une **fraction du cash, 20 % au départ** ; retard de la Trend et faux switchs acceptés (drawdown prioritaire sur le gain).
- **Variante de base (E1)** : vente 100 % au switch Bull → Bear (plancher = bande L2), rachat de 20 % du cash au switch Bear → Bull, Rainbow normal ensuite.
- **À lever** : après les 20 % du switch, le reste du cash est-il déployé par les achats Rainbow habituels (lecture probable) ? Les 20 % portent-ils sur le cash au moment du switch ?
- **Variantes secondaires, seulement si E1 est positif** : ré-entrée sous Down2 (mécanisme existant `blockBuyAfterSellUntilDown2`), cooldown de N jours.

## 3. Autres pistes raisonnées (à classer, pas à toutes tester)
- **Sortie sur prix, pas sur Trend** : stop suiveur de type ATR / Supertrend depuis le plus haut (idée antérieure de Clem, stop-loss suiveur) ; pas de retard de Trend mais plus de faux déclenchements ; complémentaire de la piste principale.
- **Sortie en deux temps** : une fraction quand la SMA / ATR casse (précoce, bruyant), le reste à la confirmation par la régression ; réduit le retard. Dépend de ce que la Trend Mix expose (K20 : switchs dus à la SMA).
- **Vendre les rebonds en Bear** : `sellFraction` et zones Up du jeu Bear plus agressives (existant, mais exclu de la recherche pure tant que le volume est modulé ; à rouvrir comme timing).
- **Ne pas racheter le haut de Bull** : réduire les achats près de l'ATH. Volume + timing mêlés, donc après décision sur la modulation ATH ; à cadrer.
- **Rachat par paliers après coupure** : au lieu d'une condition unique, remonter l'exposition par tranches à mesure que le prix baisse / que la Trend revient (réutilise les zones).

## 4. Méthode proposée (pour ne pas « aller droit dans le mur »)
1. **Diagnostic descriptif d'abord, sans optimisation ni preset.** Pour chaque switch Bull → Bear de la Trend (BTC, puis ETH, PAXG pour comparaison, corrélés donc non indépendants) : baisse déjà consommée depuis le plus haut au switch ; pire baisse restante ensuite ; rendement à 30 / 90 / 180 j ; part de faux switchs (nouveau plus haut sous 60 j). Référence nulle : mêmes mesures à des dates tirées au hasard. Si la baisse restante n'est pas nettement pire que le hasard, la piste est close avant tout code.
2. **Un seul critère de décision** : décaler la frontière gain / drawdown (C1 / C2). La coupure ne compte que si, **à plafond de drawdown égal** (par profil), elle donne plus de gain que l'exposition constante maximale. Même protocole 4 blocs, 3 pour régler / 1 pour tester.
3. **Peu de leviers** : trigger fixe (switch Trend), 1 à 3 variantes de ré-entrée, 1 fraction. Aucun réglage fin des autres paramètres en parallèle (leurs valeurs = preset pur).
4. **Verdict fixé avant lecture**, multiplicité écrite, un seul run, comme D0.

## 5. Paramètres nouveaux candidats
`switchSellFraction` (0 à 1, plancher = bande L2 ; testé à 1), `switchReentryCashFraction` (0 à 1, départ 0,20), `switchReentryMode` (`TREND_BULL` par défaut ; `DOWN2`, `COOLDOWN_N` en secondaire), `switchReentryDays` (si cooldown). La vente au switch est un événement de vente comme un autre (mécanisme de ré-entrée existant) ; pas de nouveau moteur.

## 6. Étapes proposées à la roadmap (décision de la discussion principale)
- **D0b** : D0 relancé « preset pur » (multiplicateurs de zone et ATH figés, exposition à ±X % du preset dans l'échantillon), comparé au **preset en place avec la même modulation désactivée**. Ne vaut que comme contrôle ; probablement NON_PERSISTANT aussi, mais le biais « plus d'exposition » est écarté.
- **E0** : diagnostic descriptif des switchs (§4.1), sans code de preset.
- **E1** : test de la coupure au switch contre la frontière à plafond égal (§4.2), seulement si E0 ne ferme pas la piste.
D1 (accueil d'un nouvel actif) ne dépend pas d'E : il reste limité au preset de départ et au calage d'exposition.

## 7. Conséquence sur les références, limites, questions ouvertes
- **Comparateur** : désactiver la modulation (ATH, multiplicateurs de zone) sur le preset en place pendant les benchs est cohérent, mais **les chiffres de B' / C1 / C2 / C3 / C4 ont été mesurés avec la modulation active** : niveaux d'exposition par profil et pires drawdowns ne sont plus la référence d'un test « preset pur ». À arbitrer : re-mesurer la frontière (C1) en preset pur avant E1, ou comparer E1 à l'exposition constante « preset pur » recalée.
- Peu de switchs Bull → Bear sur BTC (compte à faire dans E0) : faible puissance ; ETH et PAXG n'ajoutent pas d'indépendance réelle. E0 inclut les deux repères de Clem (août-octobre 2025, mars 2023).
- **Multi-actif et portefeuille** : à cash commun, une ré-entrée de 20 % du cash par actif au même switch peut déployer jusqu'à 1 − 0,8ⁿ du cash pour n actifs ; plafond ou répartition de portefeuille à prévoir (hors E1, BTC seul).
- Questions pour la discussion principale : ordre D0b / re-mesure C1 / E0 ; quand traiter le multi-actif ; la modulation « au bon moment » du bench (timing ou volume) reste à discuter.
