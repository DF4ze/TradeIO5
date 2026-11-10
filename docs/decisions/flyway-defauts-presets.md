---
statut: decision
sujet: flyway-defauts-presets
cree_le: 2026-11-10
origine: memoire-claude-migree
modifié_le: 2026-11-10
empreinte: a7b3c771c0
---
# flyway-defauts-presets

> Décisions de Clem (tranchées). Ne pas re-questionner sans demande explicite. Le "comment c'est fait" est dans architecture/.

- Flyway préparé, non activé. Défauts via Initializers.
- Credentials jamais dans Flyway. Utilisateur principal : OKlm.
- Backup DB VPS avant déploiement ; nettoyage legacy après une semaine ; sauvegarde DB segmentée.
