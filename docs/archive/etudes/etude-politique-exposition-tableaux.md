---
statut: archivé
sujet: Tableaux par rotation de l'étape C2 (politique d'exposition contre exposition constante, à plafond de drawdown égal) — BTC et ETH, 4 profils
remplacé_par: Claude
archivé_le: 2026-11-20
raison: Étude close : décision reprise dans decisions/full-autonome-v1.md
modifié_le: 2026-11-20
empreinte: c2c00a7aae
---
# Étude C2 — tableaux par rotation (2026-11-19)

> ⚠ **ARCHIVÉ le 2026-11-20** — Étude close : décision reprise dans decisions/full-autonome-v1.md. Remplacé par `Claude`. Ne jamais lire comme référence de l'état courant.

Annexe de [`etude-politique-exposition.md`](etude-politique-exposition.md) : sortie brute du runner (`target/exposure-policy.md`), non retouchée. Un tableau par actif et profil : réglages retenus sur 3 blocs, mesure sur le bloc de test (le 4ᵉ), constante contre politique.

## BTC
Capital 2484 USDC ; base de référence 1.00 ; source OKX ; bougies CandleFingerprint[asset=BTC, firstDay=2018-01-11, lastDay=2026-11-17, count=3233, checksum=5d2823e19f40598a] ; blocs [2024-11-18 → 2026-11-17, 2022-11-18 → 2024-11-17, 2020-11-18 → 2022-11-17, 2018-11-18 → 2020-11-17] ; 1088 rejeux ; multiplicité : 16 multiplicateurs (constante) + 250 combinaisons (politique) × 4 rotations × 4 profils = 5320 sélections.

### BTC — PRUDENT (plafond -15.00 %) — NUL
| Test | Réglage constante | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Réglage politique (bornes Bull / Bear, mult.) | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Δ gain (pt) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2024-11-18 → 2026-11-17 | ×0.78 | 1.88 | -2.25 | oui | 5.28 | 186 / 11 | INTERPOLE | Bull ≤ 20.00 / Bear ≤ 20.00, ×0.50 | 1.20 | -1.46 | oui | 3.40 | 186 / 11 | REGLE | -0.68 |
| 2022-11-18 → 2024-11-17 | ×0.78 | 24.00 | -2.92 | oui | 8.49 | 122 / 13 | INTERPOLE | Bull ≤ 20.00 / Bear ≤ 20.00, ×0.50 | 15.37 | -2.00 | oui | 5.62 | 122 / 13 | REGLE | -8.63 |
| 2020-11-18 → 2022-11-17 | ×1.81 | -19.36 | -32.25 | NON | 19.66 | 239 / 14 | INTERPOLE | Bull ≤ 65.00 / Bear ≤ 20.00, ×6.00 | -11.62 | -19.50 | NON | 13.02 | 180 / 14 | REGLE | 7.74 |
| 2018-11-18 → 2020-11-17 | ×0.78 | 23.37 | -7.48 | oui | 10.81 | 203 / 15 | INTERPOLE | Bull ≤ 20.00 / Bear ≤ 20.00, ×0.50 | 14.97 | -4.99 | oui | 7.15 | 203 / 15 | REGLE | -8.40 |

Historique complet (lecture seule, hors verdict) : constante ×0.78 gain 80.64 % DD -11.42 % expo 8.63 % (plafond tenu) ; politique Bull ≤ 20.00 / Bear ≤ 20.00, ×0.50 gain 47.87 % DD -8.23 % expo 5.77 % (plafond tenu).

### BTC — BALANCED (plafond -25.00 %) — NUL
| Test | Réglage constante | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Réglage politique (bornes Bull / Bear, mult.) | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Δ gain (pt) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2024-11-18 → 2026-11-17 | ×1.36 | 3.27 | -3.83 | oui | 9.09 | 186 / 11 | INTERPOLE | Bull ≤ 100.00 / Bear ≤ 20.00, ×12.00 | 1.23 | -7.28 | oui | 16.87 | 102 / 11 | REGLE | -2.04 |
| 2022-11-18 → 2024-11-17 | ×1.36 | 41.73 | -4.43 | oui | 13.85 | 122 / 13 | INTERPOLE | Bull ≤ 20.00 / Bear ≤ 30.00, ×3.00 | 44.95 | -4.80 | oui | 14.25 | 122 / 13 | REGLE | 3.21 |
| 2020-11-18 → 2022-11-17 | ×3.76 | -33.70 | -52.33 | NON | 38.37 | 198 / 14 | INTERPOLE | Bull ≤ 100.00 / Bear ≤ 45.00, ×12.00 | -26.16 | -38.92 | NON | 28.38 | 198 / 14 | REGLE | 7.55 |
| 2018-11-18 → 2020-11-17 | ×1.36 | 40.64 | -11.99 | oui | 17.67 | 203 / 15 | INTERPOLE | Bull ≤ 20.00 / Bear ≤ 30.00, ×3.00 | 33.19 | -13.38 | oui | 17.83 | 197 / 15 | REGLE | -7.45 |

Historique complet (lecture seule, hors verdict) : constante ×1.36 gain 140.24 % DD -16.17 % expo 12.75 % (plafond tenu) ; politique Bull ≤ 100.00 / Bear ≤ 20.00, ×12.00 gain 248.36 % DD -22.89 % expo 20.47 % (plafond tenu).

### BTC — DYNAMIC (plafond -40.00 %) — NUL
| Test | Réglage constante | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Réglage politique (bornes Bull / Bear, mult.) | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Δ gain (pt) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2024-11-18 → 2026-11-17 | ×2.41 | 5.82 | -6.64 | oui | 15.89 | 186 / 11 | INTERPOLE | Bull ≤ 100.00 / Bear ≤ 45.00, ×16.00 | 5.25 | -12.82 | oui | 32.56 | 174 / 11 | REGLE | -0.56 |
| 2022-11-18 → 2024-11-17 | ×2.41 | 74.19 | -6.97 | oui | 22.26 | 122 / 13 | INTERPOLE | Bull ≤ 20.00 / Bear ≤ 45.00, ×8.00 | 79.01 | -7.23 | oui | 22.48 | 111 / 13 | REGLE | 4.82 |
| 2020-11-18 → 2022-11-17 | ×13.78 | -47.68 | -66.21 | NON | 59.22 | 89 / 14 | INTERPOLE | Bull ≤ 100.00 / Bear ≤ 100.00, ×12.00 | -47.07 | -66.23 | NON | 59.31 | 100 / 14 | REGLE | 0.60 |
| 2018-11-18 → 2020-11-17 | ×2.41 | 72.25 | -18.66 | oui | 28.43 | 203 / 15 | INTERPOLE | Bull ≤ 20.00 / Bear ≤ 45.00, ×8.00 | 44.55 | -18.94 | oui | 26.82 | 184 / 15 | REGLE | -27.71 |

Historique complet (lecture seule, hors verdict) : constante ×2.41 gain 249.32 % DD -21.46 % expo 18.15 % (plafond tenu) ; politique Bull ≤ 100.00 / Bear ≤ 45.00, ×16.00 gain 458.12 % DD -36.05 % expo 32.58 % (plafond tenu).

### BTC — FULL (plafond -60.00 %) — NUL
| Test | Réglage constante | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Réglage politique (bornes Bull / Bear, mult.) | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Δ gain (pt) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2024-11-18 → 2026-11-17 | ×5.57 | 13.43 | -14.28 | oui | 34.98 | 186 / 11 | INTERPOLE | Bull ≤ 100.00 / Bear ≤ 65.00, ×16.00 | 12.55 | -16.26 | oui | 42.85 | 186 / 11 | REGLE | -0.88 |
| 2022-11-18 → 2024-11-17 | ×5.57 | 171.22 | -13.32 | oui | 40.83 | 122 / 13 | INTERPOLE | Bull ≤ 20.00 / Bear ≤ 65.00, ×12.00 | 120.91 | -10.05 | oui | 30.79 | 111 / 13 | REGLE | -50.31 |
| 2020-11-18 → 2022-11-17 | ×64.00 | -44.33 | -68.37 | NON | 66.64 | 29 / 14 | PLAFOND_NON_ATTEINT | Bull ≤ 100.00 / Bear ≤ 100.00, ×12.00 | -47.07 | -66.23 | NON | 59.31 | 100 / 14 | REGLE | -2.75 |
| 2018-11-18 → 2020-11-17 | ×5.57 | 164.17 | -31.37 | oui | 51.56 | 199 / 15 | INTERPOLE | Bull ≤ 20.00 / Bear ≤ 65.00, ×12.00 | 60.17 | -25.19 | oui | 36.41 | 185 / 15 | REGLE | -104.00 |

Historique complet (lecture seule, hors verdict) : constante ×5.57 gain 570.32 % DD -31.98 % expo 27.69 % (plafond tenu) ; politique Bull ≤ 100.00 / Bear ≤ 65.00, ×16.00 gain 616.20 % DD -46.31 % expo 36.19 % (plafond tenu).

### ETH
Capital 2425 USDC ; base de référence 1.00 ; source OKX ; bougies CandleFingerprint[asset=ETH, firstDay=2018-01-11, lastDay=2026-11-17, count=3233, checksum=26a9c34ef1ec48c7] ; blocs [2024-11-18 → 2026-11-17, 2022-11-18 → 2024-11-17, 2020-11-18 → 2022-11-17, 2018-11-18 → 2020-11-17] ; 1088 rejeux ; multiplicité : 16 multiplicateurs (constante) + 250 combinaisons (politique) × 4 rotations × 4 profils = 5320 sélections.

### ETH — PRUDENT (plafond -15.00 %) — NUL
| Test | Réglage constante | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Réglage politique (bornes Bull / Bear, mult.) | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Δ gain (pt) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2024-11-18 → 2026-11-17 | ×1.37 | 18.57 | -13.98 | oui | 16.58 | 283 / 12 | INTERPOLE | Bull ≤ 30.00 / Bear ≤ 45.00, ×1.50 | 10.56 | -15.33 | NON | 15.96 | 283 / 12 | REGLE | -8.01 |
| 2022-11-18 → 2024-11-17 | ×1.37 | 21.93 | -6.21 | oui | 13.50 | 125 / 13 | INTERPOLE | Bull ≤ 45.00 / Bear ≤ 45.00, ×1.00 | 16.05 | -4.78 | oui | 10.14 | 125 / 13 | REGLE | -5.88 |
| 2020-11-18 → 2022-11-17 | ×1.37 | 6.49 | -13.00 | oui | 9.57 | 184 / 20 | INTERPOLE | Bull ≤ 30.00 / Bear ≤ 30.00, ×1.50 | 7.91 | -13.40 | oui | 9.39 | 184 / 20 | REGLE | 1.42 |
| 2018-11-18 → 2020-11-17 | ×1.47 | 35.25 | -15.97 | NON | 15.29 | 122 / 19 | INTERPOLE | Bull ≤ 45.00 / Bear ≤ 45.00, ×1.00 | 24.03 | -11.51 | oui | 10.74 | 122 / 19 | REGLE | -11.22 |

Historique complet (lecture seule, hors verdict) : constante ×1.37 gain 114.86 % DD -20.18 % expo 13.26 % (plafond DÉPASSÉ) ; politique Bull ≤ 45.00 / Bear ≤ 45.00, ×1.00 gain 84.04 % DD -14.78 % expo 10.44 % (plafond tenu).

### ETH — BALANCED (plafond -25.00 %) — NUL
| Test | Réglage constante | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Réglage politique (bornes Bull / Bear, mult.) | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Δ gain (pt) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2024-11-18 → 2026-11-17 | ×2.62 | 35.57 | -26.70 | NON | 29.38 | 283 / 12 | INTERPOLE | Bull ≤ 65.00 / Bear ≤ 65.00, ×3.00 | 8.83 | -28.75 | NON | 30.92 | 283 / 12 | REGLE | -26.74 |
| 2022-11-18 → 2024-11-17 | ×2.45 | 39.25 | -9.67 | oui | 22.47 | 125 / 13 | INTERPOLE | Bull ≤ 100.00 / Bear ≤ 100.00, ×2.00 | 32.09 | -8.35 | oui | 18.91 | 125 / 13 | REGLE | -7.16 |
| 2020-11-18 → 2022-11-17 | ×2.45 | 11.61 | -20.89 | oui | 16.05 | 184 / 20 | INTERPOLE | Bull ≤ 65.00 / Bear ≤ 45.00, ×3.00 | 13.00 | -24.29 | oui | 17.36 | 184 / 20 | REGLE | 1.39 |
| 2018-11-18 → 2020-11-17 | ×2.45 | 58.90 | -23.98 | oui | 24.17 | 122 / 19 | INTERPOLE | Bull ≤ 100.00 / Bear ≤ 100.00, ×2.00 | 48.06 | -20.52 | oui | 20.21 | 122 / 19 | REGLE | -10.85 |

Historique complet (lecture seule, hors verdict) : constante ×2.45 gain 205.58 % DD -36.02 % expo 20.44 % (plafond DÉPASSÉ) ; politique Bull ≤ 100.00 / Bear ≤ 100.00, ×2.00 gain 168.08 % DD -29.49 % expo 17.60 % (plafond DÉPASSÉ).

### ETH — DYNAMIC (plafond -40.00 %) — NUL
| Test | Réglage constante | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Réglage politique (bornes Bull / Bear, mult.) | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Δ gain (pt) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2024-11-18 → 2026-11-17 | ×5.86 | 6.65 | -47.90 | NON | 57.29 | 236 / 12 | INTERPOLE | Bull ≤ 100.00 / Bear ≤ 100.00, ×4.00 | 30.94 | -40.24 | NON | 42.32 | 267 / 12 | REGLE | 24.29 |
| 2022-11-18 → 2024-11-17 | ×3.98 | 63.78 | -13.28 | oui | 33.35 | 125 / 13 | INTERPOLE | Bull ≤ 100.00 / Bear ≤ 100.00, ×3.00 | 48.14 | -11.12 | oui | 26.63 | 125 / 13 | REGLE | -15.65 |
| 2020-11-18 → 2022-11-17 | ×3.98 | 18.87 | -29.65 | oui | 24.02 | 184 / 20 | INTERPOLE | Bull ≤ 100.00 / Bear ≤ 65.00, ×8.00 | 4.38 | -43.04 | NON | 30.40 | 184 / 20 | REGLE | -14.49 |
| 2018-11-18 → 2020-11-17 | ×3.98 | 94.40 | -33.31 | oui | 36.36 | 121 / 19 | INTERPOLE | Bull ≤ 100.00 / Bear ≤ 100.00, ×3.00 | 72.08 | -27.77 | oui | 28.78 | 122 / 19 | REGLE | -22.32 |

Historique complet (lecture seule, hors verdict) : constante ×3.98 gain 282.00 % DD -58.29 % expo 30.02 % (plafond DÉPASSÉ) ; politique Bull ≤ 100.00 / Bear ≤ 100.00, ×3.00 gain 245.31 % DD -44.11 % expo 23.95 % (plafond DÉPASSÉ).

### ETH — FULL (plafond -60.00 %) — NUL
| Test | Réglage constante | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Réglage politique (bornes Bull / Bear, mult.) | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Δ gain (pt) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2024-11-18 → 2026-11-17 | ×13.27 | -26.58 | -55.99 | oui | 70.74 | 116 / 12 | INTERPOLE | Bull ≤ 100.00 / Bear ≤ 100.00, ×12.00 | -25.59 | -56.20 | oui | 70.60 | 128 / 12 | REGLE | 0.98 |
| 2022-11-18 → 2024-11-17 | ×13.27 | 128.98 | -21.84 | oui | 60.87 | 89 / 13 | INTERPOLE | Bull ≤ 20.00 / Bear ≤ 100.00, ×8.00 | 80.61 | -13.49 | oui | 35.40 | 119 / 13 | REGLE | -48.37 |
| 2020-11-18 → 2022-11-17 | ×64.00 | 18.27 | -68.53 | NON | 60.95 | 42 / 20 | PLAFOND_NON_ATTEINT | Bull ≤ 100.00 / Bear ≤ 100.00, ×12.00 | 0.19 | -59.20 | oui | 44.83 | 112 / 20 | REGLE | -18.08 |
| 2018-11-18 → 2020-11-17 | ×13.27 | 187.04 | -46.14 | oui | 57.75 | 62 / 19 | INTERPOLE | Bull ≤ 20.00 / Bear ≤ 100.00, ×8.00 | 70.62 | -40.69 | oui | 37.17 | 81 / 19 | REGLE | -116.43 |

Historique complet (lecture seule, hors verdict) : constante ×13.27 gain 384.64 % DD -74.79 % expo 52.05 % (plafond DÉPASSÉ) ; politique Bull ≤ 100.00 / Bear ≤ 100.00, ×8.00 gain 422.92 % DD -71.28 % expo 43.22 % (plafond DÉPASSÉ).
