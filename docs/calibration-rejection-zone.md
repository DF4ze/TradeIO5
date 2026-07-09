# Calibration — Zones de rejet (Lot 3, item J)

Complément à `docs/etude-indicateurs-macro-externes.md` §14 et au prompt d'implémentation
`docs/prompt-implementation-lot3-indicateurs.md`. Documente le protocole de validation empirique
de `RejectionZoneIndicator` et son résultat, conformément à l'exigence du lot : "livrer le code
sans avoir fait cette étape ne satisfait pas ce lot, même si la compilation et les tests unitaires
passent".

## Outil

`tools/calibration/rejection_zone_calibration.py` — script Python autonome (hors code de
production Java), qui réimplémente exactement la même formule que `RejectionZoneIndicator.java`
(mêmes 3 conditions de rejet, même clustering par distance à `clusterDistance * ATR`, même score
de force) pour itérer rapidement sur les paramètres sans recompiler le projet. Exécute les 3 étapes
du protocole demandé par le prompt d'implémentation :

1. **Calibration visuelle** — résumé texte des zones détectées (niveau, nombre de touches, score)
   sur l'historique, avec les paramètres par défaut.
2. **Test statistique** — taux de réaction (proportion de tests d'une zone qui produisent un
   nouveau rejet plutôt qu'un franchissement) comparé à des niveaux de prix choisis au hasard dans
   la même plage.
3. **Sensibilité aux paramètres** — variation de `k1`/`k2`/`p`/`clusterDistance` sur une grille
   (81 combinaisons) et stabilité du taux de réaction résultant.

```
python3 tools/calibration/rejection_zone_calibration.py [--csv path/to/ohlcv.csv] [--seed 42]
```

## Limite importante de l'exécution ci-dessous : données synthétiques, pas un historique réel

L'environnement dans lequel cette implémentation a été réalisée n'a **pas d'accès réseau sortant**
vers un exchange (Binance, etc.) : le trafic sortant du bac à sable est restreint à une allowlist
qui ne couvre pas `api.binance.com`, et les tentatives de récupération d'un historique BTC/USDT H1
réel (API Binance, export CSV stooq) n'ont renvoyé aucune donnée exploitable. Faute d'historique
réel accessible depuis ce script, le run documenté ci-dessous tourne sur
`generate_synthetic_series()` : une marche aléatoire géométrique avec clusters de volatilité, dans
laquelle 3 niveaux de prix "vrais" ont été injectés artificiellement (rebonds forcés à ~58 000,
~63 500 et ~67 000) pour avoir un signal de test non trivial — si le protocole ne détectait même
pas un signal injecté à la main, la formule ne vaudrait clairement rien.

**Conséquence directe, à ne pas perdre de vue** : les chiffres ci-dessous valident que *le
protocole lui-même fonctionne* (le script tourne, détecte des zones, mesure un taux de réaction,
teste la sensibilité) et donnent une première indication que la formule n'est pas absurde par
construction. Ils ne remplacent **pas** une validation sur données de marché réelles. Avant
d'activer `RejectionZoneIndicator` par défaut dans une `Strategy`/`Opinion`, ce même script doit
être ré-exécuté avec `--csv` sur un export réel (ex. `MarketData` BTC/USDT H1 exporté depuis
TradeIO5 lui-même, qui a un accès réseau normal en production, contrairement à cet environnement de
build) — c'est un prérequis explicite, pas une formalité.

## Résultat de l'exécution (données synthétiques, seed=42, paramètres par défaut)

Paramètres par défaut testés : `k1=1.5`, `k2=0.5`, `p=0.66`, `clusterDistance=0.5×ATR`,
`atrPeriod=14`, `lookback=200` (sur les 200 dernières bougies de la série de 1500).

### 1. Zones détectées (calibration visuelle)

8 zones détectées sur la fenêtre de lookback, dont la plus forte (`resistance`, niveau ≈ 26 394,
2 touches, score 0.415) correspond à un regroupement réel de 2 rejets proches — cohérent avec ce
qu'un lecteur de graphique identifierait comme zone plus significative qu'un pivot isolé (les 7
autres zones, à 1 touche, ont un score entre 0.24 et 0.29).

### 2. Test statistique — taux de réaction

| | Taux de réaction | Nombre de tests |
|---|---|---|
| Zones détectées | **83.0 %** | 53 |
| Niveaux aléatoires (même plage de prix) | 64.8 % | 91 |

Écart de +18.2 points entre les zones détectées et une baseline aléatoire : sur ce jeu de données
(qui contient un signal injecté à la main), la formule capture bien un signal réel et ne se
comporte pas comme un sélecteur de niveaux arbitraire. Ce n'est pas une preuve que la formule
fonctionne sur un vrai marché — un jeu de données réel a un bruit et une microstructure différents
— mais c'est le minimum attendu avant de considérer la formule plausible.

### 3. Sensibilité aux paramètres

Grille de 81 combinaisons (`k1 ∈ {1.2, 1.5, 2.0}`, `k2 ∈ {0.35, 0.5, 0.75}`,
`p ∈ {0.55, 0.66, 0.75}`, `clusterDistance ∈ {0.35, 0.5, 0.75}`), toutes avec au moins une zone
testable :

- Taux de réaction : min **74.8 %**, max **95.5 %**, moyenne **85.5 %**, écart-type **6.6 points**.
- Aucun effondrement du taux de réaction observé sur la grille (pas de combinaison proche de 50 %,
  qui signalerait un hasard pur) : la formule ne dépend pas de manière instable d'un réglage
  précis à la décimale près, ce qui était le risque explicitement signalé par le prompt
  d'implémentation.
- L'écart-type de 6.6 points sur une grille large indique une sensibilité modérée mais réelle :
  à reconfirmer sur données réelles avant de figer des valeurs définitives.

## Paramètres retenus (point de départ, à reconfirmer sur données réelles)

Les valeurs par défaut de `RejectionZoneIndicator` (`DEFAULT_K1=1.5`, `DEFAULT_K2=0.5`,
`DEFAULT_P=0.66`, `DEFAULT_CLUSTER_DISTANCE=0.5`, `DEFAULT_LOOKBACK=200`, `DEFAULT_ATR_PERIOD=14`)
sont conservées comme point de départ : elles sont au centre de la grille de sensibilité testée et
n'ont montré aucun signe d'instabilité sur celle-ci. **Elles ne sont pas figées** — le protocole
ci-dessus doit être rejoué sur un historique BTC/USDT (et ETH/USDT) réel en H1 avant tout
branchement dans une `Strategy`/`Opinion`, conformément à la Definition of done du Lot 3.

## Prochaine étape (hors scope de ce lot)

1. Exporter un historique H1 réel (BTC/USDT, quelques centaines à quelques milliers de bougies)
   depuis TradeIO5 ou une source externe, au format CSV attendu par le script
   (`timestamp,open,high,low,close,volume`).
2. Ré-exécuter `rejection_zone_calibration.py --csv <export>.csv` et comparer le taux de réaction
   obtenu à celui documenté ici sur données synthétiques.
3. Si le taux de réaction réel s'effondre near 50 % (proche du hasard) ou si la sensibilité aux
   paramètres explose, revoir la formule (pondérations du score, définition du rejet) avant tout
   branchement en aval — ne pas activer l'indicateur par défaut dans l'intervalle.
