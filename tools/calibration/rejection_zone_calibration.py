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
from datetime import datetime, timedelta


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
            raw_ts = row.get("timestamp", len(candles))
            # Timestamp réel (ISO-8601, ex: export fetch_real_klines.py / BucketResample.java) ->
            # datetime, pour permettre une fenêtre de tenue en durée calendaire (cf.
            # reaction_rate_per_level) plutôt qu'un nombre fixe de bougies. Reste un entier simple
            # pour la série synthétique (generate_synthetic_series, pas de vraie notion de temps).
            try:
                ts = datetime.fromisoformat(str(raw_ts).replace("Z", "+00:00"))
            except ValueError:
                ts = raw_ts
            candles.append({
                "timestamp": ts,
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

def reaction_rate_per_level(levels, candles, start_index, touch_tolerance, hold_margin,
                             lookahead_candles=5, hold_days=None):
    """Même logique que l'ancien `reaction_rate` (repère chaque "approche" d'un niveau, teste si
    les bougies suivantes tiennent ou cassent), mais retourne le détail **par niveau** (holds,
    total) plutôt qu'un agrégat global immédiat — nécessaire pour agréger ensuite de plusieurs
    façons (brut, pondéré par `strength`, filtré par seuil de force). `levels` est une liste de
    `(level, weight)` ; `weight` n'intervient pas dans le calcul par niveau, il est juste reporté
    tel quel pour l'agrégation en aval (cf. `run_statistical_test`).
    <p>
    **Fenêtre de "tenue" normalisée en durée calendaire, pas en nombre de bougies** (cf.
    docs/calibration-rejection-zone.md, run du 2026-07-09) : un nombre fixe de bougies ("tient sur
    les 5 prochaines") ne veut pas dire la même chose selon le TF (5h en H1, 5 jours en D1, plus
    d'un mois en W1) — comparer des taux de réaction obtenus avec des fenêtres temporelles aussi
    différentes n'est pas une comparaison honnête entre TF. Si `hold_days` est fourni ET que les
    timestamps des candles sont de vrais `datetime` (CSV réel, pas la série synthétique), la
    fenêtre est bornée en jours calendaires réels, identique quel que soit le TF. Sinon (série
    synthétique sans vrai temps, ou `hold_days=None`), on retombe sur l'ancien comportement à
    nombre de bougies fixe (`lookahead_candles`)."""
    use_calendar = hold_days is not None and len(candles) > 0 and isinstance(candles[0]["timestamp"], datetime)

    results = []
    for level, weight in levels:
        holds = 0
        total = 0
        in_touch = False
        for i in range(start_index, len(candles) - 1):
            c = candles[i]
            touching = c["low"] - touch_tolerance <= level <= c["high"] + touch_tolerance
            if touching and not in_touch:
                was_above = c["close"] >= level
                broke = False
                if use_calendar:
                    deadline = c["timestamp"] + timedelta(days=hold_days)
                    j = i + 1
                    while j < len(candles) and candles[j]["timestamp"] <= deadline:
                        nxt = candles[j]
                        if (was_above and nxt["close"] < level - hold_margin) or \
                                (not was_above and nxt["close"] > level + hold_margin):
                            broke = True
                            break
                        j += 1
                else:
                    for j in range(i + 1, min(i + 1 + lookahead_candles, len(candles))):
                        nxt = candles[j]
                        if (was_above and nxt["close"] < level - hold_margin) or \
                                (not was_above and nxt["close"] > level + hold_margin):
                            broke = True
                            break
                total += 1
                if not broke:
                    holds += 1
            in_touch = touching
        results.append((weight, holds, total))
    return results


def _aggregate(per_level, weighted=False):
    if weighted:
        total = sum(w * t for w, h, t in per_level)
        holds = sum(w * h for w, h, t in per_level)
    else:
        total = sum(t for w, h, t in per_level)
        holds = sum(h for w, h, t in per_level)
    rate = holds / total if total > 0 else None
    return rate, total


def run_statistical_test(candles, zones, rng, min_strength=None, hold_days=None):
    """Test statistique en 3 lectures (cf. docs/calibration-rejection-zone.md, run du 2026-07-09) :
    taux "brut" (toutes zones à égalité), **pondéré par `strength`**, et **filtré** (uniquement les
    zones au-dessus d'un seuil de force, médiane du run par défaut) — comparaison, pas substitution.
    <p>
    `hold_days` (si fourni, avec des candles à vrais timestamps) normalise la fenêtre de "tenue" en
    durée calendaire plutôt qu'en nombre fixe de bougies, pour permettre une comparaison honnête
    entre TF (cf. reaction_rate_per_level) — sinon comportement historique à 5 bougies."""
    if not zones:
        return None

    price_min = min(c["low"] for c in candles)
    price_max = max(c["high"] for c in candles)
    avg_range = statistics.mean(c["high"] - c["low"] for c in candles)
    touch_tolerance = avg_range * 0.25
    hold_margin = avg_range * 0.5

    strengths = [z.strength for z in zones]
    threshold = min_strength if min_strength is not None else statistics.median(strengths)

    zone_levels = [(z.level, z.strength) for z in zones]
    per_level = reaction_rate_per_level(zone_levels, candles, 0, touch_tolerance, hold_margin, hold_days=hold_days)

    raw_rate, raw_tests = _aggregate(per_level, weighted=False)
    weighted_rate, weighted_tests = _aggregate(per_level, weighted=True)

    filtered = [(w, h, t) for w, h, t in per_level if w >= threshold]
    filtered_rate, filtered_tests = _aggregate(filtered, weighted=False)
    filtered_zone_count = len(filtered)

    random_levels = [(rng.uniform(price_min, price_max), 1.0) for _ in zones]
    random_per_level = reaction_rate_per_level(random_levels, candles, 0, touch_tolerance, hold_margin, hold_days=hold_days)
    random_rate, random_tests = _aggregate(random_per_level, weighted=False)

    return {
        # Compat avec le code existant (grille de sensibilité) : taux brut, mêmes clés qu'avant.
        "zone_reaction_rate": raw_rate,
        "zone_tests": raw_tests,
        "weighted_reaction_rate": weighted_rate,
        "weighted_tests": weighted_tests,
        "filtered_reaction_rate": filtered_rate,
        "filtered_tests": filtered_tests,
        "filtered_zone_count": filtered_zone_count,
        "filtered_threshold": threshold,
        "random_reaction_rate": random_rate,
        "random_tests": random_tests,
    }


# --------------------------------------------------------------------------------------
# 4. Orchestration
# --------------------------------------------------------------------------------------

DEFAULT_PARAMS = dict(k1=1.5, k2=0.5, p=0.66, cluster_distance_mult=0.5, atr_period=14, lookback=200)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", help="Chemin d'un CSV OHLCV réel (timestamp,open,high,low,close,volume)")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--lookback", type=int, default=None,
                         help="Surcharge DEFAULT_PARAMS['lookback'] (200 par défaut) — utile pour "
                              "scanner une fenêtre plus large selon le TF (ex: W1 sur plusieurs années).")
    parser.add_argument("--label", default=None, help="Etiquette affichée dans les logs (ex: 'W1')")
    parser.add_argument("--min-strength", type=float, default=None,
                         help="Seuil de force pour le taux de réaction 'filtré' (défaut : médiane des "
                              "zones détectées sur ce run).")
    parser.add_argument("--hold-days", type=float, default=None,
                         help="Fenêtre de 'tenue' normalisée en jours calendaires réels (ex: 5), "
                              "identique quel que soit le TF, au lieu de l'ancien comportement à "
                              "5 bougies fixes (qui ne veut pas dire la même durée en H1 vs D1 vs W1). "
                              "Nécessite un CSV réel (timestamps ISO), ignoré sur série synthétique.")
    args = parser.parse_args()

    if args.lookback is not None:
        DEFAULT_PARAMS["lookback"] = args.lookback

    if args.csv:
        candles = load_csv(args.csv)
        source = f"CSV réel: {args.csv}"
    else:
        candles = generate_synthetic_series(seed=args.seed)
        source = "série synthétique (generate_synthetic_series) — PAS des données de marché réelles, cf. avertissement en tête de fichier"

    print(f"=== RejectionZone — protocole de validation ===")
    if args.label:
        print(f"Label: {args.label}")
    print(f"Source: {source}")
    print(f"Bougies: {len(candles)}")
    print(f"Params: {DEFAULT_PARAMS}")
    print()

    # ---- Étape 1 : calibration visuelle (résumé texte des zones sur les paramètres par défaut) ----
    zones = detect_all_zones(candles, **DEFAULT_PARAMS)
    print(f"--- ZONES DETECTEES (params par défaut {DEFAULT_PARAMS}) ---")
    zones_sorted = sorted(zones, key=lambda z: -z.strength)
    for z in zones_sorted[:15]:
        print(f"  {z.direction:10s} level={z.level:10.2f}  touches={z.touches}  strength={z.strength:.3f}")
    print(f"  Total zones: {len(zones)}")
    print()

    # ---- Étape 2 : test statistique (taux de réaction vs baseline aléatoire, 3 lectures) ----
    rng = random.Random(args.seed)
    stats = run_statistical_test(candles, zones, rng, min_strength=args.min_strength, hold_days=args.hold_days)
    print("--- TEST STATISTIQUE (taux de réaction) ---")
    if args.hold_days is not None:
        print(f"  (fenêtre de tenue normalisée : {args.hold_days} jours calendaires)")
        if len(candles) >= 2 and isinstance(candles[0]["timestamp"], datetime):
            avg_spacing_days = (candles[-1]["timestamp"] - candles[0]["timestamp"]).total_seconds() \
                                / (len(candles) - 1) / 86400
            if args.hold_days < avg_spacing_days:
                print(f"  ⚠ hold_days ({args.hold_days}) < espacement moyen des bougies "
                      f"({avg_spacing_days:.2f} jours) : la fenêtre de tenue ne contient alors "
                      f"AUCUNE bougie suivante à tester -> tout 'tient' par construction (taux "
                      f"artificiellement à 100%, pas un vrai résultat). Augmenter --hold-days pour "
                      f"ce TF.")
    if stats is None:
        print("  Aucune zone détectée avec les paramètres par défaut : impossible de calculer un taux de réaction.")
    else:
        print(f"  Brut (toutes zones à égalité)      : {stats['zone_reaction_rate']:.1%}  ({stats['zone_tests']} tests)")
        if stats['weighted_reaction_rate'] is not None:
            print(f"  Pondéré par strength                : {stats['weighted_reaction_rate']:.1%}  "
                  f"(poids total {stats['weighted_tests']:.1f})")
        else:
            print(f"  Pondéré par strength                : n/a")
        if stats['filtered_reaction_rate'] is not None:
            print(f"  Filtré (strength >= {stats['filtered_threshold']:.3f})       : "
                  f"{stats['filtered_reaction_rate']:.1%}  ({stats['filtered_tests']} tests, "
                  f"{stats['filtered_zone_count']}/{len(zones)} zones retenues)")
        else:
            print(f"  Filtré (strength >= {stats['filtered_threshold']:.3f})       : n/a (aucune zone au-dessus du seuil)")
        print(f"  Niveaux aléatoires                  : {stats['random_reaction_rate']:.1%}  ({stats['random_tests']} tests)")
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
        s = run_statistical_test(candles, z, random.Random(args.seed), hold_days=args.hold_days)
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
