---
statut: decision
sujet: espace-expert
cree_le: 2026-11-10
origine: memoire-claude-migree
modifié_le: 2026-11-16
empreinte: ad9413922d
---
# espace-expert

> Décisions de Clem (tranchées). Ne pas re-questionner sans demande explicite. Le "comment c'est fait" est dans architecture/.

- Entités : TrendPreset, RainbowPreset, AssetAffectation.
- Presets système modifiables par admin seulement ; copies read-only pour users.
- Règles de simulation et décisions UI de la page expert : voir etudes/etude-espace-expert.md (actif).

## Simulateur complet (2026-11-14)
Cadre, périmètre et sorties : [`../etudes/etude-optimisation-mode-autonome.md`](../etudes/etude-optimisation-mode-autonome.md) §10.
- Plafond de drawdown par profil (défauts) : Prudent −15 %, Équilibré −25 %, Dynamique −40 %, Full −60 %. Premier lot : mesure et signalement seulement, **aucun effet sur les ordres** (live et rejeu).
- Plafonds (2026-11-15, lot 6) : réglage **global admin** (un jeu de 4 valeurs pour tous, pas de surcharge utilisateur) ; ordre imposé Prudent ≤ Équilibré ≤ Dynamique ≤ Full en amplitude ; profil `CUSTOM` : aucun plafond présélectionné, l'utilisateur choisit le profil à comparer ; drawdown sur la valeur totale du wallet (cash USDC inclus), plus haut initial = capital de départ, sur toute la plage simulée ; égalité exacte au plafond = respecté ; plafond du profil affiché aussi dans Réglages stratégiques.
- Cache global des frais : durée de vie 24 h, **persisté en base** ; consommé par le plan d'ordres, le devis, l'ajout d'un lien, le re-devis d'exécution et le simulateur. Frais périmés + exchange injoignable : acceptés avec avertissement pour les estimations (simulateur, devis, onboarding), refusés pour le plan d'ordres et le re-devis. Re-devis d'exécution : cache 24 h conservé. Pas d'action admin « rafraîchir les frais » pour l'instant.
- Rejeu avec coûts : spread / slippage = un carnet public actuel par instrument lu au lancement (estimation signalée) ; plafonds d'exécution (30 USD / ordre, 100 USD / jour) **ignorés** dans le simulateur (ils bornent l'exécution, pas le plan).
- Source des candles du rejeu = celle du lien (comme le live, pas de fallback silencieux).
- Capital de départ par défaut = valeur du wallet réel de l'utilisateur (cash USDC + actifs au prix), modifiable avant lancement.
- Test de parité : base locale = copie de la prod (`tools/dev-sync`, [`../operations/dev-sync.md`](../operations/dev-sync.md)), jamais de lecture directe de la prod.
- Ordre de livraison : mono-actif d'abord (cœur + parité), multi-actifs ensuite.
- Parité (2026-11-14) : acceptée si ≥ 95 % de jours identiques et tout écart résiduel expliqué ; rejeu au cours de décision enregistré par le live (23:55) ; période = du 1er run BTC de la base locale ; exposé par service + test + endpoint expert sans UI. Parité BTC acceptée (2026-11-14) à 18/19 jours identiques : l'écart du 28/10 vient de l'ancien moteur, qui ne se resynchronisait pas (ni reset ni rejeu) et restait bloqué sur d'anciennes décisions — bug corrigé depuis, état d'époque non reconstituable.
- Rejeu multi-liens (2026-11-14) : un rejeu par wallet (sélectionnable, capital par wallet comme le live) ; tous les liens du wallet sont rejoués, y compris inactifs/en simulation (`liveSlots` ne filtre pas) ; actifs détenus sans lien = valorisés et figés (comptent dans l'exposition, jamais traités) ; DCA fixe de référence = base de chaque lien, cash commun ; période de chaque lien = de son 1er run, `C0` = cash du snapshot le plus ancien du wallet ; acceptation ≥ 95 % par lien et global.
- Parité multi-liens, mesure du 2026-11-14 (wallet 1, BTC + ETH, du 11/10 au 13/11) : 28/37 jours-liens identiques, 0 écart inexpliqué ; BTC 18/19 (écart du 28/10 déjà accepté), ETH 10/18 (8 écarts consécutifs du 27/10 au 03/11, live NONE vs rejeu BUY ; tous les jours du 04/11 au 13/11 identiques). Aucun écart dû au cash commun, à la priorité ou à l'exposition inconnue ; cash jamais contraignant sur la période (aucun `INSUFFICIENT_CASH` testé). Règle : le verdict de l'endpoint n'est pas assoupli ; la parité se mesure sur la période où l'état moteur est reconstituable (`from=2026-11-04`). Confirmé le 2026-11-15 sur cette période : 19/19 jours-liens identiques (BTC 10/10, ETH 9/9), 0 écart, verdict ACCEPTÉ. Parité multi-liens acceptée (lot 2).
- Timeline des épisodes et répartition du gain (2026-11-15, lot 4) : répartition = exposition (exposition moyenne × mouvement) / timing (exposition au bon moment + écart d'exécution) / frais, exactement additive ; prix de revient de la position d'ouverture = cours du 1er jour de la plage (latent nul au départ) ; barres inactives = épisodes « inactif » ; aucune fusion ni durée minimale ; recalcul serveur sur la plage visible depuis le résultat en mémoire (TTL 30 min) ; endpoint dédié sans UI dans ce lot.
- Écran Simulateur (2026-11-15, lot 5) : nouvel onglet « Simulateur » (`/app/expert/simulateur`) ; rejeu libre = tous les liens actifs du wallet (case par lien) ; départ tout en USDC, position initiale 0, capital par défaut = valeur du wallet réel ; cours de décision = clôture D1 (écart vs 23:55 de la prod signalé dans le résultat) ; frais activés par défaut (estimation signalée) ; plage par défaut 24 mois ; zoom/pan = recalcul serveur du tableau avec rebond ~400 ms ; plafond de drawdown : voir ci-dessus (lot 6).
