---
statut: journal
sujet: Résultats E2 — robustesse de la coupure au switch (taille de ré-entrée, bande de bruit, portefeuille BTC + ETH), verdict COUPURE_NON_ROBUSTE
modifié_le: 2026-11-23
empreinte: aca2c18086
---
# E2 — Robustesse de la coupure au switch (2026-11-23)

Mécanisme, règles et runner : [`../architecture/15k-coupure-switch.md`](../architecture/15k-coupure-switch.md). Point de départ : [`etude-coupure-switch.md`](etude-coupure-switch.md) (E1). Tableaux complets : [`etude-coupure-robustesse-tableaux.md`](etude-coupure-robustesse-tableaux.md). Timing pur (modulation ATH désactivée sur toutes les lignes), mécanisme d'E1 inchangé, blocs de 24 mois jusqu'au 2026-11-17, BTC et ETH (PAXG exclu, historique < 24 mois). Un run, une grille, aucune variante ajoutée après lecture.

## Verdict (règles fixées avant lecture)
| Règle | Résultat |
|---|---|
| 1. Hors cycle dominant (ablation d'un bloc) | BTC `ROBUSTE` aux 4 tailles ; ETH `FRAGILE` aux 4 tailles : le retrait du bloc 2024-11-18 → 2026-11-17 renverse le signe |
| 2. Taille | le gain médian croît avec la taille (0, 5, 10, 20 %) pour BTC et ETH ; meilleure taille = 20 % pour les deux ⇒ règle générique (mais 20 % est la plus grande testée) |
| 3. Bruit (20 décalages) | `STABLE` pour BTC (4/4 plafonds, 20/20 décalages) et ETH (3/4 plafonds), aux tailles 20 % et 10 % |
| 4. Portefeuille BTC + ETH | `TIENT` (3/4 plafonds) aux tailles 20 % et 10 % |
| **Global** | **`COUPURE_NON_ROBUSTE`**, cause : **cycle dominant** (règle 1, ETH) ; bruit et portefeuille positifs |

Une taille n'est retenue que si 1, 3 et 4 sont positifs pour BTC **et** ETH (lecture stricte, « jamais un seul run positif ») : les tailles candidates sont 10 % et 20 % (seules testées en 3 et 4) ; les deux échouent à la règle 1 sur ETH.

## Règle 1 — ablation d'un bloc (4 plafonds : −15, −25, −40, −60 %)
`ROBUSTE` si, pour ≥ 3 plafonds, le Δ gain médian reste > 0 en retirant n'importe lequel des 4 blocs (plafond ré-interpolé sur les 3 blocs restants ; plafond non encadré = non positif).

- **BTC** : ROBUSTE à 0, 5, 10 et 20 % (3/4 plafonds ; −60 % non conclu quand on retire le bloc 2020-2022, celui du bear 2021-22 qui fixe ce plafond). Le plus petit Δ en ablation : −15 % +3,7 à +4,7 (sans 2018-2020), −25 % +7,0 à +8,3, −40 % +14,3 à +16,5.
- **ETH** : FRAGILE à toutes les tailles (1/4 plafonds). Sans le bloc 2024-11-18 → 2026-11-17, Δ à −15 % / −25 % : 20 % −0,7 / −0,4 ; 10 % −0,5 / −1,9 ; 5 % −1,0 / −2,6 ; 0 % −1,5 / −3,4. Seul −40 % tient dans toutes les ablations ; −60 % non conclu (la coupure n'atteint pas le plafond, même à ×64).
- **Réserve BTC** : « ROBUSTE » porte sur le Δ apparié par bloc. Le gain médian des blocs de la coupure reste **sous le pur** à −40 et −60 % à toutes les tailles, et à −15 / −25 % sauf à 20 % (tableau ci-dessous). Même fragilité qu'en E1 (2018-2020 positif, 2022-2024 négatif), que la règle 1 ne mesure pas.

## Règle 2 — taille de ré-entrée (gain médian des blocs à plafond égal, %)
| | Plafond | Pur | 0 % | 5 % | 10 % | 20 % |
|---|---|---|---|---|---|---|
| BTC | −15 | +11,1 | +6,5 | +8,4 | +10,3 | +13,8 |
| BTC | −25 | +19,4 | +12,0 | +14,0 | +16,0 | +19,9 |
| BTC | −40 | +33,5 | +23,9 | +26,1 | +28,3 | +32,7 |
| BTC | −60 | +67,3 | +47,9 | +48,6 | +50,5 | +54,1 |
| ETH | −15 | +6,8 | +11,2 | +13,3 | +15,4 | +18,6 |
| ETH | −25 | +12,7 | +20,7 | +23,0 | +25,3 | +29,8 |
| ETH | −40 | +24,6 | +29,7 | +35,5 | +41,5 | +50,8 |
| ETH | −60 | n/d | n/d | n/d | n/d | n/d |

- Croissance **stricte** à 4/4 plafonds (BTC) et 3/3 évaluables (ETH) ; meilleure taille = 20 % aux deux actifs. Le gain vient de la ré-entrée : BTC sans ré-entrée (0 %) est sous le pur aux 4 plafonds, ETH au-dessus.
- Limites : l'optimum n'est pas encadré (aucune taille > 20 % testée) ; BTC ne dépasse le pur qu'à 20 % et à −15 / −25 %.

## Règle 3 — bande de bruit (20 décalages de départ de 0 à 60 j, même grille et mêmes plafonds)
Δ gain médian à plafond égal : médiane [P10 ; P90] sur les 20 décalages ; la coupure bat le pur à 20/20 décalages à chaque plafond conclu.

| Actif | Taille | −15 | −25 | −40 | −60 | Plafonds stables |
|---|---|---|---|---|---|---|
| BTC | 20 % | +8,7 [8,7 ; 8,7] | +12,9 [12,8 ; 12,9] | +20,6 [20,5 ; 20,7] | +12,8 [12,7 ; 13,0] | 4/4 |
| BTC | 10 % | +7,0 [7,0 ; 7,0] | +11,1 [11,1 ; 11,2] | +18,8 [18,6 ; 18,8] | +12,0 [12,0 ; 12,5] | 4/4 |
| ETH | 20 % | +5,9 [3,2 ; 6,2] | +5,6 [3,4 ; 7,8] | +12,4 [11,7 ; 15,3] | non conclu (0/20) | 3/4 |
| ETH | 10 % | +2,7 [1,2 ; 3,3] | +5,2 [3,7 ; 5,9] | +12,5 [11,8 ; 14,3] | non conclu (0/20) | 3/4 |

Le point d'entrée (≤ 60 j) ne renverse jamais le signe du Δ apparié ; la bande est étroite sur BTC (le Δ médian dépend des deux blocs du milieu). Elle ne dit rien du bloc qui domine (règle 1).

## Règle 4 — portefeuille BTC + ETH à cash commun (4 909 USDC, 50 / 50)
Δ gain médian des blocs à plafond égal du **wallet** ; plafond rejoué au multiplicateur interpolé (pire drawdown des blocs, tolérance 1 pt, même test pour le pur : −15,0 / −25,0 / −40,3 / −60,8).

| Taille (du capital du lien) | −15 | −25 | −40 | −60 | Plafonds OK |
|---|---|---|---|---|---|
| 20 % | +16,2 (pire DD −15,0) | +27,1 (−25,1) | +24,7 (−39,8) | non atteint (×64 : −56,8) | 3/4 |
| 10 % | +13,9 (−15,0) | +24,5 (−25,0) | +21,1 (−40,0) | non atteint (×64 : −56,7) | 3/4 |

Réduction de drawdown à multiplicateur égal (pire DD des blocs, points ; + = meilleur) :
| Taille | ×1 | ×2 | ×4 | ×16 |
|---|---|---|---|---|
| 20 % | −2,6 | +0,6 | +6,2 | +23,6 |
| 10 % | +0,2 | +3,2 | +8,5 | +24,6 |

La coupure aggrave le drawdown aux petits multiplicateurs (la ré-entrée achète plus que les achats Rainbow) et le réduit fortement aux grands : le gain à plafond égal vient du multiplicateur 2 à 3 fois plus grand permis par la coupure.

## Choix de lecture (fixés avant lecture)
- Δ gain médian = médiane sur les blocs du Δ par bloc (définition d'E1), pour les règles 1, 3 et 4.
- Portefeuille : même multiplicateur sur les deux liens (base de référence de chaque actif) ; ré-entrée d'un lien = taille × 0,5 du cash commun, soit le même montant relatif au lien qu'en E1.
- E1 (0 % et 20 %) reconstitué par le même code, car les points par bloc n'étaient plus stockés ; chiffres publiés d'E1 retrouvés (24 cellules, écart ≤ 0,06) : aucune dérive.

## Multiplicité (sans correction)
A : 4 runs nouveaux (5 et 10 % × BTC, ETH) + 4 runs E1 reconstitués, × 4 plafonds, 128 ablations (recalculs, 0 rejeu). B : 80 évaluations (2 actifs × 2 tailles × 20 décalages, dont 4 au décalage 0 = A) × 4 plafonds. C : 3 lignes de wallet × 4 plafonds. 4 règles, 1 verdict global. Rejeux réels : 8 712 (actifs) + 504 (wallet), ≈ 2 min.

## Limites
4 blocs non indépendants ; BTC et ETH corrélés (pas deux confirmations indépendantes) ; PAXG non conclu ; frais simplifiés (0,40 % par ordre) ; interpolation linéaire entre points de grille ; un seul levier d'exposition ; taille de ré-entrée testée jusqu'à 20 % seulement.
