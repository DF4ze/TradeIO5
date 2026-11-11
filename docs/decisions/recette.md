---
statut: decision
sujet: recette
cree_le: 2026-11-11
modifié_le: 2026-11-11
empreinte: bc75ba9efd
---
# recette

> Décisions de Clem (tranchées). Ne pas re-questionner sans demande explicite. Le « comment c'est fait » est dans [`../operations/recette.md`](../operations/recette.md).

- Toute mise en prod passe d'abord par la recette : copie de la prod sur le VPS (même machine, 2ᵉ service, port 9004, base `tradeio5_recette`), jar démarré en profil `recette`, migrations Flyway constatées, logs et base surveillés.
- Aucun `ALTER` à la main, ni en recette ni en prod : tout schéma passe par Flyway. Le déploiement prod = changer le jar.
- Un maximum de scripts : le LLM lance et lit des rapports ; il ne rejoue pas la procédure à la main.
- Problème en recette : il remonte à Clem pour choisir la solution (pas de correctif de fond décidé seul).
- **Le passage en prod ne se lance que sur « go » explicite de Clem.**
- Données nettoyées en recette, verrou d'exécution (`OFF`, pas de clé maître), clés TRADE absentes, clés d'exchange neutralisées, crons désactivés.
- Authentification facilitée pour les tests d'endpoints : réutiliser la clé agent, étendue à `ROLE_ADMIN` seulement en profil `recette`.
- Protocole porté par une skill (pointeur seulement, le protocole de référence reste dans `docs/operations/recette.md`).
