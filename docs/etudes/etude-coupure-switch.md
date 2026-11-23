---
statut: journal
sujet: Résultats E1 — coupure du bag au switch Bull → Bear contre preset pur à plafond de drawdown égal (BTC), verdict, lecture, limites
modifié_le: 2026-11-20
empreinte: 7d7c95d268
---
# E1 — Coupure du bag au switch (2026-11-20)

Mécanisme et règle de verdict : [`../architecture/15k-coupure-switch.md`](../architecture/15k-coupure-switch.md). Cadre : [`etude-pistes-timing-sorties.md`](etude-pistes-timing-sorties.md). Six runs (3 actifs × ré-entrée 20 % / 0 %), même règle, mêmes blocs et grille ; les sections 1 puis 2 ci-dessous. Section 1 : BTC, vente 100 % du bag au switch Bull → Bear, ré-entrée 20 % du capital initial au switch Bear → Bull, blocs de 24 mois jusqu'au 2026-11-17, modulation ATH désactivée sur les deux lignes, frais du simulateur.

## 1. BTC avec ré-entrée 20 % — verdict (règle fixée avant lecture) : `COUPURE_UTILE`
`UTILE` aux 4 plafonds (règle : ≥ 3 blocs sur 4 gagnés et Δ gain médian > 0 par plafond ; ≥ 3 plafonds sur 4).

| Profil | Plafond | Pur : × / expo % / gain méd. % | Coupure : × / expo % / gain méd. % | Δ gain par bloc (récent → ancien) | Blocs | Δ méd. |
|---|---|---|---|---|---|---|
| PRUDENT | −15 | ×2,40 / 8,9 / +11,1 | ×4,37 / 13,9 / +13,8 | +4,7 ; +0,7 ; +12,7 ; +35,3 | 4/4 | +8,7 |
| BALANCED | −25 | ×4,18 / 15,0 / +19,4 | ×9,02 / 21,3 / +19,9 | +8,3 ; −7,4 ; +17,5 ; +53,3 | 3/4 | +12,9 |
| DYNAMIC | −40 | ×7,20 / 25,8 / +33,5 | ×18,87 / 37,1 / +32,7 | +16,5 ; −18,1 ; +24,8 ; +95,5 | 3/4 | +20,6 |
| FULL | −60 | ×14,80 / 47,3 / +67,3 | ×44,95 / 57,7 / +54,1 | +15,5 ; −44,8 ; +10,2 ; +124,6 | 3/4 | +12,8 |

Grille complète : [`../../target/switch-cut.md`](../../target/switch-cut.md) (régénérable, non versionnée). 18 ventes et 18 ré-entrées cumulées sur les 4 blocs.

## Lecture (à ne pas surestimer)
- La règle est satisfaite, mais **l'effet est fragile** : le gain vient surtout du bloc 2018-2020 (+35 à +125 pts) ; le bloc 2022-2024 est **négatif** à 3 plafonds sur 4 (−7 à −45 pts). Le Δ médian (milieu de deux blocs) est tiré par ce bloc extrême : en gain médian des blocs, la coupure est quasi égale à BALANCED (+19,9 contre +19,4) et **inférieure** à DYNAMIC et FULL (+32,7 contre +33,5 ; +54,1 contre +67,3).
- À drawdown égal, la coupure permet un multiplicateur 2 à 3 fois plus grand (drawdown plus faible à même multiplicateur dès ×2) : le levier réel est la réduction du drawdown, pas le gain.
- La ligne « coupure » a un gain presque indépendant du multiplicateur aux bas niveaux (≈ +10 % de ×0,125 à ×1) : il vient de la ré-entrée de 20 % du capital initial à chaque switch Bear → Bull, taille sans commune mesure avec les achats Rainbow à ×1. La coupure + ré-entrée se comporte comme une couche de suivi de tendance propre, plus que comme un « filtre » du preset. Une ré-entrée plus petite ou une autre vente n'a pas été testée (aucune variante, règle du run unique).
- Retard de la Trend et faux switchs inclus (acceptés par décision de Clem).

## 2. Compléments : sans ré-entrée (0 %), ETH, PAXG

Même règle (≥ 3 blocs sur 4 gagnés et Δ gain médian > 0 par plafond ; ≥ 3 plafonds sur 4). Gain médian des blocs à plafond égal (pur → coupure) :

| Actif, ré-entrée | −15 % | −25 % | −40 % | −60 % | Plafonds UTILES |
|---|---|---|---|---|---|
| BTC, 20 % | 11,1 → 13,8 | 19,4 → 19,9 | 33,5 → 32,7 | 67,3 → 54,1 | 4/4 |
| BTC, 0 % | 11,1 → 6,5 | 19,4 → 12,0 | 33,5 → 23,9 | 67,3 → 47,9 | 4/4 |
| ETH, 20 % | 6,8 → 18,6 | 12,7 → 29,8 | 24,6 → 50,8 | non conclu (plafond non atteint par la coupure) | 3/4 |
| ETH, 0 % | 6,8 → 11,2 | 12,7 → 20,7 | 24,6 → 29,7 | non conclu (idem) | 3/4 |
| PAXG, 20 % et 0 % | non conclu | non conclu | non conclu | non conclu | 0/4 |

- **BTC sans ré-entrée** : verdict `COUPURE_UTILE` par la règle (3/4 blocs à chaque plafond), mais le gain médian des blocs est **inférieur au pur à tous les plafonds** ; le bloc 2022-2024 est très négatif (−13 à −59 pts), le bloc 2018-2020 très positif (+15 à +114). La règle est indulgente quand un seul bloc domine : elle ne distingue pas ce cas d'un vrai avantage. La ré-entrée de 20 % apporte donc l'essentiel du gain BTC ; la vente seule ne fait que protéger (multiplicateur ×2 à ×3 plus grand à drawdown égal).
- **ETH** : meilleur que BTC. Avec ré-entrée, le gain médian double aux plafonds −15, −25 et −40 % (3 à 4 blocs sur 4 gagnés) ; sans ré-entrée, gain modeste (+3 à +5 pts) avec le bloc 2018-2020 négatif. À −60 %, la coupure ne atteint pas le plafond même à ×64 : non conclu (la protection dépasse la grille).
- **PAXG** : non conclu. Historique rejouable dès 2025-01-30 (≈ 21 mois) : aucun bloc de 24 mois n'est rejouable, la règle ne peut pas s'appliquer (E0 : un seul switch fermé sur PAXG). Aucun chiffre PAXG. Ajouter une fenêtre plus courte est une variante, non faite.
- **Non-indépendance** : BTC et ETH bougent ensemble, leurs verdicts ne sont pas deux confirmations indépendantes ; chaque actif a été rejoué seul (cash propre), pas en portefeuille à cash commun.
- **Multiplicité** : 6 runs × 4 plafonds sous la même règle, sans correction ; le premier run (BTC, 20 %) est celui de la section 1.

## Limites
4 blocs non indépendants, ni bande de bruit ni décalages de départ ; BTC seul (ETH, PAXG et multi-actif non traités) ; un seul levier d'exposition (multiplicateur du montant de base) ; frais simplifiés (0,40 % par ordre, sans spread ni glissement) ; interpolation linéaire entre points de grille ; 4 plafonds lus sur un seul run (multiplicité).
