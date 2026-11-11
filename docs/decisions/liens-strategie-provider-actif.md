---
statut: decision
sujet: liens-strategie-provider-actif
cree_le: 2026-11-10
origine: memoire-claude-migree
modifié_le: 2026-11-10
empreinte: 22a2acc359
---
# liens-strategie-provider-actif

> Décisions de Clem (tranchées). Ne pas re-questionner sans demande explicite. Le "comment c'est fait" est dans architecture/.

- Hiérarchie : Stratégie -> Presets par actif -> Lien (user, provider, actif) -> Affectation.
- Affectations : System vs User (ex. "BTC Système").
- Changement de lien/affectation => reset moteur.
- Noms uniques par actif.
- Signal calculé par lien, sur les candles du provider du lien (D1, fenêtre 720 j). Pas de fallback silencieux : fallback = gros WARNING.
- `preferredRoute` porté par le lien. Armement auto à la première activation.
- Affectation et lien (2026-11-10) : on joue le lien, pas l'Affectation ; une Affectation sans lien n'est jamais jouée (plus de flag `enabled`).
- Affectation Système = définition commune (presets système de base), nommée « BTC Système » / « ETH Système » / « PAXG Système », rattachée par défaut au premier lien d'un actif ; Affectation Utilisateur = propre à l'utilisateur, jamais partagée.
- Plusieurs Affectations par actif possibles, nom unique par (utilisateur, actif) ; un lien n'en référence qu'une, choisie dans « Actifs gérés » (menu trié : correspondance provider d'abord, puis le reste).
- Changer l'Affectation d'un lien : reset du moteur et rejeu sur l'historique ; aucun blocage, aucun avertissement d'évènement différé. Étude : [`../etudes/etude-affectation-par-lien.md`](../archive/etudes/etude-affectation-par-lien.md).
