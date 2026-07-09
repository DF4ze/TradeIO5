#!/usr/bin/env python3
"""
Outil de calibration/validation pour RejectionZoneIndicator (Lot 3, item J).

Ce script est volontairement en dehors du code de production Java (cf. prompt d'implémentation :
"peut être un script/notebook d'analyse ... pas nécessairement une classe Java de plus"). Il
réimplémente la même formule que RejectionZoneIndicator.java (mêmes 3 conditions de rejet, même
clustering, même score) pour pouvoir itérer rapidement sur k1/k2/p/clusterDistance sans recompiler
le projet, puis exécute le protocole de validation en 3 étapes défini par le prompt :

  1. Calibration visuelle (ici : un résumé texte des zones détectées sur l'historique, à défaut
     d'un rendu graphique dans cet environnement - voir la sortie "ZONES DETECTEES").
  2. Test statistique : taux de réaction des zones détectées vs un niveau de référence choisi au
     hasard dans la même plage de prix.
  3. Sensibilité aux paramètres : variation de k1/k2/p/clusterDistance et stabilité du taux de
     réaction.

IMPORTANT (cf. docs/calibration-rejection-zone.md pour le détail) : l'environnement d'exécution de
cette implémentation n'a pas d'accès réseau vers Binance/un exchange (accès sortant restreint à une
allowlist qui ne couvre pas api.binance.com). Faute de pouvoir télécharger un historique BTC/USDT H1
réel depuis ce script, l'exécution ci-dessous tourne sur une série synthétique generée par
`generate_synthetic_series()` (marche aléatoire géométrique + niveaux de support/résistance
artificiels injectés). Le script est conçu pour être ré-exécuté tel quel sur un vrai export CSV
(colonnes: timestamp,open,high,low,close,volume) via `--csv <path>` dès qu'un historique réel est
disponible (export TradeIO5 ou téléchargement manuel) ; c'est un prérequis explicite avant
d'activer l'indicateur par défaut dans une Strategy/Opinion (cf. Definition of done du Lot 3).

Usage:
    python3 rejection_zone_calibration.py [--csv path/to/ohlcv.csv] [--seed 42]
"""

import argparse
import csv
import math
import random
import statistics
import sys
from dataclasses import dataclass, field


# --------------------------------------------------------------------------------------
# 1. Chargement des données
# --------------------------------------------------------------------------------------

def generate_synthetic_series(n=1500, seed=42):
    """Génère une série OHLCV synthétique "BTC-like" : marche aléatoire géométrique avec
    clusters de volatilité, sur laquelle on injecte volontairement des rebonds répétés autour
    de quelques niveaux de prix (pour avoir un signal de validation non trivial : si le protocole
    ne détecte même pas un signal injecté à la main, la formule ne vaut rien)."""
    rng = random.Random(seed)
    price = 60000.0
    candles = []

    # Niveaux de support/résistance "vrais" injectés artificiellement, avec une force
    # décroissante (le niveau 3 est plus faible / moins fiable que le niveau 1).
    injected_levels = [58000.0, 63500.0, 67000.0]

    vol_regime = 0.006
    for i in range(n):
        # cluster de volatilité : régime qui change lentement
        if rng.random() < 0.01:
            vol_regime = rng.uniform(0.003, 0.02)

        drift = rng.gauss(0, 1) * vol_regime
        open_p = price
        close_p = open_p * (1 + drift)

        high_p = max(open_p, close_p) * (1 + abs(rng.gauss(0, 1)) * vol_regime * 0.4)
        low_p = min(open_p, close_p) * (1 - abs(rng.gauss(0, 1)) * vol_regime * 0.4)

        # Injection : si le range de la bougie touche un niveau connu, on a une proba élevée
        # de rejet (mèche marquée + clôture repoussée), pour simuler un "vrai" support/résistance.
        for level in injected_levels:
            if low_p <= level <= high_p and rng.random() < 0.6:
                if abs(close_p - level) > abs(open_p - level):
                    # rejet haussier : on repousse le low vers le bas et la clôture vers le haut
                    low_p = min(low_p, level - vol_regime * level * rng.uniform(0.5, 1.5))
                    close_p = level + vol_regime * level * rng.uniform(0.3, 1.2)
                    high_p = max(high_p, close_p)
                else:
                    # rejet baissier
                    high_p = max(high_p, level + vol_regime * level * rng.uniform(0.5, 1.5))
                    close_p = level - vol_regime * level * rng.uniform(0.3, 1.2)
                    low_p = min(low_p, close_p)

        volume = abs(rng.gauss(100, 30)) + (50 if any(low_p <= l <= high_p for l in injected_levels) else 0)

        candles.append({
            "timestamp": i,
            "open": open_p, "high": high_p, "low": low_p, "close": close_p,
            "volume": max(volume, 1.0),
        })
        price = close_p

    return candles


def load_csv(path):
    candles = []
    with open(path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            candles.append({
                "timestamp": row.get("timestamp", len(candles)),
                "open": float(row["open"]),
                "high": float(row["high"]),
                "low": float(row["low"]),
                "close": float(row["close"]),
                "volume": float(row.get("volume", 0.0)),
            })
    return candles


# --------------------------------------------------------------------------------------
# 2. Réimplémentation de la formule (miroir de RejectionZoneIndicator.java)
# --------------------------------------------------------------------------------------

def wilder_atr(candles, period):
    """ATR lissage de Wilder, mêmes formules que AtrIndicator.java. Retourne une liste de même
    longueur que `candles`, avec None tant que l'ATR n'est pas calculable (warmup)."""
    n = len(candles)
    atr = [None] * n
    if n < period + 1:
        return atr

    tr = [None] * n
    for i in range(1, n):
        c, p = candles[i], candles[i - 1]
        tr[i] = max(
            c["high"] - c["low"],
            abs(c["high"] - p["close"]),
            abs(c["low"] - p["close"]),
        )

    first = sum(tr[1:period + 1]) / period
    atr[period] = first
    for i in range(period + 1, n):
        atr[i] = (atr[i - 1] * (period - 1) + tr[i]) / period

    return atr


@dataclass
class Rejection:
    level: float
    age: int
    volume: float
    direction: str  # "support" (rejet haussier) ou "resistance" (rejet baissier)


@dataclass
class Zone:
    level: float
    touches: int
    strength: float
    direction: str
    members: list = field(default_factory=list)


W_TOUCHES = 1.0
W_VOLUME = 0.5
W_RECENCY = 1.0
RECENCY_HALF_LIFE = 50.0
STRENGTH_SATURATION = 5.0


def detect_rejections(window, atr_value, k1, k2, p):
    """Même définition que RejectionZoneIndicator.compute() : 3 conditions par bougie."""
    n = len(window)
    rejections = []
    for i, c in enumerate(window):
        o, h, l, cl = c["open"], c["high"], c["low"], c["close"]
        rng = h - l
        if rng <= 0:
            continue
        body = abs(cl - o)
        lower_wick = min(o, cl) - l
        upper_wick = h - max(o, cl)
        age = n - 1 - i
        volume = c["volume"]

        if lower_wick > k1 * body and lower_wick > k2 * atr_value and cl > l + p * rng:
            rejections.append(Rejection(l, age, volume, "support"))
        if upper_wick > k1 * body and upper_wick > k2 * atr_value and cl < h - p * rng:
            rejections.append(Rejection(h, age, volume, "resistance"))
    return rejections


def cluster_zones(rejections, cluster_distance, window_avg_volume):
    zones = []
    for direction in ("support", "resistance"):
        points = sorted([r for r in rejections if r.direction == direction], key=lambda r: r.level)
        if not points:
            continue
        current = [points[0]]
        for r in points[1:]:
            if r.level - current[-1].level <= cluster_distance:
                current.append(r)
            else:
                zones.append(_build_zone(current, direction, window_avg_volume))
                current = [r]
        zones.append(_build_zone(current, direction, window_avg_volume))
    return zones


def _build_zone(members, direction, window_avg_volume):
    touches = len(members)
    level = sum(m.level for m in members) / touches
    recency_sum = sum(0.5 ** (m.age / RECENCY_HALF_LIFE) for m in members)
    avg_zone_volume = sum(m.volume for m in members) / touches
    volume_score = (avg_zone_volume / window_avg_volume) if window_avg_volume > 1e-9 else 0.0

    raw = W_TOUCHES * touches + W_VOLUME * volume_score + W_RECENCY * recency_sum
    strength = raw / (raw + STRENGTH_SATURATION)
    return Zone(level, touches, strength, direction, members)


def detect_all_zones(candles, k1, k2, p, cluster_distance_mult, atr_period, lookback):
    """Détecte les zones sur TOUTE la série (pas seulement les 2 plus proches, contrairement au
    contrat `IndicatorResult` en production) : nécessaire pour le test statistique, qui a besoin
    de la liste complète des zones pour vérifier ce qui se passe à chaque futur test de chacune."""
    atr_series = wilder_atr(candles, atr_period)
    n = len(candles)
    if n < lookback:
        return []

    window = candles[n - lookback:]
    atr_value = atr_series[n - 1]
    if atr_value is None or atr_value <= 0:
        return []

    cluster_distance = cluster_distance_mult * atr_value
    window_avg_volume = sum(c["volume"] for c in window) / len(window)

    rejections = detect_rejections(window, atr_value, k1, k2, p)
    return cluster_zones(rejections, cluster_distance, window_avg_volume)


# --------------------------------------------------------------------------------------
# 3. Test statistique : taux de réaction
# --------------------------------------------------------------------------------------

def reaction_rate(levels, candles, start_index, touch_tolerance, hold_margin, lookahead=5):
    """Pour chaque niveau, repère chaque "approche" du niveau (début d'une séquence de bougies
    dont le range touche le niveau +/- touch_tolerance ; les bougies consécutives qui touchent
    encore le même niveau ne comptent PAS comme un nouveau test, pour ne pas gonfler artificiellement
    le taux avec des micro-rebonds intra-séquence). Pour chaque approche, regarde les `lookahead`
    bougies suivantes : "tient" (rejet confirmé) si aucune ne clôture au-delà de `hold_margin` du
    mauvais côté du niveau, "cassé" sinon. Retourne (taux_de_reaction, nb_tests)."""
    holds = 0
    total = 0
    for level in levels:
        in_touch = False
        for i in range(start_index, len(candles) - 1):
            c = candles[i]
            touching = c["low"] - touch_tolerance <= level <= c["high"] + touch_tolerance
            if touching and not in_touch:
                was_above = c["close"] >= level
                broke = False
                for j in range(i + 1, min(i + 1 + lookahead, len(candles))):
                    nxt = candles[j]
                    if (was_above and nxt["close"] < level - hold_margin) or \
                            (not was_above and nxt["close"] > level + hold_margin):
                        broke = True
                        break
                total += 1
                if not broke:
                    holds += 1
            in_touch = touching
    if total == 0:
        return None, 0
    return holds / total, total


def run_statistical_test(candles, zones, rng):
    if not zones:
        return None

    price_min = min(c["low"] for c in candles)
    price_max = max(c["high"] for c in candles)
    avg_range = statistics.mean(c["high"] - c["low"] for c in candles)
    touch_tolerance = avg_range * 0.25
    hold_margin = avg_range * 0.5

    zone_levels = [z.level for z in zones]
    zone_rate, zone_n = reaction_rate(zone_levels, candles, 0, touch_tolerance, hold_margin)

    random_levels = [rng.uniform(price_min, price_max) for _ in zones]
    random_rate, random_n = reaction_rate(random_levels, candles, 0, touch_tolerance, hold_margin)

    return {
        "zone_reaction_rate": zone_rate,
        "zone_tests": zone_n,
        "random_reaction_rate": random_rate,
        "random_tests": random_n,
    }


# --------------------------------------------------------------------------------------
# 4. Orchestration
# --------------------------------------------------------------------------------------

DEFAULT_PARAMS = dict(k1=1.5, k2=0.5, p=0.66, cluster_distance_mult=0.5, atr_period=14, lookback=200)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", help="Chemin d'un CSV OHLCV réel (timestamp,open,high,low,close,volume)")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    if args.csv:
        candles = load_csv(args.csv)
        source = f"CSV réel: {args.csv}"
    else:
        candles = generate_synthetic_series(seed=args.seed)
        source = "série synthétique (generate_synthetic_series) — PAS des données de marché réelles, cf. avertissement en tête de fichier"

    print(f"=== RejectionZone — protocole de validation ===")
    print(f"Source: {source}")
    print(f"Bougies: {len(candles)}")
    print()

    # ---- Étape 1 : calibration visuelle (résumé texte des zones sur les paramètres par défaut) ----
    zones = detect_all_zones(candles, **DEFAULT_PARAMS)
    print(f"--- ZONES DETECTEES (params par défaut {DEFAULT_PARAMS}) ---")
    zones_sorted = sorted(zones, key=lambda z: -z.strength)
    for z in zones_sorted[:15]:
        print(f"  {z.direction:10s} level={z.level:10.2f}  touches={z.touches}  strength={z.strength:.3f}")
    print(f"  Total zones: {len(zones)}")
    print()

    # ---- Étape 2 : test statistique (taux de réaction vs baseline aléatoire) ----
    rng = random.Random(args.seed)
    stats = run_statistical_test(candles, zones, rng)
    print("--- TEST STATISTIQUE (taux de réaction) ---")
    if stats is None:
        print("  Aucune zone détectée avec les paramètres par défaut : impossible de calculer un taux de réaction.")
    else:
        print(f"  Zones détectées   : taux de réaction = {stats['zone_reaction_rate']:.1%}  ({stats['zone_tests']} tests)")
        print(f"  Niveaux aléatoires: taux de réaction = {stats['random_reaction_rate']:.1%}  ({stats['random_tests']} tests)")
    print()

    # ---- Étape 3 : sensibilité aux paramètres ----
    print("--- SENSIBILITE AUX PARAMETRES ---")
    grid = []
    for k1 in (1.2, 1.5, 2.0):
        for k2 in (0.35, 0.5, 0.75):
            for p in (0.55, 0.66, 0.75):
                for cd in (0.35, 0.5, 0.75):
                    grid.append(dict(k1=k1, k2=k2, p=p, cluster_distance_mult=cd,
                                      atr_period=14, lookback=200))

    results = []
    for params in grid:
        z = detect_all_zones(candles, **params)
        s = run_statistical_test(candles, z, random.Random(args.seed))
        if s is not None and s["zone_reaction_rate"] is not None:
            results.append((params, len(z), s["zone_reaction_rate"], s["zone_tests"]))

    if results:
        rates = [r[2] for r in results]
        print(f"  {len(results)}/{len(grid)} combinaisons ont produit au moins une zone testable.")
        print(f"  Taux de réaction : min={min(rates):.1%}  max={max(rates):.1%}  "
              f"moyenne={statistics.mean(rates):.1%}  écart-type={statistics.pstdev(rates):.1%}")
        print("  Détail (10 premières combinaisons) :")
        for params, nzones, rate, n in results[:10]:
            print(f"    k1={params['k1']:.2f} k2={params['k2']:.2f} p={params['p']:.2f} "
                  f"cd={params['cluster_distance_mult']:.2f} -> zones={nzones:3d} rate={rate:.1%} (n={n})")
    else:
        print("  Aucune combinaison n'a produit de zone testable.")


if __name__ == "__main__":
    main()
