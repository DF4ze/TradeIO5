---
statut: archivé
sujet: Calibrage walk-forward de la politique L2 par profil (4 profils, BTC + ETH [+ PAXG exclu], 3 plis annuels) après correction de l'évaluateur (pré-étape 5) : résultats et porte de sortie
vérifié_le: 2026-11-16
remplacé_par: Claude
archivé_le: 2026-11-20
raison: Étude close : décision reprise dans decisions/full-autonome-v1.md
modifié_le: 2026-11-20
empreinte: acce53253e
---
# Étude — calibrage de la politique L2 (étape 4 rejouée après la pré-étape 5, 2026-11-16)

> ⚠ **ARCHIVÉ le 2026-11-20** — Étude close : décision reprise dans decisions/full-autonome-v1.md. Remplacé par `Claude`. Ne jamais lire comme référence de l'état courant.

Journal daté : le mécanisme est décrit dans [`../architecture/15-optimiseur.md`](../../architecture/15-optimiseur.md), le cadre dans [`etude-optimisation-mode-autonome.md`](../../etudes/etude-optimisation-mode-autonome.md) §11.4. Un seul run (`-Doptimizer.manual=policy`, base locale copie de prod), graine 20261116, 4 profils × 3 plis, **2 412 essais** (+ 53 rejeux hors recherche), 48 s (pool de 4 threads). Résultats reproductibles (deux runs identiques).

## Entrées
- Wallet : BTC (lien #1, OKX), ETH (#3, OKX), PAXG (#5, Kraken), cash commun 10 000 USDC, autonomie 90 j, base de référence 47,61 USDC/jour à 2 liens.
- **PAXG exclu des 3 plis**, statut `HISTORIQUE_INSUFFISANT` : 0 bougie avant 2023-11-15 (pli 2023, fenêtre complète : 0 / 180 requises), 0 / 180 (pli 2024), 23 / 180 (pli 2025). Wallet effectif : BTC + ETH. Empreintes BTC/ETH : 2018-01-11 → T (2 135 / 2 501 / 2 866 bougies), détail dans `target/policy-calibration.json`.
- Plis : T = 2023-11-15, 2024-11-15, 2025-11-15 ; test = 12 mois suivants. Score = fenêtres 12 m / 24 m seulement ; veto de drawdown sur ces fenêtres seulement ; fenêtre complète rapportée, hors score.

## Porte de sortie vers l'étape 5
| Critère | Mesure | Verdict |
|---|---|---|
| Départ vetoé à l'entraînement < 30 % | **7 / 12 = 58 %** (Prudent 2/3, Équilibré 2/3, Dynamique 2/3, Full 1/3), tous pour `DRAWDOWN` sur 12 m / 24 m | **non atteint** (avant correction : 10 / 12) |
| Aucun `INVALID_CANDIDATE` retenu | 0 retenu (Prudent 2025 : tous les candidats vetoés, retenu arbitraire = `INVALID_CANDIDATE`, écarté : pli `GARDER_DEPART`) | atteint |
| Référence ≤ capital | cash du DCA fixe ∈ [0, capital] (test `fixedMinCash`) ; ne dépense jamais au-delà du cash | atteint (voir surprise 1) |
| Pénalité de dispersion < 50 % du score | BTC 43 % (4 / 12 mesures > 50 %), **ETH 50 %** (6 / 12 > 50 %) ⇒ alerte ETH | alerte documentée |

**Conclusion : la porte n'est pas franchie.** Les 4 verdicts restent `GARDER_DEPART`, mais l'entraînement n'est pas sain (58 % de plis sans exploitable) : ce résultat nul n'est pas concluant. Décision à Clem avant l'étape 5.

## Surprises et écarts de périmètre
1. **Le DCA fixe de référence ne dépensait déjà jamais plus que le capital** (le moteur de rejeu plafonne son cash, tout-ou-rien, sans recharge) : l'explication « 138 k USDC investis » de l'étape 4 était fausse. La cause des gains de −300 % à −1 050 % sur l'historique complet est un effet de timing : la référence engage les 10 000 USDC en quelques mois (47,61 × ~210 jours actifs, début 2018) puis garde la position ; elle finit à ~101 k sur BTC seul (stratégie ~36 k). Le test littéral « |gain DCA fixe| ≤ 100 % sur la fenêtre complète » est donc **impossible** sans changer la référence (mécanisme nouveau, hors périmètre) ; test livré : cash ∈ [0, capital] sur toutes les fenêtres, |gain DCA fixe| ≤ 100 % sur 12 m / 24 m récentes (BTC, fin 2026-09). Sur les plis historiques haussiers, 12 m / 24 m restent très négatifs (BTC seul jusqu'à −279 %) pour la même raison.
2. **Le veto du départ pèse sur les plis, pas seulement la fenêtre complète** : une fois la fenêtre complète retirée, le départ (bandes 20–90 / 0–30, multiple 1) dépasse encore le plafond de drawdown des profils sur 12 m / 24 m dans 7 plis. Parmi eux, 3 plis ont un candidat non vetoé (Équilibré 2025, Dynamique 2025, Full 2023) mais sont écartés par la règle « départ vetoé ⇒ pli `GARDER_DEPART` ».
3. `baseMultiplier` n'est plus bloqué à 0,5 : il monte à 1,75–2,0 (borne haute) sur le pli 2024.

## Résultats par profil (gains hors échantillon sur les 12 mois du pli, % du capital vs DCA fixe)
- **Plis gagnés** : Prudent 0/3, Équilibré 0/3, Dynamique 0/3, Full 1/3 (majorité requise 2).
- **Stabilité des paramètres** : non mesurable pour Prudent, Équilibré, Dynamique (un seul pli avec candidat : 2024). Full (2 plis) : `bullMax` 65 → 100 (70 % de la largeur), `baseMultiplier` 2,0 → 1,0 (67 %), `buyCap` 9 → 13 (29 %), `bearMax` 60 → 50 (20 %), `bullMin` 15 → 10 (10 %), `bearMin` 10 → 10 (0 %) : instable.

| Profil | Pli | Entraînement départ / retenu | Retenu hors éch. | Départ hors éch. | Statut |
|---|---|---|---|---|---|
| Prudent | 2023 | veto DD / tous vetoés | n/a | −39,92 | GARDER_DEPART (ENTRAINEMENT_VETOE) |
| Prudent | 2024 | −67,02 / −57,16 | −6,69 (veto DD test) | −1,39 | non gagné |
| Prudent | 2025 | veto DD / tous vetoés (INVALID) | n/a | +17,89 | GARDER_DEPART (ENTRAINEMENT_VETOE) |
| Équilibré | 2023 | veto DD / tous vetoés | n/a | −28,15 | GARDER_DEPART (ENTRAINEMENT_VETOE) |
| Équilibré | 2024 | −50,12 / −26,80 | −0,35 (veto DD test) | +2,61 | non gagné |
| Équilibré | 2025 | veto DD / −4,88 | n/a | +14,01 | GARDER_DEPART (départ vetoé) |
| Dynamique | 2023 | veto DD / tous vetoés | n/a | −28,74 | GARDER_DEPART (ENTRAINEMENT_VETOE) |
| Dynamique | 2024 | −49,31 / −27,67 | −11,14 (veto DD test) | +3,14 | non gagné |
| Dynamique | 2025 | veto DD / −3,43 | n/a | +14,17 | GARDER_DEPART (départ vetoé) |
| Full | 2023 | veto DD / −26,46 | n/a | −29,09 | GARDER_DEPART (départ vetoé) |
| Full | 2024 | −49,34 / −26,08 | −10,80 | +3,22 | non gagné |
| Full | 2025 | +0,22 / +7,94 | +14,06 | +13,89 | **gagné** (Δ +0,17 pt) |

## Verdict par profil
- **Prudent — GARDER_DEPART** : 0/3 plis gagnés ; 2/3 plis `ENTRAINEMENT_VETOE` ; médiane hors échantillon −6,69 % (1 pli) ; veto de drawdown en test.
- **Équilibré — GARDER_DEPART** : 0/3 ; 2/3 `ENTRAINEMENT_VETOE` ; médiane −0,35 % (1 pli) ; veto en test.
- **Dynamique — GARDER_DEPART** : 0/3 ; 2/3 `ENTRAINEMENT_VETOE` ; médiane −11,14 % (1 pli) ; veto en test.
- **Full — GARDER_DEPART** : 1/3 gagné ; 1/3 `ENTRAINEMENT_VETOE` ; médiane +1,63 % (2 plis, Δ +0,17 pt sur le seul pli gagné : dans le bruit) ; pas de veto en test.

## Mesure 4.0 B — part de la pénalité de dispersion (point de départ, actif seul, fenêtres 12 m / 24 m)
- **BTC** : pénalité médiane 15,62 pt ; score brut médian −64,40 ; part médiane 43 % du score absolu ; 4 / 12 mesures > 50 % ; alerte non. Formule inchangée.
- **ETH** : pénalité médiane 7,71 pt ; score brut médian −22,97 ; part médiane 50 % ; 6 / 12 mesures > 50 % ; **alerte** (avant correction : 64 %, 12 / 12). Le score brut reste négatif : la part élevée reflète des gains de fenêtre très dispersés (12 m ≈ −4 %, 24 m jusqu'à +36 % ou −92 %), pas un score positif écrasé.

## Variantes du protocole testées (mêmes entrées, graine, 2 412 essais ; runner `-Doptimizer.manual=policy-variants`)
`CalibrationConfig.withVariant(trainDrawdownVeto, dropFoldOnStartVeto)` et `ReferenceWallet.autonomyDays` ; les défauts du protocole ne changent pas.

| Variante | Départ vetoé entr. | Plis écartés | Plis gagnés P/É/D/F | Médiane hors éch. % P/É/D/F | Plis avec veto test | Pénalité BTC / ETH |
|---|---|---|---|---|---|---|
| V0 base | 7/12 | 7/12 | 0 / 0 / 0 / 1 | −6,7 / −0,4 / −11,1 / +1,6 | 3/12 | 43 % / 50 % |
| V1 sans veto de drawdown à l'entraînement | 0/12 | 0/12 | 0 / 1 / 2 / 2 | −14,6 / −0,4 / −11,1 / −8,3 | **6/12** | 43 % / 50 % |
| V2 pli gardé si seul le départ est vetoé | 7/12 | 4/12 | 0 / 1 / 1 / 1 | −6,7 / **+10,5** / **+6,9** / −10,8 | 3/12 | 43 % / 50 % |
| V3 = V1 + V2 | 0/12 | 0/12 | 0 / 1 / 2 / 2 | idem V1 | 6/12 | idem |
| V4 référence étalée (autonomie 360 j) | 4/12 | 4/12 | 0 / 0 / 1 / 2 | −2,4 / −4,2 / −2,2 / −4,0 | 3/12 | 49 % / 59 % |
| V5 = V1 + V2 + V4 | 0/12 | 0/12 | 2 / 2 / 2 / 2 | −2,4 / −4,2 / −2,2 / −4,0 | 3/12 | 49 % / 59 % |
| **V6 = V2 + V4** (veto d'entraînement gardé) | 4/12 | **0/12** | 1 / 1 / 1 / 2 | −2,4 / −4,2 / −2,2 / −4,0 | 3/12 | 49 % / 59 % |

Lecture : (1) retirer le veto d'entraînement (V1) « guérit » la porte mais pousse les candidats à franchir le plafond en test (6/12 vetos) : à écarter. (2) La référence étalée sur 360 j (V4) ramène les gains à ±1–4 % du capital au lieu de −300 % : l'évaluateur devient lisible. (3) V6 garde tous les plis avec un entraînement sain et le veto de drawdown ; le départ y est vetoé 4/12 mais n'écarte plus le pli. Hors échantillon, V6 : pli 2025 gagné par les 4 profils (+2 à +3,5 pts vs départ, ex. Équilibré +11,9 vs +8,6), pli 2024 perdu par les 4 (veto de drawdown en test pour 3), pli 2023 mixte (Full seul gagne). **Verdict V6 : `GARDER_DEPART` pour les 4 profils** (médiane négative, veto en test) : aucune politique L2 ne bat le départ de façon robuste. Non testé : un départ adapté au plafond de chaque profil (définition à fixer). Réserve : une autonomie de 360 j rend la base de référence 4× plus petite que la proposition d'onboarding à 90 j (le « multiple 1 » ne vaut plus la base d'onboarding).

## Tests complémentaires (base V6 : veto d'entraînement gardé, pli gardé, référence 360 j sauf mention)
Départs alternatifs (`CalibrationConfig.withStart`), autonomie de la référence, plis semestriels à test de 6 mois (`withFolds`, 6 plis × 4 profils = 24 plis-profils).

| Variante | Départ vetoé entr. | Plis gagnés P/É/D/F | Médiane hors éch. % P/É/D/F | Plis avec veto test | Verdicts P/É/D/F |
|---|---|---|---|---|---|
| W1 départ multiple 0,5 (3 plis) | 2/12 | 1 / 1 / 2 / 2 sur 3 | −2,4 / −4,2 / −3,0 / +1,8 | 3/12 | GD / GD / GD / **ADOPTER** |
| W2 départ bandes défensives (bullMax 70, bearMax 15) | 4/12 | 1 / 1 / 1 / 2 | −2,4 / −4,2 / −5,3 / −4,8 | 3/12 | GD ×4 |
| W3 = W1 + W2 | 0/12 | 1 / 1 / 2 / 3 | +4,8 / −4,2 / −3,0 / +1,8 | 3/12 | GD / GD / GD / **ADOPTER** |
| A1 autonomie 180 j | 6/12 | 1 / 1 / 1 / 2 | −0,9 / −14,2 / −11,2 / −10,3 | 3/12 | GD ×4 |
| A2 autonomie 720 j | 2/12 | 0 / 1 / 3 / 3 | +1,9 / +4,2 / +5,7 / +5,7 | 2/12 | GD / GD / **ADOPTER** / **ADOPTER** |
| M6 base V6, 6 plis | 6/24 | 1 / 1 / 2 / 2 sur 6 | −6,9 / −5,7 / −0,1 / +2,2 | 3/24 | GD ×4 |
| X1 = W1, 6 plis | 4/24 | 2 / 2 / 4 / 3 sur 6 | −6,9 / −5,7 / −0,1 / +2,2 | 3/24 | GD ×4 |
| X2 = W3, 6 plis | 1/24 | 2 / 3 / 4 / 4 sur 6 | −3,2 / −4,9 / −1,3 / +2,1 | 3/24 | GD / GD / GD / **ADOPTER** |
| X3 = A2, 6 plis | 4/24 | 1 / 1 / 3 / 3 sur 6 | −1,9 / +0,6 / +1,8 / +3,1 | 2/24 | GD ×4 |

(GD = `GARDER_DEPART`.) Lecture : (1) les `ADOPTER` à 3 plis sont fragiles : 16 variantes × 4 profils testés, la barre de significativité monte avec le nombre de configurations essayées. (2) **A2 (référence sur 720 j) s'effondre sur 6 plis** : une référence qui investit lentement est battue par n'importe quelle exposition en marché haussier ; l'autonomie de la référence pilote le résultat, elle ne doit pas être choisie pour obtenir un gain. (3) Seul **Full avec départ défensif (X2)** reste `ADOPTER` sur 6 plis (4/6 gagnés, médiane +2,1 %, sans veto de test), mais son départ n'est pas le départ standard et le gain tient à la référence 360 j : à suivre, pas à adopter. (4) Aucune variante n'adopte Prudent ni Équilibré ; Dynamique gagne 4/6 mais sa médiane reste négative avec veto en test.

### Multigraine (6 plis semestriels, tests 6 mois, graines 111/222/333/444, référence 360 j)

| Config | Verdict Full (ADOPTER / 4) | Médiane OOS Full % | Médiane OOS Dynamique % | Plis écartés |
|---|---|---|---|---|
| M6 (départ standard) | 1 | +1,9 à +3,4 | -1,4 à +1,6 | 1–4/24 |
| X2 (Full défensif : `baseMultiplier`=0,5, `bullMax`=70, `bearMax`=15) | 4 | +1,7 à +3,3 | -2,0 à +0,5 | 1–2/24 |

- Full ADOPTER sous X2 sur 4/4 graines ; Prudent, Équilibré, Dynamique : GARDER_DEPART sur 4/4.
- Départ vetoé à l'entraînement : M6 6/24, X2 1/24 (identique entre graines : dû au départ, pas à la recherche).
- 3/24 plis avec veto test, toutes configs/graines.
- Biais : X2 choisi après lecture des résultats (multiplicité) ; Full gagne aussi en M6 sur 1/4 graines => piste, pas preuve.

## Limites
Trois plis, ~200 essais par pli : la barre de significativité est haute ; aucune conclusion de gain. Une seule période hors échantillon par pli ; ETH/BTC haussiers sur la plupart des plis (la référence, déployée tôt, domine).

Départ = bandes 20–90 / 0–30, plafond d'achat du profil (3 / 6 / 10 / 15), multiple 1. Détails, sensibilités et timing pur : `target/policy-calibration.json`.
