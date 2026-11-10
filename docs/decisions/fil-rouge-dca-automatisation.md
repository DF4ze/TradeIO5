---
statut: decision
sujet: fil-rouge-dca-automatisation
cree_le: 2026-11-10
origine: memoire-claude-migree
modifié_le: 2026-11-10
empreinte: 903e3f3fb4
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
