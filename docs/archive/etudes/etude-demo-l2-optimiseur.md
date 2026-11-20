---
statut: archivé
sujet: Étape 3 — démo L2 réelle de l'optimiseur (BTC, ETH, PAXG, profil Équilibré) et diagnostic de reproductibilité : mesures complètes
remplacé_par: Claude
archivé_le: 2026-11-20
raison: Étude close : décision reprise dans decisions/full-autonome-v1.md
modifié_le: 2026-11-20
empreinte: 15c17a10bc
---
# Étude — Démo L2 de l'optimiseur et reproductibilité (étape 3, 2026-11-16)

> ⚠ **ARCHIVÉ le 2026-11-20** — Étude close : décision reprise dans decisions/full-autonome-v1.md. Remplacé par `Claude`. Ne jamais lire comme référence de l'état courant.

Synthèse et réponses : [`etude-optimisation-mode-autonome.md`](../../etudes/etude-optimisation-mode-autonome.md) §11.3. Moteur : [`../architecture/15-optimiseur.md`](../../architecture/15-optimiseur.md). Runner : `OptimizerManualRunnerTest` (`-Doptimizer.manual=repro|l2`), base locale copie de prod, utilisateur admin, profil Équilibré, graine 20261116, pool 4 threads, coûts complets statiques.

Méthode : entraînement sur des fenêtres 12 m (poids 3) / 24 m (2) / historique complet (1) finissant le 2025-11-15, capital BTC 2 484, ETH 2 425, PAXG 1 062 USDC ; **hors échantillon** = les 12 mois suivants (2025-11-15 → 2026-11-15), même candidat. Espace : bandes par régime `bullMin`, `bullMax`, `bearMin`, `bearMax`, plafond d'achat ATH `buyCap`, montant de base `baseAmount`. « Départ » = bande 20–90 / 0–30 et plafond du profil. Exposition / timing / frais : répartition du gain vs DCA fixe en USDC (`EpisodeTimeline`). PAXG : seule la fenêtre « full » (7 mois) est éligible à l'entraînement (Kraken : 754 bougies D1).

## Diagnostic de reproductibilité
### Défauts du simulateur complet (BTC, wallet 1, aucun paramètre fourni)
- plage par défaut : 2024-11-15 → 2026-11-15 (glisse avec l'horloge)
- capital : plan=null ; chargé=2708.58 (valeur du wallet réel du jour)
- réglages de modulation utilisateur effectifs : ModulationSettings[exposureMin=0.0, exposureMax=10.0, power=1.0, bias=0.0, regulationMarginPct=20.0]

### Scorer, entrées explicites (BTC, fenêtres 12m / 24m / full)
| Cas | Capital | Fin | Final 24m | DCA fixe 24m | Gain 24m % | Empreinte |
|---|---|---|---|---|---|---|
| 2 runs identiques | 2484.00 | 2026-11-15 | 2510 | 1672 | +50.14 | CandleFingerprint[asset=BTC, firstDay=2018-01-11, lastDay=2026-11-15, count=3231, checksum=f83f4eab47945246] |
| 2 runs identiques | 2484.00 | 2026-11-14 | 2498 | 1643 | +51.99 | CandleFingerprint[asset=BTC, firstDay=2018-01-11, lastDay=2026-11-14, count=3230, checksum=5afd6f328976c2b2] |
| 2 runs identiques | 2484.00 | 2026-10-16 | 2679 | 2206 | +21.48 | CandleFingerprint[asset=BTC, firstDay=2018-01-11, lastDay=2026-10-16, count=3201, checksum=0c1aeacfc54bbb63] |
| 2 runs identiques | 2708.58 | 2026-11-15 | 2739 | 1799 | +52.24 | CandleFingerprint[asset=BTC, firstDay=2018-01-11, lastDay=2026-11-15, count=3231, checksum=f83f4eab47945246] |
| 2 runs identiques | 2708.58 | 2026-11-14 | 2726 | 1768 | +54.14 | CandleFingerprint[asset=BTC, firstDay=2018-01-11, lastDay=2026-11-14, count=3230, checksum=5afd6f328976c2b2] |
| 2 runs identiques | 2708.58 | 2026-10-16 | 2920 | 2375 | +22.93 | CandleFingerprint[asset=BTC, firstDay=2018-01-11, lastDay=2026-10-16, count=3201, checksum=0c1aeacfc54bbb63] |

## Run 1 — tous les paramètres libres
Pool d'évaluation : 4 threads ; profil BALANCED ; graine 20261116.

### BTC
Entraînement : fenêtres finissant le 2025-11-15 ; capital 2484.0 USDC ; 209 essais ; 5597 ms au total ; 26.6 ms/run.
Sensibilités (effet en points de score) : [buyCap=4.37, bullMin=2.98, bearMax=1.89, bullMax=0.21, baseAmount=0.00, bearMin=0.00]
Retenu : {baseAmount=1.0, bearMax=25.0, bearMin=25.0, bullMax=60.0, bullMin=10.0, buyCap=14.0} ; score -2.22 ; plateau -2.27 (12/12 voisins) ; robuste=false (pire écart 171 %).

**Entraînement, candidat retenu** — score -2.22
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 12m | 2024-11-15 | 2025-11-15 | 2543 | 2461 | +3.33 | -3.6 | 10.5 | +3 | +81 | -2 | 0 |
| 24m | 2023-11-15 | 2025-11-15 | 2813 | 2713 | +3.69 | -5.7 | 13.5 | -86 | +191 | -5 | 1 |
| full | 2018-07-10 | 2025-11-15 | 6005 | 16085 | -62.66 | -23.6 | 18.6 | -7659 | -2363 | -58 | 2 |
Timing pur (hors score) : +3.30 %
Bousculades : [fin -1 j -2.22, fin -7 j -3.52 HORS TOLÉRANCE, fin -15 j -5.38 HORS TOLÉRANCE, fin -30 j -6.03 HORS TOLÉRANCE, voisin bullMin- -2.72, voisin bullMin+ -2.43, voisin bullMax- -2.18, voisin bullMax+ -2.27, voisin bearMin- -2.29, voisin bearMin+ -2.17, voisin bearMax- -2.29, voisin bearMax+ -2.17, voisin buyCap- -2.22, voisin buyCap+ -2.22, voisin baseAmount- -2.86 HORS TOLÉRANCE, voisin baseAmount+ -2.58]
Vetos rencontrés : {DEGENERATE=36, DRAWDOWN=38}

**Hors échantillon (2025-11-15 → 2026-11-15), candidat retenu** — score 2.29
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hors-échantillon 12m | 2025-11-15 | 2026-11-15 | 2492 | 2436 | +2.29 | -5.9 | 17.8 | -110 | +171 | -6 | 1 |

**Hors échantillon, point de départ (bande 20–90 / 0–30, plafond du profil)** — score 0.66
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hors-échantillon 12m | 2025-11-15 | 2026-11-15 | 2452 | 2436 | +0.66 | -5.4 | 10.5 | -35 | +52 | -1 | 5 |
Durée d'un run (mono-thread, 5 essais) : SHORT_WINDOW_SIMPLIFIED 12.1 ms (298441 runs/h/thread) ; ALL_WINDOWS_SIMPLIFIED 70.8 ms (50879 runs/h/thread) ; ALL_WINDOWS_COMPLETE 74.3 ms (48434 runs/h/thread) ; 

### ETH
Entraînement : fenêtres finissant le 2025-11-15 ; capital 2425.0 USDC ; 204 essais ; 4180 ms au total ; 20.4 ms/run.
Sensibilités (effet en points de score) : [bearMax=9.20, baseAmount=8.36, buyCap=7.85, bearMin=2.16, bullMin=1.38, bullMax=1.21]
Retenu : {baseAmount=0.5, bearMax=55.0, bearMin=20.0, bullMax=80.0, bullMin=15.0, buyCap=5.0} ; score 5.53 ; plateau 5.53 (10/11 voisins) ; robuste=false (pire écart 52 %).

**Entraînement, candidat retenu** — score 5.53
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 12m | 2024-11-15 | 2025-11-15 | 2673 | 2444 | +9.39 | -8.6 | 14.6 | +7 | +227 | -5 | 1 |
| 24m | 2023-11-15 | 2025-11-15 | 2704 | 2469 | +9.53 | -12.0 | 10.5 | +28 | +211 | -4 | 8 |
| full | 2018-07-10 | 2025-11-15 | 6030 | 9520 | -36.66 | -18.6 | 17.5 | -3065 | -392 | -33 | 12 |
Timing pur (hors score) : +8.92 %
Bousculades : [fin -1 j 5.40, fin -7 j 5.56, fin -15 j 4.25, fin -30 j 4.14 HORS TOLÉRANCE, voisin bullMin- 4.94, voisin bullMin+ 6.42, voisin bullMax- 5.31, voisin bullMax+ 5.73, voisin bearMin- 4.71, voisin bearMin+ 5.54, voisin bearMax- 5.53, voisin bearMax+ 5.53, voisin buyCap- 3.66 HORS TOLÉRANCE, voisin buyCap+ 6.90, voisin baseAmount+ 2.64 HORS TOLÉRANCE]
Vetos rencontrés : {DEGENERATE=1, DRAWDOWN=22}

**Hors échantillon (2025-11-15 → 2026-11-15), candidat retenu** — score -2.00
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hors-échantillon 12m | 2025-11-15 | 2026-11-15 | 2343 | 2391 | -2.00 | -8.6 | 12.3 | -127 | +81 | -2 | 5 |

**Hors échantillon, point de départ (bande 20–90 / 0–30, plafond du profil)** — score -0.39
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hors-échantillon 12m | 2025-11-15 | 2026-11-15 | 2348 | 2357 | -0.39 | -7.9 | 10.1 | -43 | +37 | -3 | 2 |
Durée d'un run (mono-thread, 5 essais) : SHORT_WINDOW_SIMPLIFIED 13.3 ms (270556 runs/h/thread) ; ALL_WINDOWS_SIMPLIFIED 73.6 ms (48931 runs/h/thread) ; ALL_WINDOWS_COMPLETE 75.6 ms (47619 runs/h/thread) ; 

### PAXG
Entraînement : fenêtres finissant le 2025-11-15 ; capital 1062.0 USDC ; 208 essais ; 325 ms au total ; 1.5 ms/run.
Sensibilités (effet en points de score) : [bullMin=1.54, baseAmount=0.87, bearMin=0.00, bearMax=0.00, bullMax=0.00, buyCap=0.00]
Retenu : {baseAmount=1.25, bearMax=35.0, bearMin=25.0, bullMax=80.0, bullMin=20.0, buyCap=7.0} ; score 0.66 ; plateau 0.66 (12/12 voisins) ; robuste=false (pire écart 100066 %).

**Entraînement, candidat retenu** — score 0.66 ; exclues : [12m : PAXG : historique insuffisant (23 bougies avant 2024-11-15, 180 requises), 24m : PAXG : historique insuffisant (0 bougies avant 2023-11-15, 180 requises)]
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| full | 2025-04-21 | 2025-11-15 | 1110 | 1102 | +0.66 | -2.7 | 14.3 | +2 | +5 | -0 | 0 |
Timing pur (hors score) : +0.45 %
Bousculades : [fin -1 j 0.69, fin -7 j 0.79, fin -15 j 0.78, fin -30 j -1000.00 VETO HORS TOLÉRANCE, voisin bullMin- 0.39 HORS TOLÉRANCE, voisin bullMin+ 0.70, voisin bullMax- 0.65, voisin bullMax+ 0.67, voisin bearMin- 0.66, voisin bearMin+ 0.66, voisin bearMax- 0.66, voisin bearMax+ 0.66, voisin buyCap- 0.63, voisin buyCap+ 0.70, voisin baseAmount- 0.57, voisin baseAmount+ 0.46]
Vetos rencontrés : {DEGENERATE=40, NO_ELIGIBLE_WINDOW=65}

**Hors échantillon (2025-11-15 → 2026-11-15), candidat retenu** — score 1.03
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hors-échantillon 12m | 2025-11-15 | 2026-11-15 | 1015 | 1005 | +1.03 | -7.5 | 22.8 | -1 | +12 | -1 | 13 |

**Hors échantillon, point de départ (bande 20–90 / 0–30, plafond du profil)** — score 1.82
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hors-échantillon 12m | 2025-11-15 | 2026-11-15 | 1035 | 1016 | +1.82 | -5.6 | 16.4 | +0 | +19 | -1 | 1 |
Durée d'un run (mono-thread, 5 essais) : SHORT_WINDOW_SIMPLIFIED 0.1 ms (46236835 runs/h/thread) ; ALL_WINDOWS_SIMPLIFIED 5.2 ms (695797 runs/h/thread) ; ALL_WINDOWS_COMPLETE 3.8 ms (958074 runs/h/thread) ;

## Run 2 — `baseAmount` gelé à la valeur du lien
Gelés : [baseAmount]. Pool d'évaluation : 4 threads ; profil BALANCED ; graine 20261116.

### BTC
Entraînement : fenêtres finissant le 2025-11-15 ; capital 2484.0 USDC ; 193 essais ; 5390 ms au total ; 27.7 ms/run.
Sensibilités (effet en points de score) : [buyCap=4.37, bullMin=2.98, bearMax=1.89, bullMax=0.21, bearMin=0.00]
Retenu : {baseAmount=1.0, bearMax=40.0, bearMin=25.0, bullMax=90.0, bullMin=5.0, buyCap=10.0} ; score -1.88 ; plateau -1.89 (8/10 voisins) ; robuste=false (pire écart 53208 %).

**Entraînement, candidat retenu** — score -1.88
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 12m | 2024-11-15 | 2025-11-15 | 2543 | 2461 | +3.31 | -3.4 | 10.2 | +3 | +81 | -2 | 0 |
| 24m | 2023-11-15 | 2025-11-15 | 2809 | 2713 | +3.55 | -5.4 | 13.0 | -97 | +198 | -5 | 1 |
| full | 2018-07-10 | 2025-11-15 | 6691 | 16085 | -58.40 | -24.6 | 19.8 | -7233 | -2091 | -69 | 2 |
Timing pur (hors score) : +3.29 %
Bousculades : [fin -1 j -1.86, fin -7 j -3.31 HORS TOLÉRANCE, fin -15 j -5.21 HORS TOLÉRANCE, fin -30 j -5.85 HORS TOLÉRANCE, voisin bullMin- -5.11 HORS TOLÉRANCE, voisin bullMin+ -1000.00 VETO HORS TOLÉRANCE, voisin bullMax- -1.84, voisin bullMax+ -1.91, voisin bearMin- -2.05, voisin bearMin+ -1.70, voisin bearMax- -1.93, voisin bearMax+ -1.82, voisin buyCap- -1.89, voisin buyCap+ -1.86]
Vetos rencontrés : {DEGENERATE=36, DRAWDOWN=30}

**Hors échantillon (2025-11-15 → 2026-11-15), candidat retenu** — score 2.75
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hors-échantillon 12m | 2025-11-15 | 2026-11-15 | 2503 | 2436 | +2.75 | -6.2 | 19.1 | -123 | +196 | -7 | 1 |

**Hors échantillon, point de départ (bande 20–90 / 0–30, plafond du profil)** — score 0.66
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hors-échantillon 12m | 2025-11-15 | 2026-11-15 | 2452 | 2436 | +0.66 | -5.4 | 10.5 | -35 | +52 | -1 | 5 |
Durée d'un run (mono-thread, 5 essais) : SHORT_WINDOW_SIMPLIFIED 13.2 ms (272315 runs/h/thread) ; ALL_WINDOWS_SIMPLIFIED 85.4 ms (42156 runs/h/thread) ; ALL_WINDOWS_COMPLETE 77.7 ms (46324 runs/h/thread) ; 

### ETH
Entraînement : fenêtres finissant le 2025-11-15 ; capital 2425.0 USDC ; 189 essais ; 3965 ms au total ; 20.9 ms/run.
Sensibilités (effet en points de score) : [bearMax=9.20, buyCap=7.85, bearMin=2.16, bullMin=1.38, bullMax=1.21]
Retenu : {baseAmount=1.0, bearMax=30.0, bearMin=30.0, bullMax=70.0, bullMin=0.0, buyCap=4.0} ; score 6.98 ; plateau 4.89 (3/8 voisins) ; robuste=false (pire écart 72 %).

**Entraînement, candidat retenu** — score 6.98
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 12m | 2024-11-15 | 2025-11-15 | 2808 | 2462 | +14.04 | -11.0 | 15.4 | +6 | +348 | -8 | 0 |
| 24m | 2023-11-15 | 2025-11-15 | 2879 | 2513 | +14.57 | -8.2 | 8.7 | -57 | +430 | -7 | 3 |
| full | 2018-07-10 | 2025-11-15 | 5034 | 16577 | -69.63 | -16.5 | 12.9 | -8940 | -2563 | -40 | 11 |
Timing pur (hors score) : +14.13 %
Bousculades : [fin -1 j 6.98, fin -7 j 6.05, fin -15 j 2.46 HORS TOLÉRANCE, fin -30 j 1.99 HORS TOLÉRANCE, voisin bullMin+ 3.49 HORS TOLÉRANCE, voisin bullMax- 6.98, voisin bullMax+ 6.98, voisin bearMin- 4.43 HORS TOLÉRANCE, voisin bearMax- 4.43 HORS TOLÉRANCE, voisin bearMax+ 8.03, voisin buyCap- 3.50 HORS TOLÉRANCE, voisin buyCap+ 4.89 HORS TOLÉRANCE]
Vetos rencontrés : {DEGENERATE=3, DRAWDOWN=16}

**Hors échantillon (2025-11-15 → 2026-11-15), candidat retenu** — score -0.33
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hors-échantillon 12m | 2025-11-15 | 2026-11-15 | 2349 | 2357 | -0.33 | -9.7 | 17.6 | -153 | +150 | -4 | 4 |

**Hors échantillon, point de départ (bande 20–90 / 0–30, plafond du profil)** — score -0.39
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hors-échantillon 12m | 2025-11-15 | 2026-11-15 | 2348 | 2357 | -0.39 | -7.9 | 10.1 | -43 | +37 | -3 | 2 |
Durée d'un run (mono-thread, 5 essais) : SHORT_WINDOW_SIMPLIFIED 10.6 ms (341118 runs/h/thread) ; ALL_WINDOWS_SIMPLIFIED 72.6 ms (49585 runs/h/thread) ; ALL_WINDOWS_COMPLETE 79.5 ms (45296 runs/h/thread) ; 

### PAXG
Entraînement : fenêtres finissant le 2025-11-15 ; capital 1062.0 USDC ; 193 essais ; 301 ms au total ; 1.5 ms/run.
Sensibilités (effet en points de score) : [bullMin=1.54, bearMin=0.00, bearMax=0.00, bullMax=0.00, buyCap=0.00]
Retenu : {baseAmount=1.0, bearMax=25.0, bearMin=10.0, bullMax=90.0, bullMin=15.0, buyCap=11.0} ; score 0.24 ; plateau 0.24 (9/10 voisins) ; robuste=false (pire écart 100024 %).

**Entraînement, candidat retenu** — score 0.24 ; exclues : [12m : PAXG : historique insuffisant (23 bougies avant 2024-11-15, 180 requises), 24m : PAXG : historique insuffisant (0 bougies avant 2023-11-15, 180 requises)]
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| full | 2025-04-21 | 2025-11-15 | 1097 | 1094 | +0.24 | -2.2 | 11.1 | +1 | +1 | +0 | 0 |
Timing pur (hors score) : +0.12 %
Bousculades : [fin -1 j 0.26, fin -7 j 0.35, fin -15 j 0.34, fin -30 j -1000.00 VETO HORS TOLÉRANCE, voisin bullMin- 0.12, voisin bullMin+ -1000.00 VETO HORS TOLÉRANCE, voisin bullMax- 0.24, voisin bullMax+ 0.24, voisin bearMin- 0.23, voisin bearMin+ 0.55 HORS TOLÉRANCE, voisin bearMax- 0.23, voisin bearMax+ 0.28, voisin buyCap- 0.24, voisin buyCap+ 0.24]
Vetos rencontrés : {DEGENERATE=48, NO_ELIGIBLE_WINDOW=65}

**Hors échantillon (2025-11-15 → 2026-11-15), candidat retenu** — score 1.80
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hors-échantillon 12m | 2025-11-15 | 2026-11-15 | 1035 | 1016 | +1.80 | -5.1 | 15.7 | +0 | +19 | -1 | 1 |

**Hors échantillon, point de départ (bande 20–90 / 0–30, plafond du profil)** — score 1.82
| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hors-échantillon 12m | 2025-11-15 | 2026-11-15 | 1035 | 1016 | +1.82 | -5.6 | 16.4 | +0 | +19 | -1 | 1 |
Durée d'un run (mono-thread, 5 essais) : SHORT_WINDOW_SIMPLIFIED 0.1 ms (40937003 runs/h/thread) ; ALL_WINDOWS_SIMPLIFIED 3.6 ms (1000879 runs/h/thread) ; ALL_WINDOWS_COMPLETE 3.8 ms (957187 runs/h/thread) ;
