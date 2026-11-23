---
statut: journal
sujet: Résultats E2 — tableaux complets (ablations, bande de bruit, grilles du portefeuille)
modifié_le: 2026-11-23
empreinte: bcde173f07
---
# E2 — tableaux complets

Résumé, verdicts et limites : [`etude-coupure-robustesse.md`](etude-coupure-robustesse.md). Produit par `target/switch-robustness.md` (runner `switch-robustness`, régénérable). Gain en % du capital ; Δ = médiane sur les blocs de (gain de la coupure − gain du pur) à plafond de drawdown égal ; `NON_CONCLU` = plafond non encadré par la grille (jusqu'à ×64) ; blocs en ordre récent → ancien.

## BTC

#### Ablations d'un bloc (règle 1)

**Ré-entrée 0 % (E1 reconstitué)** — verdict de run E1 : COUPURE_UTILE ; règle 1 : ROBUSTE (3/4 plafonds positifs dans toutes les ablations)

| Profil | Plafond | Δ médian (4 blocs) | Δ sans chaque bloc (statut) | Tous positifs |
|---|---|---|---|---|
| PRUDENT | -15 | +5.3 | [2024 : +6.8, 2022 : +6.8, 2020 : +6.1, 2018 : +3.7] | true |
| BALANCED | -25 | +9.4 | [2024 : +11.8, 2022 : +11.8, 2020 : +14.2, 2018 : +7.0] | true |
| DYNAMIC | -40 | +16.9 | [2024 : +19.4, 2022 : +19.4, 2020 : +28.1, 2018 : +14.3] | true |
| FULL | -60 | +11.8 | [2024 : +6.2, 2022 : +17.3, 2020 : NON_CONCLU, 2018 : +6.2] | false |

**Ré-entrée 5 %** — verdict de run E1 : COUPURE_UTILE ; règle 1 : ROBUSTE (3/4 plafonds positifs dans toutes les ablations)

| Profil | Plafond | Δ médian (4 blocs) | Δ sans chaque bloc (statut) | Tous positifs |
|---|---|---|---|---|
| PRUDENT | -15 | +6.1 | [2024 : +8.3, 2022 : +8.3, 2020 : +6.2, 2018 : +4.0] | true |
| BALANCED | -25 | +10.3 | [2024 : +13.2, 2022 : +13.2, 2020 : +14.6, 2018 : +7.3] | true |
| DYNAMIC | -40 | +17.8 | [2024 : +20.7, 2022 : +20.7, 2020 : +27.2, 2018 : +14.9] | true |
| FULL | -60 | +11.8 | [2024 : +7.3, 2022 : +16.2, 2020 : NON_CONCLU, 2018 : +7.3] | false |

**Ré-entrée 10 %** — verdict de run E1 : COUPURE_UTILE ; règle 1 : ROBUSTE (3/4 plafonds positifs dans toutes les ablations)

| Profil | Plafond | Δ médian (4 blocs) | Δ sans chaque bloc (statut) | Tous positifs |
|---|---|---|---|---|
| PRUDENT | -15 | +7.0 | [2024 : +9.7, 2022 : +9.7, 2020 : +6.4, 2018 : +4.3] | true |
| BALANCED | -25 | +11.1 | [2024 : +14.6, 2022 : +14.6, 2020 : +15.2, 2018 : +7.7] | true |
| DYNAMIC | -40 | +18.8 | [2024 : +22.0, 2022 : +22.0, 2020 : +26.7, 2018 : +15.5] | true |
| FULL | -60 | +12.0 | [2024 : +8.2, 2022 : +15.8, 2020 : NON_CONCLU, 2018 : +8.2] | false |

**Ré-entrée 20 % (E1 reconstitué)** — verdict de run E1 : COUPURE_UTILE ; règle 1 : ROBUSTE (3/4 plafonds positifs dans toutes les ablations)

| Profil | Plafond | Δ médian (4 blocs) | Δ sans chaque bloc (statut) | Tous positifs |
|---|---|---|---|---|
| PRUDENT | -15 | +8.7 | [2024 : +12.7, 2022 : +12.7, 2020 : +6.7, 2018 : +4.7] | true |
| BALANCED | -25 | +12.9 | [2024 : +17.5, 2022 : +17.5, 2020 : +16.0, 2018 : +8.3] | true |
| DYNAMIC | -40 | +20.6 | [2024 : +24.8, 2022 : +24.8, 2020 : +26.0, 2018 : +16.5] | true |
| FULL | -60 | +12.8 | [2024 : +10.2, 2022 : +15.5, 2020 : NON_CONCLU, 2018 : +10.2] | false |

#### Bande de bruit (règle 3) : 20 décalages de départ

| Ré-entrée | Profil | Plafond | Conclus | Bat le pur | Part | Δ médian | P10 | P90 | Plafond stable |
|---|---|---|---|---|---|---|---|---|---|
| 20 % | PRUDENT | -15 | 20/20 | 20 | 100 % | +8.7 | +8.7 | +8.7 | true |
| 20 % | BALANCED | -25 | 20/20 | 20 | 100 % | +12.9 | +12.8 | +12.9 | true |
| 20 % | DYNAMIC | -40 | 20/20 | 20 | 100 % | +20.6 | +20.5 | +20.7 | true |
| 20 % | FULL | -60 | 20/20 | 20 | 100 % | +12.8 | +12.7 | +13.0 | true |
| 20 % | **STABLE** | | | | | | | | 4/4 |
| 10 % | PRUDENT | -15 | 20/20 | 20 | 100 % | +7.0 | +7.0 | +7.0 | true |
| 10 % | BALANCED | -25 | 20/20 | 20 | 100 % | +11.1 | +11.1 | +11.2 | true |
| 10 % | DYNAMIC | -40 | 20/20 | 20 | 100 % | +18.8 | +18.6 | +18.8 | true |
| 10 % | FULL | -60 | 20/20 | 20 | 100 % | +12.0 | +12.0 | +12.5 | true |
| 10 % | **STABLE** | | | | | | | | 4/4 |

## ETH

#### Ablations d'un bloc (règle 1)

**Ré-entrée 0 % (E1 reconstitué)** — verdict de run E1 : COUPURE_UTILE ; règle 1 : FRAGILE (1/4 plafonds positifs dans toutes les ablations) ; bloc qui renverse le signe : 2024-11-18 → 2026-11-17

| Profil | Plafond | Δ médian (4 blocs) | Δ sans chaque bloc (statut) | Tous positifs |
|---|---|---|---|---|
| PRUDENT | -15 | +2.8 | [2024 : -1.5, 2022 : +2.8, 2020 : +2.8, 2018 : +5.6] | false |
| BALANCED | -25 | +5.1 | [2024 : -3.4, 2022 : +5.2, 2020 : +5.0, 2018 : +8.9] | false |
| DYNAMIC | -40 | +12.8 | [2024 : +1.2, 2022 : +11.8, 2020 : +13.8, 2018 : +13.8] | true |
| FULL | -60 | n/d | [2024 : NON_CONCLU, 2022 : NON_CONCLU, 2020 : NON_CONCLU, 2018 : NON_CONCLU] | false |

**Ré-entrée 5 %** — verdict de run E1 : COUPURE_UTILE ; règle 1 : FRAGILE (1/4 plafonds positifs dans toutes les ablations) ; bloc qui renverse le signe : 2024-11-18 → 2026-11-17

| Profil | Plafond | Δ médian (4 blocs) | Δ sans chaque bloc (statut) | Tous positifs |
|---|---|---|---|---|
| PRUDENT | -15 | +2.7 | [2024 : -1.0, 2022 : +2.1, 2020 : +3.4, 2018 : +7.7] | false |
| BALANCED | -25 | +5.2 | [2024 : -2.6, 2022 : +4.6, 2020 : +5.8, 2018 : +10.9] | false |
| DYNAMIC | -40 | +12.9 | [2024 : +3.6, 2022 : +11.2, 2020 : +14.5, 2018 : +15.4] | true |
| FULL | -60 | n/d | [2024 : NON_CONCLU, 2022 : NON_CONCLU, 2020 : NON_CONCLU, 2018 : NON_CONCLU] | false |

**Ré-entrée 10 %** — verdict de run E1 : COUPURE_UTILE ; règle 1 : FRAGILE (1/4 plafonds positifs dans toutes les ablations) ; bloc qui renverse le signe : 2024-11-18 → 2026-11-17

| Profil | Plafond | Δ médian (4 blocs) | Δ sans chaque bloc (statut) | Tous positifs |
|---|---|---|---|---|
| PRUDENT | -15 | +2.6 | [2024 : -0.5, 2022 : +1.4, 2020 : +3.9, 2018 : +9.8] | false |
| BALANCED | -25 | +5.2 | [2024 : -1.9, 2022 : +3.9, 2020 : +6.5, 2018 : +12.9] | false |
| DYNAMIC | -40 | +12.5 | [2024 : +4.6, 2022 : +10.3, 2020 : +14.7, 2018 : +16.9] | true |
| FULL | -60 | n/d | [2024 : NON_CONCLU, 2022 : NON_CONCLU, 2020 : NON_CONCLU, 2018 : NON_CONCLU] | false |

**Ré-entrée 20 % (E1 reconstitué)** — verdict de run E1 : COUPURE_UTILE ; règle 1 : FRAGILE (1/4 plafonds positifs dans toutes les ablations) ; bloc qui renverse le signe : 2024-11-18 → 2026-11-17

| Profil | Plafond | Δ médian (4 blocs) | Δ sans chaque bloc (statut) | Tous positifs |
|---|---|---|---|---|
| PRUDENT | -15 | +5.0 | [2024 : -0.7, 2022 : +6.2, 2020 : +6.2, 2018 : +13.4] | false |
| BALANCED | -25 | +5.3 | [2024 : -0.4, 2022 : +2.6, 2020 : +8.0, 2018 : +16.9] | false |
| DYNAMIC | -40 | +12.1 | [2024 : +9.9, 2022 : +8.7, 2020 : +15.4, 2018 : +20.0] | true |
| FULL | -60 | n/d | [2024 : NON_CONCLU, 2022 : NON_CONCLU, 2020 : NON_CONCLU, 2018 : NON_CONCLU] | false |

#### Bande de bruit (règle 3) : 20 décalages de départ

| Ré-entrée | Profil | Plafond | Conclus | Bat le pur | Part | Δ médian | P10 | P90 | Plafond stable |
|---|---|---|---|---|---|---|---|---|---|
| 20 % | PRUDENT | -15 | 20/20 | 20 | 100 % | +5.9 | +3.2 | +6.2 | true |
| 20 % | BALANCED | -25 | 20/20 | 20 | 100 % | +5.6 | +3.4 | +7.8 | true |
| 20 % | DYNAMIC | -40 | 20/20 | 20 | 100 % | +12.4 | +11.7 | +15.3 | true |
| 20 % | FULL | -60 | 0/20 | 0 | 0 % | n/d | n/d | n/d | false |
| 20 % | **STABLE** | | | | | | | | 3/4 |
| 10 % | PRUDENT | -15 | 20/20 | 20 | 100 % | +2.7 | +1.2 | +3.3 | true |
| 10 % | BALANCED | -25 | 20/20 | 20 | 100 % | +5.2 | +3.7 | +5.9 | true |
| 10 % | DYNAMIC | -40 | 20/20 | 20 | 100 % | +12.5 | +11.8 | +14.3 | true |
| 10 % | FULL | -60 | 0/20 | 0 | 0 % | n/d | n/d | n/d | false |
| 10 % | **STABLE** | | | | | | | | 3/4 |

## Portefeuille BTC + ETH à cash commun (règle 4)

Cash commun 4909 USDC ; blocs [2024-11-18 → 2026-11-17, 2022-11-18 → 2024-11-17, 2020-11-18 → 2022-11-17, 2018-11-18 → 2020-11-17] ; bases de référence BTC 1.0000, ETH 1.0000 ; 504 rejeux.

**Coupure 20 % du capital du lien (moteur : 10.0 % du cash commun)** — verdict : TIENT (3/4 plafonds OK)

| Profil | Plafond wallet | Pur : × / gain méd. | Coupure : × / gain méd. | Δ par bloc | Δ médian | Pur respecte | Coupure respecte (pire DD rejoué) | OK |
|---|---|---|---|---|---|---|---|---|
| PRUDENT | -15 | ×3.22 / +11.2 | ×6.23 / +25.0 | [+23.0, +4.6, +9.3, +26.2] | +16.2 | true (-15.0) | true (-15.0) | true |
| BALANCED | -25 | ×5.82 / +20.2 | ×14.43 / +42.0 | [+38.6, +4.9, +15.6, +43.7] | +27.1 | true (-25.0) | true (-25.1) | true |
| DYNAMIC | -40 | ×10.70 / +37.2 | ×31.50 / +55.8 | [+28.5, +8.7, +21.0, +75.8] | +24.7 | true (-40.3) | true (-39.8) | true |
| FULL | -60 | ×23.08 / +62.4 | PLAFOND_NON_ATTEINT | [] | n/d | true (-60.8) | false (n/d) | false |

Réduction de drawdown à multiplicateur égal (pire DD des blocs, points) :

| × | Pur | Coupure | Réduction |
|---|---|---|---|
| 1.0 | -5.0 | -7.6 | -2.6 |
| 2.0 | -9.7 | -9.1 | +0.6 |
| 4.0 | -18.2 | -12.0 | +6.2 |

Grille complète :

| × | Pur : pire DD | Pur : gain méd. | Coupure : pire DD | Coupure : gain méd. | Ventes | Rachats |
|---|---|---|---|---|---|---|
| 0.125 | -0.6 | +0.4 | -6.9 | +12.4 | 38 | 38 |
| 0.25 | -1.3 | +0.9 | -7.0 | +12.6 | 38 | 38 |
| 0.5 | -2.6 | +1.7 | -7.2 | +13.1 | 38 | 38 |
| 1.0 | -5.0 | +3.5 | -7.6 | +14.2 | 38 | 38 |
| 1.5 | -7.4 | +5.2 | -8.4 | +15.2 | 38 | 38 |
| 2.0 | -9.7 | +7.0 | -9.1 | +16.2 | 38 | 38 |
| 3.0 | -14.1 | +10.4 | -10.6 | +18.3 | 38 | 38 |
| 4.0 | -18.2 | +13.9 | -12.0 | +20.4 | 38 | 38 |
| 6.0 | -25.7 | +20.9 | -14.7 | +24.5 | 38 | 38 |
| 8.0 | -32.3 | +27.8 | -17.3 | +28.7 | 38 | 38 |
| 12.0 | -43.7 | +41.8 | -22.3 | +36.9 | 38 | 38 |
| 16.0 | -50.3 | +50.6 | -26.7 | +45.2 | 38 | 38 |
| 24.0 | -61.3 | +63.2 | -34.0 | +50.5 | 38 | 38 |
| 32.0 | -65.3 | +77.6 | -40.4 | +56.2 | 38 | 38 |
| 48.0 | -67.1 | +95.0 | -52.8 | +64.0 | 38 | 36 |
| 64.0 | -68.6 | +107.7 | -56.8 | +59.1 | 38 | 35 |

**Coupure 10 % du capital du lien (moteur : 5.0 % du cash commun)** — verdict : TIENT (3/4 plafonds OK)

| Profil | Plafond wallet | Pur : × / gain méd. | Coupure : × / gain méd. | Δ par bloc | Δ médian | Pur respecte | Coupure respecte (pire DD rejoué) | OK |
|---|---|---|---|---|---|---|---|---|
| PRUDENT | -15 | ×3.22 / +11.2 | ×7.43 / +21.4 | [+19.6, +0.9, +8.3, +20.5] | +13.9 | true (-15.0) | true (-15.0) | true |
| BALANCED | -25 | ×5.82 / +20.2 | ×15.36 / +37.8 | [+34.6, +0.6, +14.4, +36.7] | +24.5 | true (-25.0) | true (-25.0) | true |
| DYNAMIC | -40 | ×10.70 / +37.2 | ×31.97 / +50.6 | [+23.2, +3.5, +19.0, +65.4] | +21.1 | true (-40.3) | true (-40.0) | true |
| FULL | -60 | ×23.08 / +62.4 | PLAFOND_NON_ATTEINT | [] | n/d | true (-60.8) | false (n/d) | false |

Réduction de drawdown à multiplicateur égal (pire DD des blocs, points) :

| × | Pur | Coupure | Réduction |
|---|---|---|---|
| 1.0 | -5.0 | -4.8 | +0.2 |
| 2.0 | -9.7 | -6.5 | +3.2 |
| 4.0 | -18.2 | -9.7 | +8.5 |

Grille complète :

| × | Pur : pire DD | Pur : gain méd. | Coupure : pire DD | Coupure : gain méd. | Ventes | Rachats |
|---|---|---|---|---|---|---|
| 0.125 | -0.6 | +0.4 | -3.8 | +6.3 | 38 | 38 |
| 0.25 | -1.3 | +0.9 | -3.9 | +6.6 | 38 | 38 |
| 0.5 | -2.6 | +1.7 | -4.1 | +7.1 | 38 | 38 |
| 1.0 | -5.0 | +3.5 | -4.8 | +8.1 | 38 | 38 |
| 1.5 | -7.4 | +5.2 | -5.7 | +9.2 | 38 | 38 |
| 2.0 | -9.7 | +7.0 | -6.5 | +10.2 | 38 | 38 |
| 3.0 | -14.1 | +10.4 | -8.1 | +12.3 | 38 | 38 |
| 4.0 | -18.2 | +13.9 | -9.7 | +14.3 | 38 | 38 |
| 6.0 | -25.7 | +20.9 | -12.9 | +18.5 | 38 | 38 |
| 8.0 | -32.3 | +27.8 | -15.8 | +22.6 | 38 | 38 |
| 12.0 | -43.7 | +41.8 | -21.1 | +30.9 | 38 | 38 |
| 16.0 | -50.3 | +50.6 | -25.7 | +39.2 | 38 | 38 |
| 24.0 | -61.3 | +63.2 | -33.3 | +45.4 | 38 | 38 |
| 32.0 | -65.3 | +77.6 | -40.0 | +50.6 | 38 | 38 |
| 48.0 | -67.1 | +95.0 | -53.8 | +60.5 | 38 | 36 |
| 64.0 | -68.6 | +107.7 | -56.7 | +56.8 | 38 | 35 |
