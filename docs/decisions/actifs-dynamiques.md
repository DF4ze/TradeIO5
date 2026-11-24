---
statut: decision
sujet: actifs-dynamiques
cree_le: 2026-11-24
modifié_le: 2026-11-24
empreinte: c0486fefee
---
# Actifs dynamiques (liste d'actifs non figée)

> Décisions de Clem (tranchées). Ne pas re-questionner sans demande explicite. Le « comment c'est fait » : [`../architecture/08c-rainbow-chemin-achat-wallet.md`](../architecture/08c-rainbow-chemin-achat-wallet.md) (éligibilité, registre, liens), [`../architecture/15m-pipeline-nouvel-actif.md`](../architecture/15m-pipeline-nouvel-actif.md) (D1b, validation admin), [`../architecture/11-front-app.md`](../architecture/11-front-app.md) (onboarding, onglet Actifs).

## Objectif
Plus de liste figée d'actifs (naguère BTC, ETH, PAXG) : ajouter DOGE, SOL, etc. depuis l'interface sans toucher au code.

## Source et accès
- **Les providers font foi** : catalogue **dérivé** de `InstrumentCatalog` (`exchange_instrument`, refresh 24 h). Pas de table d'activation, pas de whitelist admin. La ligne `asset` est créée au 1er lien.
- **Ouvert à tout actif éligible** chez un provider connecté du user. Aucun plafond dur sur le nombre d'actifs (surveiller la charge : candles, calage D1b).
- **Existence chez un provider** : route d'achat `PathFinder` depuis USDC (≤ 2 jambes, passerelle comprise, ex. PAXG sur Kraken) **et** bougies D1 chez ce provider. Un actif dispo chez un seul exchange est valide (le lien = provider × actif). Un actif joignable seulement via fiat est inéligible.
- **Historique minimal : 120 jours** de bougies D1 (seuil D1b). Au-dessus : lien possible, drapeaux D1b (`PROVISOIRE` < 24 mois…).
- **Exclusions automatiques** : stablecoins, fiat, tokens à levier / dérivés (3L, UP/DOWN…), illiquides, et tokens « earn » / staking / wrapped dont l'encodage propre à un provider ressemble à un nouvel actif (filtre par provider, testé).
- **Seuil d'illiquidité** : volume spot 24 h minimum chez le provider du lien, **réglable en admin** (défaut 1 M$).
- **Liste noire admin** (`asset_blocklist`, journal d'audit admin) : bloque les nouveaux liens ; liens existants => exécution suspendue + alarme.

## Calibration et exécution
- **Exécution réelle autorisée seulement si l'actif a une ligne de taux validée** (`asset_base_rate`). Un lien sur un actif sans ligne est en **simulation seule** (signal et simulation OK, message « en attente de validation ») ; Refuser garde la simulation seule ; Valider débloque.
- **BTC, ETH : lignes existantes (V9). PAXG : V10 lui écrit une ligne = taux BTC** (`validated_by = migration V10`) pour garder l'exécution réelle actuelle ; recalibrable via « Recalculer ».
- **Pipeline D1b généralisé** : `tradeio.new-asset-proposal.enabled` passe à **`true` par défaut** (`application.properties`), actif au déploiement du lot admin.
- **Bouton admin « Recalculer »** : relance le calcul d'une proposition (refus, calcul interrompu, historique devenu suffisant, recalibrage) ; journal d'audit.
- **Preset de départ = preset BTC**, réglé ensuite à la main (inchangé, pas de réglage automatique).
- **Bougies D1 du signal** : paire **USDC si dispo, sinon USDT, sinon USD** (comportement actuel de BTC/ETH/PAXG) ; l'exécution reste en USDC via `PathFinder`. Précise la règle « tout en USDC » ([`regles-transverses.md`](regles-transverses.md)).

## Cycle de vie
- **Désactivation globale** = liste noire. **Suppression** = le user archive son lien (existant). La ligne `asset` reste.
- **Paire retirée / provider déconnecté / clé supprimée** : alarme Docteur + **exécution suspendue** ; le lien reste visible ; jamais de bascule silencieuse de provider.
- **Rang d'actif pour départager l'allocation du cash** (après priorité du lien) : **ancienneté du lien (id)**, plus d'ordre BTC / ETH / PAXG.
- **Migration** : plus de constante ; BTC, ETH, PAXG deviennent des actifs comme les autres (migration `V10`).

## UX
- **Onboarding** : **top 12 par volume + recherche** ; badges d'état (calibré / provisoire / simulation seule en attente de validation) ; mobile + PC ; stocker le volume 24 h au catalogue.
- **Admin** : nouvel onglet **« Actifs »** (vue par actif : liens, état de calibration, bloqué ; liste noire ; seuil de volume). « Actifs à valider » (propositions de nouvel actif : Valider / Refuser) est **en haut de l'onglet « Actifs »**, avec une pastille du nombre de propositions à valider (onglet et menu admin) ; « Modulation » ne garde que ses réglages globaux ; « Taux de base » reste dans son onglet. **La barre d'onglets passe sur deux lignes** (l'overlay masque déjà « Diagnostic »).

## Hypothèses de conception (non questionnées, à contester si besoin)
- Listes d'actifs de l'Espace Expert / Actifs gérés = actifs liés par le user.
- Affectation Système « <ACTIF> Système » et presets BTC copiés dès la création du lien (simulation immédiate).
- ATH Trend Mix amorcé sur Binance ; actif absent de Binance => amorçage sur les 720 j du provider du lien (WARN).
- Volume 24 h lu en un appel « toutes les paires » par exchange (règle cache : jamais un appel par paire).

## TODO différé
- Notifier le user quand une paire est retirée / son provider déconnecté (chantier notifications user).
- Pouvoir réorganiser l'ordre d'allocation entre actifs (priorité) depuis l'interface.

## Hors périmètre
Réglage automatique de preset ; actifs à levier, futures, fiat ; cotation en BTC (PAXG/BTC) ; whitelist admin ; plafond d'actifs ; notifications user ; réorganisation de priorité.
