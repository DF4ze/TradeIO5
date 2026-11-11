---
statut: decision
sujet: flyway-defauts-presets
cree_le: 2026-11-10
origine: memoire-claude-migree
modifié_le: 2026-11-11
empreinte: db1dcf17eb
---
# flyway-defauts-presets

> Décisions de Clem (tranchées). Ne pas re-questionner sans demande explicite. Le "comment c'est fait" est dans architecture/.

- Flyway activé (2026-11-11, baseline V1) ; toute modif de schéma = migration `V<n>` rejouée en recette avant la prod. Défauts via Initializers.
- Protocole de changement de schéma (2026-11-11) : recette sur copie de la prod (profil `recette`, base temporaire, démarrage réel, appel de quelques endpoints) → corrections remontées au dev local → dump prod → arrêt → migration par Flyway au démarrage → surveillance. Aucune migration manuelle, ni en recette ni en prod ([`../operations/recette.md`](../operations/recette.md)).
- Credentials jamais dans Flyway. Utilisateur principal : OKlm.
- Backup DB VPS avant déploiement ; nettoyage legacy après une semaine ; sauvegarde DB segmentée.
