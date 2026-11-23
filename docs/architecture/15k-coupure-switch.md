---
statut: courant
sujet: Coupure du bag au switch Bull → Bear (étape E1) — hook du moteur de rejeu, ligne « preset pur », comparaison à plafond de drawdown égal, runner switch-cut
vérifié_le: 2026-11-20
modifié_le: 2026-11-20
empreinte: 7b5af77226
---
# Coupure au switch (`SwitchCutStudy`, `SwitchCutConfig`, `SwitchCutReport`, `MultiLinkReplayEngine.SwitchCut`)

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
