---
statut: courant
sujet: Coupure du bag au switch Bull → Bear (étapes E1 et E2) — hook du moteur de rejeu, ligne « preset pur », comparaison à plafond de drawdown égal, runners switch-cut et switch-robustness (taille de ré-entrée, bande de bruit, portefeuille)
vérifié_le: 2026-11-23
modifié_le: 2026-11-23
empreinte: 3a06ed0be5
---
# Coupure au switch (`SwitchCutStudy`, `SwitchCutConfig`, `SwitchCutReport`, `MultiLinkReplayEngine.SwitchCut`, `SwitchRobustness`)

Outil de mesure (hors prod, rien appliqué ni persisté, aucun preset modifié). Question : couper le bag au switch Bull → Bear et ré-entrer au switch Bear → Bull améliore-t-il le gain **à plafond de drawdown égal** ? Constats chiffrés : [`../etudes/etude-coupure-switch.md`](../etudes/etude-coupure-switch.md). Cadre et positions de Clem : [`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md) (« Coupure au switch »), [`../etudes/etude-pistes-timing-sorties.md`](../etudes/etude-pistes-timing-sorties.md).

## Hook du moteur (`MultiLinkReplayEngine.Input.cut`)
- `SwitchCut(sellFraction, reentryPct)` ; `null` = aucune coupure (chiffres strictement identiques, testé).
- Jour où le jeu d'un lien passe de Bull à Bear (comparaison des noms de jeux de la veille et du jour) : vente de `sellFraction` de la position de l'actif, avant les décisions Rainbow du jour (les ventes Rainbow du jour ne voient que le reste) ; produit crédité en fin de journée, comme toute vente ; frais et minimums via `OrderReplay`.
- Jour Bear → Bull : achat de `reentryPct` % du **capital initial** (`cash0`), borné au cash disponible ; le reste du cash passe par les achats Rainbow habituels.
- Pas de switch le premier jour rejoué (jeu de la veille inconnu). `Result.cuts` liste les ordres exécutés (`CutEvent`).
- `SensitivityEvaluator.parityCut(…, cut)` expose le rejeu avec coupure (`parity(…)` = `parityCut(…, null)`).

## Preset pur
Modulation ATH désactivée (décision de Clem) : `athOn = faux` sur les deux jeux (`SwitchCutStudy.withoutAth`), pour **les deux lignes** comparées ; réglages utilisateur neutres, ni politique ni plafond ATH. Le reste du preset est inchangé. Les références B' à C4 (mesurées avec la modulation) ne sont pas réutilisées : la ligne « pur » est re-mesurée dans le même run.

## Comparaison à plafond égal
Mêmes 4 blocs de 24 mois (`ParityConfig`) et même grille de multiplicateurs du montant de base (grille de C1 élargie d'emblée : 0,125 → 64). Deux lignes par bloc et par niveau : pur, pur + coupure (1 rejeu chacune, sans calage DCA ni bande de bruit). À chaque plafond de profil (`FrontierConfig.profileCaps()`, lus dans `ModulationDefaults`), chaque ligne est interpolée au multiplicateur où son **pire drawdown des blocs** atteint le plafond (premier franchissement en montant la grille, interpolation linéaire, gain de chaque bloc interpolé avec le même poids).

## Verdict (fixé avant lecture, `SwitchCutStudy.compare` / `run`)
Par plafond : `UTILE` si la coupure gagne en gain sur ≥ `minBlocksPass` (3) blocs sur 4 **et** Δ gain médian > 0 ; `NON_UTILE` sinon ; `NON_CONCLU` si un plafond n'est pas encadré par la grille pour l'une des deux lignes. Global : `COUPURE_UTILE` si ≥ 3 plafonds `UTILE` ; `NON_CONCLU` si un plafond est non conclu ; `COUPURE_INUTILE` si aucun `UTILE` ; sinon `MIXTE`.

## Blocs rejouables
Un bloc dont l'historique est insuffisant (actif récent) est écarté ; le rapport liste les blocs utilisés. Moins de `minBlocksPass` (3) blocs rejouables ⇒ plafond `NON_CONCLU` (jamais de verdict sur moins de blocs que la règle n'en exige).

## Lancement
`run-optimizer-manual.bat switch-cut` (mode `switch-cut` de `OptimizerManualRunnerTest`) : pour chaque actif de `CASES` (BTC, ETH, PAXG, chacun en run isolé) et chaque ré-entrée (20 %, puis 0 % = vente seule via `SwitchCutConfig.withReentry`), 128 rejeux au pire (≈ 3 s par run). Écrit `target/switch-cut.json|md` (synthèse puis un détail par run) et `-plan.txt`. Config : `SwitchCutConfig.defaults(fin)` (vente 100 %). Tests : `SwitchCutTest`.

## E2 : robustesse (`SwitchRobustness`, `SwitchRobustnessConfig`, `SwitchRobustnessReport`, `WalletEvaluator.lineCut`)
Même mécanisme que E1, inchangé (timing pur, ATH désactivée sur toutes les lignes) ; mesure hors ligne, rien appliqué ni persisté. Résultats : [`../etudes/etude-coupure-robustesse.md`](../etudes/etude-coupure-robustesse.md). Δ gain médian = médiane sur les blocs du Δ par bloc à plafond égal (définition d'E1) ; plafond non encadré par la grille = non conclu = non positif.

### Les 3 runs (liste fixée avant lecture, BTC et ETH ; PAXG exclu)
- **A : taille de ré-entrée.** 5 % et 10 % du capital initial (nouveaux) ; 0 % et 20 % reconstitués par le code d'E1 (les points par bloc ne sont pas conservés) et comparés aux chiffres publiés (dérive tolérée ≤ 0,06 pt, le runner échoue sinon).
- **B : bande de bruit.** Ré-entrée 20 % et 10 % contre le pur, 20 décalages de la date de départ de 0 à 60 j (`ExposureParity.shifts`, méthode de B'), même grille et mêmes plafonds ; le décalage 0 redonne le run de A (testé). Le départ avance, la fin ne bouge pas, aucune donnée postérieure n'est lue.
- **C : portefeuille BTC + ETH à cash commun** (`WalletEvaluator`, somme des capitaux, départ commun : un bloc doit être rejouable pour les deux liens). Pur contre coupure à 20 % et 10 %, mêmes 4 plafonds lus sur le pire drawdown **du wallet** ; même multiplicateur sur les deux liens (base de référence de chaque actif) ; ré-entrée d'un lien = taille × `walletShare` (0,5) du cash commun, soit le même montant relatif au lien qu'en E1.

### Règles de verdict (fixées avant lecture)
1. **Robustesse hors cycle dominant** (A, chaque taille) : par plafond, un bloc retiré à la fois (4 ablations = recalcul complet sans ce bloc, plafond ré-interpolé sur les blocs restants) ; `ROBUSTE` si ≥ 3 plafonds sur 4 ont un Δ gain médian > 0 dans les 4 ablations ; `FRAGILE` sinon, avec le bloc dont le retrait renverse le plus souvent le signe.
2. **Taille** : le gain médian de la coupure « croît » si la suite 0 < 5 < 10 < 20 % est strictement croissante à ≥ 3 plafonds conclus pour toutes les tailles ; meilleure taille = la plus souvent la plus haute ; identique pour BTC et ETH ⇒ règle générique.
3. **Bruit** : plafond stable si la coupure bat le pur (Δ gain médian > 0) sur ≥ 80 % des 20 décalages ; `STABLE` si ≥ 3 plafonds sur 4 ; médiane et P10 / P90 du Δ rapportés.
4. **Portefeuille** : `TIENT` si ≥ 3 plafonds sur 4 donnent un Δ gain médian > 0 **et** un plafond du wallet respecté (pire drawdown des blocs rejoué au multiplicateur interpolé ≥ plafond − `capTolerancePts` (1), même test pour le pur) ; réduction de drawdown à multiplicateur égal rapportée (×1, ×2, ×4).
5. **Global** : `COUPURE_ROBUSTE` si, pour au moins une taille testée en 3 et 4 (20 %, 10 %), les règles 1 et 3 sont positives pour BTC **et** ETH et la règle 4 est `TIENT` ; sinon `COUPURE_NON_ROBUSTE` avec la cause (cycle dominant, bruit, portefeuille). Aucun run positif isolé n'est promu.

### Lancement
`run-optimizer-manual.bat switch-robustness` (mode `switch-robustness` de `OptimizerManualRunnerTest`, base locale copie de prod). Estimation (`SwitchRobustnessConfig.worstCaseMinutes`, ≈ 9 200 rejeux × 25 ms) écrite dans `target/switch-robustness-plan.txt` avant le run ; arrêt si > 10 min sauf `-Doptimizer.robustness.confirm=true`. Pool dédié `POOL` (résultats ordonnés, aucun tirage : même rapport à l'octet hors durées), empreinte des bougies par actif. Sorties `target/switch-robustness.json|md` (verdicts, tableaux, dérive contre E1, multiplicité).

### Tests (`SwitchRobustnessTest`, CSV du repo)
Taille de ré-entrée respectée sur série synthétique (chaque rachat = taille % du capital initial, total croissant, 0 % = aucun rachat) ; ablation d'un bloc = recalcul sans ce bloc, bloc fautif nommé ; décalages de départ sans donnée future (bougies tronquées = mêmes chiffres, début avancé de N jours, fin inchangée) ; décalage 0 = run E1 ; wallet à un lien avec coupure = évaluateur d'un actif (chiffres et ordres) ; reproductibilité (série contre pool).
