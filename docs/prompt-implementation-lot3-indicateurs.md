# Prompt d'implémentation — Lot 3 (indicateurs macro/externes)

Prompt autonome, à la suite des Lots 1 et 2 (`docs/prompt-implementation-lot1-indicateurs.md`, `docs/prompt-implementation-lot2-indicateurs.md`). Couvre le **Lot 3** défini dans `docs/etude-indicateurs-macro-externes.md` (§14) : Flow ETF (scraping) et Zones de rejet. Contrairement aux deux lots précédents, **aucun des deux items n'a de dépendance technique sur le Lot 1/Lot 2** — ils peuvent être développés en parallèle l'un de l'autre, et démarrés sans attendre quoi que ce soit d'autre.

Nature commune des deux items : ce sont les deux seuls du programme entier qui ne sont pas de simples branchements d'API. L'un dépend d'une source non officielle qui peut casser sans préavis ; l'autre n'a pas de formule de référence à suivre et doit être défini puis validé empiriquement. Les deux demandent donc, en plus du code, un mécanisme explicite de dégradation propre (le premier) et une méthodologie de validation (le second) — ce ne sont pas des détails secondaires à ajouter après coup.

Lire avant de commencer :
1. `docs/etude-indicateurs-macro-externes.md` — §1, §5.
2. `service/tree/indicator/external/FearAndGreedIndicator.java`/`AbstractExternalIndicator.java` — patron `invalid()` déjà en place, à répliquer strictement pour l'item I.
3. `service/tree/indicator/impl/AtrIndicator.java`, `BollingerIndicator.java` — patron d'un indicateur qui scanne une fenêtre de `MarketDataset` (pertinent pour l'item J, qui a le même besoin : regarder plusieurs bougies, pas une valeur ponctuelle).

---

## Item I — Flow ETF (scraping Farside)

### Structure de la page, vérifiée en direct au moment de la rédaction

`https://farside.co.uk/btc/` (Bitcoin) et `https://farside.co.uk/eth/` (Ethereum, même structure de page — le document source demandait explicitement BTC **et** ETH). Page HTML servie côté serveur (pas de rendu JavaScript nécessaire pour voir le tableau — confirmé, un simple client HTTP suffit, pas besoin de navigateur headless).

Structure du tableau (une ligne = une date) :

```
                IBIT      FBTC      BITB      ARKB      ...      GBTC      BTC       Total
Fee             0.25%     0.25%     0.20%     0.21%     ...      1.50%     0.15%
22 Jun 2026     (172.0)   57.4      0.0       64.0      ...      (81.0)    48.1      (68.3)
23 Jun 2026     (182.0)   23.0      0.0       31.0      ...      0.0       0.0       (113.8)
...
08 Jul 2026     -         -         -         -         ...      -         -         0.0
Total           60,258    10,238    1,977     1,291     ...      (27,215)  2,419     51,419
Average         96.9      16.5      3.2       2.1       ...      (43.8)    5.0       82.7
Maximum         1,119.9   473.4     237.9     268.7     ...      102.5     191.1     1,373.8
Minimum         (528.3)   (356.6)   (280.7)   (327.9)   ...      (642.5)   (318.2)   (1,113.7)
```

**Points de format à gérer explicitement dans le parseur** (vérifiés sur la page réelle, pas supposés) :
- Notation comptable pour les négatifs : `(172.0)` signifie `-172.0`.
- `0.0` = flux nul ce jour-là (vraie donnée, pas une valeur manquante).
- `-` = jour non encore publié (aujourd'hui, avant la clôture des marchés US) — **à distinguer de `0.0`**, ne pas mapper `-` vers `0.0`, mapper vers absence de valeur.
- Les valeurs cumulées (colonne `Total` à droite, sur les milliers) peuvent porter une virgule comme séparateur de milliers (`60,258`) — uniquement sur les lignes de synthèse en bas de page, pas sur les lignes quotidiennes dans le corps du tableau (`(68.3)`, `223.5`, etc., toujours sans virgule vu l'ordre de grandeur journalier).
- Les 4 dernières lignes du tableau (`Total`, `Average`, `Maximum`, `Minimum`) et la ligne `Fee` juste après l'en-tête sont des lignes de synthèse/métadonnées, **pas des lignes de flux quotidien** — le critère de reconnaissance le plus fiable n'est pas la position dans le tableau (qui peut varier légèrement) mais le contenu de la première colonne : si elle parse comme une date (`"DD Mon YYYY"`, ex. `"22 Jun 2026"`), c'est une ligne de donnée ; sinon (`"Fee"`, `"Total"`, `"Average"`, `"Maximum"`, `"Minimum"`), l'ignorer.
- La ligne d'en-tête des tickers (`IBIT`, `FBTC`, ...) donne le nom de chaque colonne — ne pas coder les noms de colonnes en dur (la liste d'émetteurs a changé dans le passé et changera encore), lire dynamiquement l'en-tête à chaque fetch pour associer chaque valeur de la ligne à son émetteur.

**À faire** :
1. `WebProviderCode.FARSIDE` — nouvelle valeur, `IndicatorType.ETF_FLOW` — nouvelle valeur.
2. Ajouter la dépendance HTML parsing si absente du projet (`org.jsoup:jsoup`, à vérifier dans `pom.xml`/`build.gradle` avant de l'ajouter en double).
3. Package `service/tree/indicator/external/etfflow/` :
   - `FarsideEtfFlowClient extends AbstractExternalIndicator` : télécharge la page (`getWebClient(credential).get().uri("/btc/")` ou `/eth/` selon le paramètre), parse le tableau HTML avec Jsoup selon les règles ci-dessus, retourne un DTO normalisé.
   - `EtfFlowResponse` (DTO) : `valid` (booléen), `date` (dernière date **avec donnée publiée**, donc en excluant les lignes `-`), `byIssuer: Map<String, Double>` (ticker → flux du jour, ex. `"IBIT" -> 54.8`), `total: Double`.
   - **Chaque étape de parsing doit pouvoir échouer proprement** : page inaccessible → `invalid()` (comme d'habitude) ; page accessible mais structure de tableau changée (colonne manquante, en-tête introuvable, aucune ligne ne parse comme une date) → `invalid()` également, avec un log `warn` explicite mentionnant *quelle* étape du parsing a échoué (pas juste "erreur de parsing" générique — la personne qui débogue dans 6 mois doit pouvoir savoir immédiatement si c'est l'en-tête, le format de date, ou le format numérique qui a changé).
4. `EtfFlowIndicator implements Indicator` dans `service/tree/indicator/external/` : `getType() = ETF_FLOW`, `getRequiredData() = 0`, paramètre supplémentaire pour choisir BTC/ETH (`IndicatorParameters.getString("asset")` ou équivalent, avec `"BTC"` par défaut), retourne `IndicatorResult.values = Map.of("total", ...)` a minima, plus le détail par émetteur si utile (`"IBIT", "FBTC", ...` directement dans `values`, en plus de `"total"` — accepter que la liste de clés varie dans le temps si Farside ajoute/retire un émetteur, ce n'est pas un problème pour un `Map<String,Double>`).
5. `IndicatorCredentialResolver.resolve(...)` : `case ETF_FLOW -> WebProviderCode.FARSIDE;` — même si aucune vraie clé n'est nécessaire, garder la cohérence avec le reste du pipeline (patron déjà suivi pour `DEFILLAMA`/`FOREXFACTORY` aux lots précédents).
6. `WebProviderInitializer`/`ApiCredentialInitializer` : provider `FARSIDE` (base URL `https://farside.co.uk`), credential `"System"` sans vraie clé.

**Traitement explicite du risque de rupture silencieuse** (le point dur réel de cet item, déjà signalé dans l'étude) : écrire un test qui échoue bruyamment si le nombre de colonnes détectées tombe à zéro ou si aucune ligne ne parse comme une date sur un fixture HTML réel (sauvegarder une copie statique de la page au moment de l'implémentation comme fixture de test) — l'objectif n'est pas seulement que le code ne plante pas en prod, c'est qu'un changement de structure soit détecté vite plutôt que de faire silencieusement remonter `invalid()` pendant des semaines sans que personne ne s'en rende compte. Envisager un log `warn` distinct (pas juste `debug`) à chaque `invalid()` de cet indicateur spécifiquement, pour qu'il soit visible dans les logs applicatifs plutôt que noyé.

**Tests attendus** : parsing complet sur une fixture HTML réelle (sauvegarder le tableau actuel) → vérifier `byIssuer`/`total`/`date` corrects ; ligne avec `-` correctement exclue (pas mappée à `0.0`) ; ligne `Total`/`Average`/`Maximum`/`Minimum`/`Fee` correctement ignorée ; comportement sur une fixture volontairement cassée (en-tête supprimé, colonne manquante) → `invalid()` propre, pas d'exception.

---

## Item J — Zones de rejet

**Rappel du cadrage utilisateur** : la définition doit être posée et **validée avant d'être considérée fiable** — le risque concret (déjà observé par l'utilisateur sur des indicateurs TradingView existants) est de livrer quelque chose qui a l'air juste visuellement mais qui ne l'est pas statistiquement. Ce lot inclut donc une étape de validation empirique comme livrable à part entière, pas comme un "nice to have".

### Définition proposée (point de départ à calibrer, pas une formule figée à implémenter telle quelle)

Une bougie est candidate à un **rejet haussier** (le marché a testé un niveau bas et l'a rejeté vers le haut) si, sur `MarketData` (`open`, `high`, `low`, `close`) :

```
lowerWick = min(open, close) - low
body      = |close - open|
range     = high - low

rejet haussier si :
  lowerWick > k1 × body                     (mèche nettement plus grande que le corps)
  ET lowerWick > k2 × ATR(period)           (mèche significative par rapport à la volatilité récente, pas juste "grande" dans l'absolu)
  ET close > low + p × range                (clôture repoussée loin du plus bas de la bougie — ex. p = 0.66 : clôture dans le tiers supérieur)
```

Symétrique pour un **rejet baissier** (`upperWick`, clôture repoussée vers le bas de la bougie).

**Pourquoi 3 conditions et pas une seule** — directement pour éviter le symptôme déjà observé par l'utilisateur (des indicateurs qui "font n'importe quoi") : une mèche longue en valeur absolue ne veut rien dire dans un marché déjà très volatil (d'où la comparaison à l'ATR, pas à une valeur fixe) ; une mèche longue par rapport au corps peut apparaître sur une bougie quasi-doji sans qu'il y ait eu de vrai "rejet" directionnel (d'où la condition sur la position de clôture, qui confirme que le marché a fini loin du niveau testé, pas juste hésité).

### Ce qu'une seule bougie ne suffit pas à établir : la notion de zone

Une bougie de rejet isolée est un signal faible. Une **zone** de rejet, au sens du besoin exprimé dans le document source, est une zone de prix où **plusieurs** rejets se sont produits dans une fenêtre de lookback — c'est ce qui la rend statistiquement plus intéressante qu'un pivot isolé.

Algorithme de regroupement proposé :
1. Scanner une fenêtre de lookback (paramétrable, ex. les 200 dernières bougies disponibles dans `context.marketDataset()`) et collecter tous les rejets candidats (prix du niveau testé = `low` pour un rejet haussier, `high` pour un rejet baissier ; pas le prix de clôture).
2. Regrouper les rejets candidats dont le niveau de prix testé est à moins de `d` (paramétrable, ex. `0.5 × ATR`) les uns des autres, en une "zone".
3. Score de force de la zone, combinant (pondération à calibrer) :
   - Nombre de touches (rejets regroupés dans la zone).
   - Volume cumulé sur les bougies de rejet de la zone (si le volume est disponible dans `MarketData` — déjà le cas, `Bucket` le porte nativement), pour donner plus de poids à un rejet confirmé par une activité d'échange élevée qu'à un rejet sur un volume anémique.
   - Récence (un rejet vieux de 150 bougies compte moins qu'un rejet d'il y a 5 bougies — pondération décroissante avec l'ancienneté, ex. exponentielle).

### Contrat `Indicator` — limite à documenter explicitement

`IndicatorResult` expose `value`/`values: Map<String,Double>` — une forme à plat, pas nativement adaptée à une liste de zones (chacune avec niveau de prix, force, nombre de touches, direction). **Pour ce lot, restreindre le scope à la zone la plus proche du prix courant dans chaque direction** plutôt que d'exposer toutes les zones détectées :

```
values = {
  "nearestResistancePrice":    ...,
  "nearestResistanceStrength": ...,   // score de force normalisé, ex. [0,1]
  "nearestResistanceTouches":  ...,
  "nearestSupportPrice":       ...,
  "nearestSupportStrength":    ...,
  "nearestSupportTouches":     ...
}
```

Si un usage ultérieur a besoin de la liste complète des zones détectées (pas seulement les deux plus proches), ce sera une extension du contrat de sortie (`IndicatorResult` ou un DTO dédié plus riche), pas un ajustement mineur — le signaler plutôt que de forcer une liste dans le `Map<String,Double>` actuel par un encodage artificiel (ex. `"zone1Price"`, `"zone2Price"`, ...), qui serait fragile et pénible à consommer côté appelant.

### À faire

1. `IndicatorType.REJECTION_ZONE` — nouvelle valeur. Pas de nouveau `WebProviderCode`/credential (calcul entièrement interne, comme `AdxIndicator`/`AtrIndicator`).
2. `RejectionZoneIndicator implements Indicator` dans `service/tree/indicator/impl/` (pas `external/` — aucune dépendance réseau). `getRequiredData(parameters)` : retourner explicitly la taille de la fenêtre de lookback nécessaire (ex. 200 bougies + la période ATR utilisée), sur le même principe que les indicateurs existants qui déclarent leur besoin de warmup.
3. Paramètres exposés via `IndicatorParameters` (numerics) : `k1` (ratio mèche/corps), `k2` (ratio mèche/ATR), `p` (position de clôture), `lookback` (nombre de bougies), `clusterDistance` (en multiple d'ATR), `atrPeriod`. Valeurs par défaut documentées en constantes, comme les autres indicateurs paramétrés du projet (`AdxIndicator`, `BollingerIndicator`).
4. Réutiliser le calcul ATR déjà existant (`AtrIndicator`) plutôt que de recalculer une volatilité en interne — vérifier comment un indicateur consomme un autre indicateur en interne dans le code actuel (`IndicatorContext.dependencies`, mentionné dans la Javadoc d'`Indicator`, prévu justement pour ce genre de besoin — "résultats d'indicateurs déjà calculés, utile pour MACD, bandes, etc.") plutôt que d'appeler `IndicatorEngine` en dur depuis `RejectionZoneIndicator`.

### Méthodologie de validation — livrable obligatoire de ce lot, pas une option

L'objectif n'est pas de vérifier que le code fait ce que la formule dit (ça, c'est un test unitaire classique), c'est de vérifier que **la formule elle-même vaut quelque chose** — exactement le point sur lequel l'utilisateur a vu d'autres indicateurs échouer.

1. **Calibration visuelle d'abord** : générer les zones détectées sur un historique connu (quelques centaines de bougies sur BTC/USDT en H1, par exemple) et les comparer à l'œil à ce qu'un lecteur de graphique identifierait comme zone de rejet évidente — c'est le test que l'utilisateur dit savoir faire "à l'œil nu". Sert à calibrer `k1`/`k2`/`p` à des valeurs raisonnables avant de passer à l'étape suivante, pas à valider l'indicateur définitivement (un œil humain peut aussi se tromper, ou confirmer un biais).
2. **Test statistique ensuite** : pour chaque zone détectée, mesurer sur les données historiques disponibles ce qui se passe la/les fois suivantes où le prix revient tester cette zone — rejet à nouveau (la zone "tient") ou franchissement (la zone est cassée). Calculer un taux de réaction (proportion de tests qui produisent un nouveau rejet) et comparer à un niveau de référence choisi arbitrairement dans la plage de prix récente (pour vérifier que les zones détectées réagissent significativement mieux qu'un niveau choisi au hasard — sinon la formule ne capture rien de réel).
3. **Sensibilité aux paramètres** : faire varier `k1`/`k2`/`p`/`clusterDistance` sur une plage raisonnable et vérifier que le taux de réaction ne s'effondre pas au moindre changement (un indicateur dont le résultat dépend de façon instable d'un paramètre à la 3ᵉ décimale n'est pas un indicateur exploitable, même s'il a un bon score avec un réglage précis).

Ce protocole peut être un script/notebook d'analyse en dehors du code de production (ex. un test d'intégration dédié ou un outil de calibration séparé, pas nécessairement une classe Java de plus) — mais son résultat (taux de réaction observé, paramètres retenus et pourquoi) doit être documenté quelque part de durable (ex. un complément à `docs/etude-indicateurs-macro-externes.md` ou un nouveau court document `docs/calibration-rejection-zone.md`) avant de considérer cet indicateur activable par défaut dans une `Strategy`/`Opinion`.

**Tests unitaires attendus** (en plus de la validation ci-dessus, qui n'est pas un test unitaire classique) : détection correcte d'un rejet haussier/baissier sur un jeu de bougies synthétique construit pour matcher exactement la définition ; non-détection sur une bougie quasi-doji ou une mèche insignifiante par rapport à l'ATR ; regroupement correct de 2 rejets proches en une seule zone avec un score de force supérieur à un rejet isolé ; comportement sur un historique trop court pour le `lookback` demandé (`getRequiredData()` doit refléter ce besoin, et `compute()` doit retourner `invalid()` si les données disponibles sont insuffisantes, comme les autres indicateurs à warmup).

---

## Definition of done (les 2 items)

- Compilation propre, aucun test existant (Lots 1/2 inclus) cassé.
- Item I : fixture HTML réelle committée pour les tests (pas seulement des tests contre l'API live, qui rendraient la CI dépendante de la disponibilité de Farside) ; log `warn` distinct et explicite à chaque `invalid()` de cet indicateur.
- Item J : le protocole de validation (§ dédiée ci-dessus) exécuté et son résultat documenté **avant** de considérer l'indicateur prêt à être branché dans une `Strategy`/`Opinion` — livrer le code sans avoir fait cette étape ne satisfait pas ce lot, même si la compilation et les tests unitaires passent.
- Aucun des deux items n'est branché dans une `Strategy`/`MarketOpinion` à ce stade (comme aux lots précédents, le branchement en aval est un chantier séparé, hors périmètre annoncé ici).
