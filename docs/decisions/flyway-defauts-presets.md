---
statut: decision
sujet: flyway-defauts-presets
cree_le: 2026-11-10
origine: memoire-claude-migree
modifié_le: 2026-11-11
empreinte: be8f2ab42f
---
# flyway-defauts-presets

> Décisions de Clem (tranchées). Ne pas re-questionner sans demande explicite. Le "comment c'est fait" est dans architecture/.

- Flyway activé (2026-11-11, baseline V1) ; toute modif de schéma = migration `V<n>` rejouée en recette avant la prod. Défauts via Initializers.
- Credentials jamais dans Flyway. Utilisateur principal : OKlm.
- Backup DB VPS avant déploiement ; nettoyage legacy après une semaine ; sauvegarde DB segmentée.
