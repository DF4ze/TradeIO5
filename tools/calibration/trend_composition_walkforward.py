"""
Walk-forward de la composition Trend unifié (cf. docs/etudes/spec-composition-trend-unifie.md).

Entrée : target/trend-composition-walkforward/features-btc-d1.csv, produit par le runner Java
TrendCompositionFeatureExportManualRunnerTest (briques de production, causales).
Sorties (target/trend-composition-walkforward/) :
  - rapport-walkforward.md       : résultats par candidate / fold, épisodes range+breakout, scénario synthétique
  - timeline-trend.csv / .json   : série jour par jour (prix, régime, scores des candidates) pour la visualisation

Itératif : grilles / candidates / horizon modifiables en tête de fichier, relance sans recompiler.
    python3 tools/calibration/trend_composition_walkforward.py
"""
import json
import math
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "target" / "trend-composition-walkforward"
FEATURES = OUT / "features-btc-d1.csv"

# ---------------------------------------------------------------- paramètres du protocole
HORIZON = 10                      # jours, rendement futur de la métrique principale
HORIZONS_CTRL = (5, 20)
TEST_YEARS = list(range(2020, 2027))
NEUTRAL_BARRIER = 1 / 6           # MarketOpinionHelper.scoreToConfidenceAndSignalType
ADX_LOW, ADX_HIGH = 15.0, 25.0
KAPPA = 0.5                       # pénalité WARNING_* (figée, non calibrée)
THETAS = (0.1, 0.2, 0.3)
SLOPE_SCALES = (25, 50, 100, 200, 400, 800)
WEIGHTS = ((0.2, 0.3, 0.5), (1 / 3, 1 / 3, 1 / 3), (0.5, 0.3, 0.2), (0.1, 0.2, 0.7), (0.0, 0.3, 0.7))
ALIGN = (True, False)
DEFAULT_REG = (100, (0.2, 0.3, 0.5), True)
# version lissée (C2L)
DEBOUNCE_DAYS = 3                 # le côté structurel ne bascule qu'après 3 jours consécutifs
REVERSAL_PERSIST = 2              # reversalCandidate exige la condition 2 jours de suite
EMA_SPAN = 5                      # lissage exponentiel du composite

EPISODES = [  # range étendu + breakout rapide (dates de breakout à lire sur le graphe)
    ("Range été 2020 -> breakout oct. 2020", "2020-06-01", "2020-10-07", "2020-11-15"),
    ("Range mars-oct. 2024 -> breakout nov. 2024", "2024-03-15", "2024-11-05", "2024-12-15"),
]

STRUCT = {"BULL_CONFIRMED": 1, "WARNING_BEAR_BREAK": 1, "BEAR_CONFIRMED": -1, "WARNING_BULL_BREAK": -1, "UNDEFINED": 0}
TOSCORE = {"BULL_CONFIRMED": 1.0, "WARNING_BULL_BREAK": 0.5, "UNDEFINED": 0.0, "WARNING_BEAR_BREAK": -0.5, "BEAR_CONFIRMED": -1.0}


# ---------------------------------------------------------------- briques
def reg_score(df, k, w, align):
    """Réplique de RegressiveTrendStrategy#evaluate (contre-vérifiée vs regDefault)."""
    sig = np.stack([np.tanh(df[f"slope{p}"].to_numpy() * k) for p in (7, 14, 30)], axis=1)
    r2 = np.stack([df[f"r2{p}"].to_numpy() for p in (7, 14, 30)], axis=1)
    wv = np.array(w)
    ws = (sig * r2 * wv).sum(axis=1) / wv.sum()
    if align:
        s = np.sign(sig)
        agree = (s[:, 0] == s[:, 1]).astype(int) + (s[:, 0] == s[:, 2]) + (s[:, 1] == s[:, 2])
        ws = ws * (0.4 + 0.6 * agree / 3)
    return np.clip(ws, -1, 1)


def force(df):
    return np.clip((df.adx.to_numpy() - ADX_LOW) / (ADX_HIGH - ADX_LOW), 0, 1)


def run_length(regime):
    r = (regime != regime.shift()).cumsum()
    return regime.groupby(r).cumcount().to_numpy() + 1


def baseline(df, frc):
    rl = run_length(df.regime)
    conf = np.minimum(1, rl / 30)
    warn = df.regime.str.startswith("WARNING").to_numpy()
    conf = np.where(warn, conf * 0.5, conf)
    conf = np.where(df.regime == "UNDEFINED", 0, conf)
    return df.regime.map(TOSCORE).to_numpy() * frc * conf, np.zeros(len(df), bool)


def c1(df, reg, frc, theta):
    sd = df.regime.map(STRUCT).to_numpy()
    warn = df.regime.str.startswith("WARNING").to_numpy()
    bd = np.where(warn, -sd, 0)
    conf_c = np.maximum(0, sd * reg)
    rev = warn & (bd * reg >= theta)
    dirn = np.where(warn, np.where(rev, bd, 0), sd)
    conf = np.where(warn, np.where(rev, KAPPA * np.abs(reg), 0), conf_c)
    return dirn * frc * conf, rev


def c2(df, reg, frc, theta, sd=None, persist=1):
    if sd is None:
        sd = df.regime.map(STRUCT).to_numpy()
    warn = df.regime.str.startswith("WARNING").to_numpy()
    bd = np.where(warn, -df.regime.map(STRUCT).to_numpy(), 0)
    cond = warn & (bd * reg >= theta)
    if persist > 1:
        c = pd.Series(cond.astype(int))
        cond = (c.rolling(persist).sum() == persist).to_numpy()
    g = np.maximum(0, sd * reg)
    conf = np.where(warn, np.where(cond, 0, KAPPA * g), g)
    conf = np.where(sd == 0, 0, conf)
    return sd * frc * conf, cond


def c3(df, reg, frc, theta):
    sd = df.regime.map(STRUCT).to_numpy()
    warn = df.regime.str.startswith("WARNING").to_numpy()
    undef = (df.regime == "UNDEFINED").to_numpy()
    bd = np.where(warn, -sd, 0)
    confirmed = ~warn & ~undef
    comp = np.where(confirmed, np.where(np.sign(reg) == sd, reg, 0), reg * KAPPA)
    return comp, warn & (bd * reg >= theta)


def debounced_struct(df, k):
    raw = df.regime.map(STRUCT).to_numpy()
    out = np.zeros_like(raw)
    cur, pend, cnt = 0, 0, 0
    for i, v in enumerate(raw):
        if cur == 0:
            cur = v
        elif v != cur and v != 0:
            cnt = cnt + 1 if v == pend else 1
            pend = v
            if cnt >= k:
                cur, cnt = v, 0
        else:
            cnt = 0
        out[i] = cur
    return out


def c2l(df, reg, frc, theta):
    sd = debounced_struct(df, DEBOUNCE_DAYS)
    comp, rev = c2(df, reg, frc, theta, sd=sd, persist=REVERSAL_PERSIST)
    return pd.Series(comp).ewm(span=EMA_SPAN, adjust=False).mean().to_numpy(), rev


def ema(x, span):
    return pd.Series(x).ewm(span=span, adjust=False).mean().to_numpy()


# ---------------------------------------------------------------- indicateurs de tendance classiques (référence externe)
# Paramètres standards du marché, non calibrés : ce sont les réglages "que tout le monde utilise".
def add_classic_indicators(raw):
    c, h, l = raw.close, raw.high, raw.low
    raw["cl_sma200"] = np.sign(c - c.rolling(200).mean())
    e = lambda n: c.ewm(span=n, adjust=False).mean()
    raw["cl_ema50_200"] = np.sign(e(50) - e(200)).where(c.index >= 199)
    raw["cl_ema20_50"] = np.sign(e(20) - e(50)).where(c.index >= 49)
    raw["cl_macd"] = np.sign(e(12) - e(26)).where(c.index >= 33)
    # Supertrend (ATR Wilder 10, multiplicateur 3)
    tr = pd.concat([h - l, (h - c.shift()).abs(), (l - c.shift()).abs()], axis=1).max(axis=1)
    atr = tr.ewm(alpha=1 / 10, adjust=False).mean().to_numpy()
    hl2 = ((h + l) / 2).to_numpy(); cc = c.to_numpy()
    ub, lb = hl2 + 3 * atr, hl2 - 3 * atr
    fu, fl = ub.copy(), lb.copy(); st = np.zeros(len(cc)); d = 1
    for i in range(1, len(cc)):
        fu[i] = ub[i] if (ub[i] < fu[i - 1] or cc[i - 1] > fu[i - 1]) else fu[i - 1]
        fl[i] = lb[i] if (lb[i] > fl[i - 1] or cc[i - 1] < fl[i - 1]) else fl[i - 1]
        if d == 1 and cc[i] < fl[i]:
            d = -1
        elif d == -1 and cc[i] > fu[i]:
            d = 1
        st[i] = d
    st[:10] = np.nan
    raw["cl_supertrend"] = st
    return raw


def hysteresis(score, enter=NEUTRAL_BARRIER, leave=0.0):
    """Etat discret avec hystérésis : entre en hausse au-dessus de +enter, n'en sort que sous leave (symétrique)."""
    out = np.zeros(len(score)); cur = 0
    for i, v in enumerate(score):
        if cur == 1 and v < leave:
            cur = 0
        elif cur == -1 and v > -leave:
            cur = 0
        if cur == 0:
            cur = 1 if v > enter else (-1 if v < -enter else 0)
        out[i] = cur
    return out


def classic(col):
    return lambda df, reg, frc, th: (df[col].fillna(0).to_numpy(), np.zeros(len(df), bool))


CANDIDATES = {
    "BASELINE": lambda df, reg, frc, th: baseline(df, frc),
    "REG_SEULE": lambda df, reg, frc, th: (reg, np.zeros(len(df), bool)),
    "REG_L5": lambda df, reg, frc, th: (ema(reg, 5), np.zeros(len(df), bool)),
    "REG_L10": lambda df, reg, frc, th: (ema(reg, 10), np.zeros(len(df), bool)),
    "C1": c1, "C2": c2, "C3": c3, "C2L": c2l,
    "C3L5": lambda df, reg, frc, th: (lambda r: (ema(r[0], 5), r[1]))(c3(df, reg, frc, th)),
    "REG_H": lambda df, reg, frc, th: (hysteresis(reg), np.zeros(len(df), bool)),
    "SMA200": classic("cl_sma200"),
    "EMA50_200": classic("cl_ema50_200"),
    "EMA20_50": classic("cl_ema20_50"),
    "MACD": classic("cl_macd"),
    "SUPERTREND": classic("cl_supertrend"),
}
USES_THETA = {"C1", "C2", "C3", "C2L", "C3L5"}
USES_REG = {"REG_SEULE", "REG_L5", "REG_L10", "REG_H", "C1", "C2", "C3", "C2L", "C3L5"}


# ---------------------------------------------------------------- vérité terrain ex-post
ZIGZAG_THRESHOLD = 0.15           # jambe de tendance "tracée à la main" : retournement >= 15 %


def zigzag_legs(close, thr=ZIGZAG_THRESHOLD):
    """Label ex-post (utilise le futur, jamais une entrée des candidates) : +1 si le jour appartient à
    une jambe haussière entre deux pivots ZigZag >= thr, -1 si baissière."""
    n = len(close)
    piv = [0]
    direction = 0
    ext = 0
    for i in range(1, n):
        if direction >= 0:
            if close[i] > close[ext]:
                ext = i
            if direction == 0 and close[i] < close[ext] * (1 - thr):
                direction = -1; piv.append(ext); ext = i
            elif direction == 1 and close[i] < close[ext] * (1 - thr):
                piv.append(ext); direction = -1; ext = i
            if direction == 0 and close[i] > close[0] * (1 + thr):
                direction = 1
        else:
            if close[i] < close[ext]:
                ext = i
            if close[i] > close[ext] * (1 + thr):
                piv.append(ext); direction = 1; ext = i
    piv.append(n - 1)
    lab = np.zeros(n, int)
    for a, b in zip(piv[:-1], piv[1:]):
        lab[a:b + 1] = 1 if close[b] >= close[a] else -1
    return lab


def agreement(score, legs):
    """Accord équilibré avec la tendance réelle, dans [-1, 1] : moyenne sur jambes hausse et baisse de
    (jours dans le bon sens - jours à contresens) / jours. Neutre = 0. Insensible au biais haussier de BTC."""
    d = discrete(score)
    parts = [float(np.mean(d[legs == s] * s)) for s in (1, -1) if (legs == s).any()]
    return float(np.mean(parts)) if parts else float("nan")


# ---------------------------------------------------------------- métriques
def spearman(a, b):
    m = ~(np.isnan(a) | np.isnan(b))
    a, b = a[m], b[m]
    if len(a) < 20 or np.std(a) == 0:
        return float("nan")
    return float(pd.Series(a).rank().corr(pd.Series(b).rank()))


def discrete(score):
    return np.where(score > NEUTRAL_BARRIER, 1, np.where(score < -NEUTRAL_BARRIER, -1, 0))


def metrics(score, fwd, drift, legs=None, legs_macro=None):
    d = discrete(score)
    active = d != 0
    changes = int((d[1:] != d[:-1]).sum())
    gain = float(np.nanmean(d[active] * (fwd[active] - drift))) if active.any() else float("nan")
    res = {
        "accord": agreement(score, legs) if legs is not None else float("nan"),
        "accord_macro": agreement(score, legs_macro) if legs_macro is not None else float("nan"),
        "IC": spearman(score, fwd),
        "gain_bp": gain * 1e4,
        "couverture": float(active.mean()),
        "changements_an": changes / (len(d) / 365),
    }
    return res


def reversal_precision(df, rev, window=20):
    """Part des reversalCandidate (1ère occurrence d'un run) suivis d'un régime confirmé dans le sens de la cassure sous `window` j."""
    reg = df.regime.to_numpy()
    sd = df.regime.map(STRUCT).to_numpy()
    starts = np.where(rev & ~np.r_[False, rev[:-1]])[0]
    ok = 0
    for i in starts:
        target = "BEAR_CONFIRMED" if sd[i] > 0 else "BULL_CONFIRMED"
        fut = reg[i + 1:i + 1 + window]
        opp = "BULL_CONFIRMED" if target == "BEAR_CONFIRMED" else "BEAR_CONFIRMED"
        for r in fut:
            if r == target:
                ok += 1
                break
            if r == opp:
                break
    return (ok / len(starts) if len(starts) else float("nan")), len(starts)


# ---------------------------------------------------------------- réplique swing (scénario synthétique)
def swing_regimes(h, l):
    ch, cl = h[0], l[0]
    lh = ll = None  # (price, cls)
    last_bull = None
    regime = "UNDEFINED"
    out = [regime]
    for i in range(1, len(h)):
        he, le = h[i] > ch, l[i] < cl
        if he:
            ch = h[i]
        if le:
            cl = l[i]
        conf = False
        if le and not he:
            cls = None if lh is None else ("HH" if ch > lh[0] else "LH")
            lh = (ch, cls); ch = h[i]; conf = True
        elif he and not le:
            cls = None if ll is None else ("HL" if cl > ll[0] else "LL")
            ll = (cl, cls); cl = l[i]; conf = True
        if conf:
            if lh is None or ll is None or lh[1] is None or ll[1] is None:
                regime = "UNDEFINED"
            elif lh[1] == "HH" and ll[1] == "HL":
                regime, last_bull = "BULL_CONFIRMED", True
            elif lh[1] == "LH" and ll[1] == "LL":
                regime, last_bull = "BEAR_CONFIRMED", False
            elif last_bull is None:
                regime = "UNDEFINED"
            else:
                regime = "WARNING_BEAR_BREAK" if last_bull else "WARNING_BULL_BREAK"
        out.append(regime)
    return out


def lr_features(close, p):
    slope, r2 = np.full(len(close), np.nan), np.full(len(close), np.nan)
    x = np.arange(p)
    for i in range(p - 1, len(close)):
        y = close[i + 1 - p:i + 1]
        b, a = np.polyfit(x, y, 1)
        pred = a + b * x
        sst = ((y - y.mean()) ** 2).sum()
        r2[i] = 0 if sst == 0 else max(0, 1 - ((y - pred) ** 2).sum() / sst)
        slope[i] = b / y.mean()
    return slope, r2


def synthetic_scenario(best):
    rng = np.random.default_rng(42)
    n_trend, n_range, n_break, n_after = 60, 90, 10, 40
    closes = [100.0]
    for _ in range(n_trend):
        closes.append(closes[-1] * (1 - 0.004 + rng.normal(0, 0.015)))         # lente baisse
    base = closes[-1]
    for _ in range(n_range):
        closes.append(base * (1 + 0.04 * np.sin(len(closes) / 7) + rng.normal(0, 0.01)))  # range ±5 %
    for _ in range(n_break):
        closes.append(closes[-1] * 1.02)                                        # breakout +~22 %
    for _ in range(n_after):
        closes.append(closes[-1] * (1 + 0.003 + rng.normal(0, 0.012)))
    c = np.array(closes)
    h = c * (1 + np.abs(rng.normal(0, 0.006, len(c))))
    l = c * (1 - np.abs(rng.normal(0, 0.006, len(c))))
    df = pd.DataFrame({"close": c, "high": h, "low": l})
    df["regime"] = swing_regimes(h, l)
    for p in (7, 14, 30):
        df[f"slope{p}"], df[f"r2{p}"] = lr_features(c, p)
    df["adx"] = 30.0  # force = 1 (pas d'ADX synthétique réaliste)
    df = add_classic_indicators(df)
    df = df.iloc[30:].reset_index(drop=True)
    b0 = n_trend + n_range - 30
    res = {}
    for name, (params, theta) in best.items():
        reg = reg_score(df, *params) if params else np.zeros(len(df))
        comp, rev = CANDIDATES[name](df, reg, force(df), theta)
        d = discrete(comp)
        rng_d = d[n_trend - 30 + 20:b0]  # range, hors 20 premiers jours de transition
        after = d[b0:]
        lag = next((i for i, v in enumerate(after) if v == 1), None)
        rev_before = bool(rev[b0 - 5:b0 + n_break].any())
        conf_bull = next((i for i, r in enumerate(df.regime[b0:]) if r == "BULL_CONFIRMED"), None)
        res[name] = {
            "range_neutre": float((rng_d == 0).mean()),
            "range_changements": int((rng_d[1:] != rng_d[:-1]).sum()),
            "retard_bull_j": lag,
            "reversal_autour_breakout": rev_before,
            "bull_confirme_j": conf_bull,
        }
    return res


# ---------------------------------------------------------------- walk-forward
def main():
    df = add_classic_indicators(pd.read_csv(FEATURES, parse_dates=["date"]))
    df = df.dropna(subset=["adx", "slope30"]).reset_index(drop=True)
    chk = np.abs(reg_score(df, *DEFAULT_REG) - df.regDefault.to_numpy()).max()
    assert chk < 1e-6, f"réplique RegressiveTrendStrategy divergente : {chk}"

    raw = pd.read_csv(FEATURES)
    rep = swing_regimes(raw.high.to_numpy(), raw.low.to_numpy())
    swing_mismatch = int((np.array(rep) != raw.regime.to_numpy()).sum())

    close = df.close.to_numpy()
    fwd = {H: np.r_[np.log(close[H:] / close[:-H]), np.full(H, np.nan)] for H in (HORIZON, *HORIZONS_CTRL)}
    years = df.date.dt.year.to_numpy()
    legs = zigzag_legs(close)
    legs_macro = zigzag_legs(close, 0.30)   # contrôle : jambes de cycle (>= 30 %)
    frc = force(df)
    frc1 = np.ones(len(df))

    reg_grid = [(k, w, a) for k in SLOPE_SCALES for w in WEIGHTS for a in ALIGN]
    reg_cache = {p: reg_score(df, *p) for p in reg_grid}

    rows, chosen = [], {}
    for ty in TEST_YEARS:
        test = years == ty
        if not test.any():
            continue
        first = np.argmax(test)
        train = np.zeros(len(df), bool)
        train[:max(0, first - HORIZON)] = True          # purge : labels du train qui chevaucheraient le test
        f = fwd[HORIZON]
        drift = float(np.nanmean(f[train]))
        # étape 1 : régression seule calibrée sur le train
        best_reg = max(reg_grid, key=lambda p: np.nan_to_num(agreement(reg_cache[p][train], legs[train]), nan=-9))
        for name, fn in CANDIDATES.items():
            for variant, fr in (("", frc), ("_sansADX", frc1)):
                if variant and (name.startswith("REG") or name.startswith("C3") or name.startswith("cl_") or name in ("SMA200", "EMA50_200", "EMA20_50", "MACD", "SUPERTREND")):
                    continue
                for mode in ("defaut", "calibre"):
                    params = (DEFAULT_REG if mode == "defaut" else best_reg) if name in USES_REG else None
                    reg = reg_cache.get(params, reg_score(df, *params)) if params else np.zeros(len(df))
                    if name in USES_THETA:
                        if mode == "defaut":
                            theta = 0.2
                        else:
                            theta = max(THETAS, key=lambda t: np.nan_to_num(agreement(fn(df, reg, fr, t)[0][train], legs[train]), nan=-9))
                    else:
                        theta = None
                    comp, rev = fn(df, reg, fr, theta)
                    m = metrics(comp[test], f[test], drift, legs[test], legs_macro[test])
                    for H in HORIZONS_CTRL:
                        m[f"IC_{H}j"] = spearman(comp[test], fwd[H][test])
                    rp, nrev = reversal_precision(df.iloc[np.where(test)[0]].reset_index(drop=True), rev[test])
                    rows.append({"fold": ty, "candidate": name + variant, "mode": mode, "reg": str(params), "theta": theta,
                                 **m, "reversal_precision": rp, "reversal_n": nrev})
                    chosen[(ty, name + variant, mode)] = (params, theta)
    res = pd.DataFrame(rows)

    summ = (res.groupby(["candidate", "mode"])
            .agg(accord_median=("accord", "median"), accord_min=("accord", "min"), accord_macro=("accord_macro", "median"), IC_median=("IC", "median"), IC_moyen=("IC", "mean"), folds_IC_pos=("IC", lambda s: int((s > 0).sum())),
                 IC_5j=("IC_5j", "median"), IC_20j=("IC_20j", "median"), gain_bp=("gain_bp", "median"),
                 couverture=("couverture", "mean"), changements_an=("changements_an", "mean"),
                 reversal_precision=("reversal_precision", "mean"))
            .reset_index().sort_values(["mode", "accord_median"], ascending=[True, False]))

    # paramètres "finaux" : calibration sur tout l'historique (pour la visu / le scénario), pas pour l'évaluation
    f = fwd[HORIZON]
    allm = ~np.isnan(f)
    best_reg_all = max(reg_grid, key=lambda p: np.nan_to_num(agreement(reg_cache[p], legs), nan=-9))
    final = {}
    for name, fn in CANDIDATES.items():
        params = best_reg_all if name in USES_REG else None
        reg = reg_cache[params] if params else np.zeros(len(df))
        theta = max(THETAS, key=lambda t: np.nan_to_num(agreement(fn(df, reg, frc, t)[0], legs), nan=-9)) if name in USES_THETA else None
        final[name] = (params, theta)

    # épisodes réels
    ep_rows = []
    for label, a, b, e in EPISODES:
        rng_m = (df.date >= a) & (df.date < b)
        aft = (df.date >= b) & (df.date < e)
        for name, (params, theta) in final.items():
            reg = reg_cache[params] if params else np.zeros(len(df))
            comp, rev = CANDIDATES[name](df, reg, frc, theta)
            d = discrete(comp)
            dr, da = d[rng_m.to_numpy()], d[aft.to_numpy()]
            lag = next((i for i, v in enumerate(da) if v == 1), None)
            ep_rows.append({"episode": label, "candidate": name, "range_neutre": float((dr == 0).mean()),
                            "range_bull": float((dr == 1).mean()), "range_bear": float((dr == -1).mean()),
                            "range_changements": int((dr[1:] != dr[:-1]).sum()), "retard_bull_j": lag,
                            "reversal_dans_range": int(rev[rng_m.to_numpy()].sum())})
    ep = pd.DataFrame(ep_rows)
    syn = synthetic_scenario(final)

    # timeline pour la visu
    tl = pd.DataFrame({"date": df.date.dt.strftime("%Y-%m-%d"), "close": df.close, "regime": df.regime,
                       "structDir": df.regime.map(STRUCT)})
    for name, (params, theta) in final.items():
        reg = reg_cache[params] if params else np.zeros(len(df))
        comp, rev = CANDIDATES[name](df, reg, frc, theta)
        tl[name] = np.round(comp, 4)
        tl[name + "_rev"] = rev.astype(int)
    tl["reg"] = np.round(reg_cache[best_reg_all], 4)
    tl["zigzag"] = legs
    tl["zigzag30"] = legs_macro
    tl.to_csv(OUT / "timeline-trend.csv", index=False)
    (OUT / "timeline-trend.json").write_text(json.dumps({
        "params": {k: {"reg": str(v[0]), "theta": v[1]} for k, v in final.items()},
        "rows": tl.to_dict(orient="list")}))

    # rapport
    fmt = lambda x: "" if x is None or (isinstance(x, float) and math.isnan(x)) else (f"{x:.3f}" if isinstance(x, float) else str(x))
    md = ["# Walk-forward composition Trend unifié — BTC D1\n",
          f"- Données : {df.date.min().date()} → {df.date.max().date()} ({len(df)} jours exploitables)",
          f"- Contre-vérif. réplique `RegressiveTrendStrategy` : écart max {chk:.2e} ; réplique swing Python vs Java : {swing_mismatch} désaccord(s)",
          f"- Horizon principal H={HORIZON} j ; folds de test annuels {TEST_YEARS[0]}–{TEST_YEARS[-1]} ; train ancré + purge {HORIZON} j",
          f"- Grille régression : {len(reg_grid)} points ; θ ∈ {THETAS} ; κ={KAPPA} ; C2L : debounce {DEBOUNCE_DAYS} j, persistance reversal {REVERSAL_PERSIST} j, EMA {EMA_SPAN}",
          f"- Paramètres calibrés sur tout l'historique (visu uniquement) : régression {best_reg_all}",
          "\n## Synthèse (médiane des folds)\n", summ.to_markdown(index=False, floatfmt=".3f"),
          "\n## Accord équilibré par fold (mode calibré)\n",
          res[res["mode"] == "calibre"].pivot(index="candidate", columns="fold", values="accord").to_markdown(floatfmt=".3f"),
          "\n## Épisodes range étendu + breakout (paramètres finaux)\n", ep.to_markdown(index=False, floatfmt=".3f"),
          "\n## Scénario synthétique (60 j baisse, 90 j range ±5 %, breakout +22 % en 10 j)\n",
          pd.DataFrame(syn).T.to_markdown(),
          "\n## Paramètres finaux par candidate\n",
          "\n".join(f"- {k} : reg={v[0]}, θ={v[1]}" for k, v in final.items())]
    (OUT / "rapport-walkforward.md").write_text("\n".join(md), encoding="utf-8")
    res.to_csv(OUT / "resultats-folds.csv", index=False)
    print("\n".join(md))


if __name__ == "__main__":
    main()
