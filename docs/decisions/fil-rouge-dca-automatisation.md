---
statut: decision
sujet: fil-rouge-dca-automatisation
cree_le: 2026-11-10
origine: memoire-claude-migree
modifié_le: 2026-11-18
empreinte: 482d40ee6e
---
# fil-rouge-dca-automatisation

> Décisions de Clem (tranchées). Ne pas re-questionner sans demande explicite. Le "comment c'est fait" est dans architecture/.

- Priorité n°1 : automatiser achat/vente DCA intelligent (finaliser Trend + brancher au Rainbow ATR).
- Trend Mix validé. Jeux Bull/Bear par actif (BTC, ETH, PAXG), pas de Sideways. Trend simple (TrendAnalyzer) d'abord, double indicateur plus tard.
- Pine `tools/pine/rainbow_dca_v3_atr_moon.pine` = source de vérité ; le Java doit s'y conformer (finesses à reporter).
- Presets Perso Bear/Bull du pine = jeux trouvés à la main.
- Bench d'optimisation auto abandonné (empiriquement mauvais, biais "ne vend jamais") ; bench ATR retiré ; une seule passe 23:55 UTC sur la bougie du jour. Presets, liens, snapshots conservés. Doc doit mentionner l'essai.
- Bench Rainbow : graphique apprécié mais coûteux => demander avant de relancer.
- Cooldown (règle Bull/Bear games) ; resync + gap check/replay au démarrage.
- Trend unifié : version lissée voulue, contrat `TrendState` figé APRÈS tests ; métriques walk-forward expliquées simplement.
- Calibrations : mode itératif (run -> analyse -> relance).
- Idée : Supertrend = stop-loss suiveur (couche Decision/sizing), pas vote directionnel.
- Verdicts : RejectionZone utilisable en pondération basse (modulateur secondaire) ; EtfFlow utilisable tel quel (validateur J-1) ; MovementQualification non branché.

## Moteur d'optimisation automatique (2026-11-16)
Cadre et architecture : [`../etudes/etude-optimisation-mode-autonome.md`](../etudes/etude-optimisation-mode-autonome.md) §11. Rouvre le bench abandonné sous forme bornée (rejeu du simulateur complet + recherche en espace réduit).
- But : un moteur de bench qui, tous les X jours, recalibre les presets Rainbow pour la conjoncture **actuelle**, et pilote dans le temps les réglages utilisateur (exposition, puissance, biais, ATH) : base du mode Full Autonome.
- Presets : optimisés **par actif** (BTC, ETH, PAXG séparément). Trend figée, non optimisée.
- Réglages utilisateur : politique de pilotage évaluée **par rapport au wallet de l'utilisateur** (état du wallet en entrée).
- Promotion des presets : **auto-bornée** (dérive limitée, journalisée, réversible) ; Clem garde un regard (visibilité / veto) sans valider chaque promotion.
- Modulation ATH : **détachée du preset** et traitée en réglage utilisateur plafonné (L2 risque) avant le moteur. Forme et portée : [`modulation-exposition-puissance.md`](modulation-exposition-puissance.md).
- L3 (macro, YouTube) : **hors périmètre** pour l'instant.
- Évaluateur du moteur (2026-11-16) : fenêtres = durées fixes ancrées à une même date de fin (12 mois ×3, 24 mois ×2, historique complet ×1) ; score = gain vs DCA fixe sous plafond de drawdown du profil ; le gain à exposition égale (timing pur) est calculé et affiché en second, sans guider la recherche ; lancement par runner de test manuel **et** endpoint admin asynchrone désactivé par défaut ; moteur démontré à la fois sur fonction synthétique (tests) et sur l'espace L2 réel (bande par régime, plafond ATH, montant de base).
- Évaluateur du moteur, corrections (2026-11-16) : (A) le DCA fixe de référence est soumis au **même cash commun** que la stratégie (jamais au-delà du cash, pas de recharge), gain en % du capital normalisé (10 000 USDC) ; (B) le veto de drawdown ne s'applique qu'aux fenêtres 12 m / 24 m ; la fenêtre complète est rapportée (gain, drawdown) mais n'entre ni dans le veto, ni dans le score, ni dans la dispersion ; (C) la dispersion est calculée sur 12 m / 24 m seulement, alerte si sa part dans le score dépasse 50 %. Un pli dont le départ ou tous les candidats sont vetoés à l'entraînement garde le départ (`ENTRAINEMENT_VETOE`).
- Calibrage de la politique L2 (2026-11-16) : calibrée sur un **wallet de référence multi-actifs** (BTC, ETH, PAXG, cash commun, capital normalisé, montants en % du capital), transposable aux wallets utilisateurs ; gain mesuré contre un **DCA fixe à budget de référence commun** (identique pour tous les candidats), exprimé en % du capital, le montant de base restant un paramètre libre ; validation par **walk-forward en plis annuels** (entraînement jusqu'à une date, test sur les 12 mois suivants), politique retenue seulement si elle bat le point de départ sur la majorité des plis sans veto, sinon on garde le point de départ ; **les 4 profils** de risque, chacun avec son plafond de drawdown.

- **Calibrage L2, protocole (2026-11-17)** : l'existant ne change pas ; le protocole V6 (veto d'entraînement gardé, pli conservé si seul le départ est vetoé, référence 360 j) est livré en **opt-in** (`CalibrationConfig.protocolV6()`), conçu pour être swappable. Défauts inchangés.
- **Optimiseur de presets L1 (2026-11-17)** : par actif, Trend figée, `baseAmount` exclu (L2). Score = gain brut vs DCA fixe (protocole V6, référence fixée a priori, jamais ajustée pour gagner) **avec veto si l'exposition moyenne dépasse la bande du profil** ; timing pur rapporté. Paramètres libres par défaut : seuils ATR (×5), trailing stops, cooldown / fixedDelay, sellFraction ; ATH et Moon gelés. **Espace des paramètres libres et rayon de dérive δ = configuration (défaut δ = ±25 % relatif), pas du code en dur** : sélection par l'utilisateur / admin depuis l'interface prévue plus tard. Cadences X comparées au preset statique : 30 / 90 / 180 j.
- **Pivot « Rainbow d'abord » (2026-11-17)** : on suspend L2, l'orchestrateur et le mode Full Auto ; priorité aux presets Rainbow. Bench BTC, **Bull seul puis Bear seul**, un paramètre à la fois sur **tous** les réglages de l'interface Expert, vue 24 mois. Une amélioration ne vaut que si elle bat **le preset par défaut de l'actif ET le DCA fixe**. Mesure en **PnL réel** : le gain latent d'un bull n'est pas un gain si le bear suivant le reprend ; l'enjeu est de déterminer **quand vendre**. Puis optimisation façon main sur les paramètres influents, avec contrôle hors échantillon.
