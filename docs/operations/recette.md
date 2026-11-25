---
statut: courant
vérifié_le: 2026-11-11
modifié_le: 2026-11-24
empreinte: 95f305c3c4
---
# Recette et déploiement prod

Vérifié le : 2026-11-11 (`tools/recette/*`, `application-recette.properties.template`, VPS : `/home/oklm/TradeIO-recette`, `tradeio.service`). Positions de Clem : [`../decisions/recette.md`](../decisions/recette.md).

Toute migration Flyway (et tout changement de schéma) est répétée sur une **copie de la prod** avant la prod. Aucun SQL manuel : seul le démarrage du jar migre. Le déroulé est **scripté** : on lance un script, on lit un rapport.

## Scripts (`tools/recette/`, copiés dans `/home/oklm/TradeIO-recette/tools/recette/` sur le VPS)

| Fichier | Rôle |
|---|---|
| `recette.sh <jar>` | Recette complète (ci-dessous). Rapport : `reports/latest.md` (verdict OK/KO en tête). Code retour 0/1. |
| `deploy-prod.sh <jar> --go` | Déploiement prod (root). Refuse sans tampon « recette OK » du même jar (sha256, < 24 h). **Sur « go » de Clem uniquement.** Rapport : `reports/latest-deploy.md`. |
| `clean-recette.sql` | Nettoyage de la copie : clés TRADE supprimées, clés d'exchange neutralisées (désactivées, secrets vidés), `execution_enabled=0` sur tous les liens. Aucun DDL. |
| `allowed-errors.txt` | Motifs d'ERROR tolérés dans le journal de recette (clé maître absente, refus d'accès des smoke tests). Toute autre ERROR = KO. |
| `expected-drops.txt` | Tables dont la suppression par une migration est attendue (nettoyage) : une table listée qui disparaît n'est pas un KO ; une baisse de lignes reste un KO. Retirer l'entrée une fois la migration en prod. |
| `lib.sh` | Fonctions communes (chemins, lecture des properties, comptages, rapport). |

`recette.sh` enchaîne :
1. **Préconditions** : base cible ≠ prod et nom contenant `recette` ; `execution.mode=OFF` ; `live-unlocked=false` ; tous les crons à `-` ; `TRADEIO_MASTER_KEY` absente ; port 9004 ; `ddl-auto=validate` ; aucune instance recette en cours.
2. **Copie** : `mysqldump --single-transaction` de la prod (lecture seule) → `~/backups/prod-pour-recette-*.sql.gz` ; recréation de `tradeio5_recette` ; restauration ; `clean-recette.sql` ; comptage des lignes par table.
3. **Démarrage** du jar (`--spring.profiles.active=recette`, lié à 127.0.0.1, `-Xmx768m`, clé agent éphémère aléatoire).
4. **Flyway** : journal, `flyway_schema_history` (aucune migration en échec), version avant/après.
5. **Smoke tests** avec la clé agent (`X-Api-Key`) : `/`, `/login`, `/app` (302), refus sans clé, Docteur (`GET`, `POST /run`, `/alarms`), `/api/admin/decision/scenarios`, `/api/admin/assets` (+ `/blocklist`, `/settings`). `/api/rainbow-live/assets/eligible` n'est pas couvert : il exige une session utilisateur en base (la clé agent n'en est pas une). Étendre la liste dans `recette.sh` quand un lot ajoute un endpoint critique.
6. **Journal et base** : ERROR hors motifs autorisés, `Caused by`, WARN fréquents, comparaison des comptages avant/après (une table qui perd des lignes ou disparaît = KO à justifier).
7. Arrêt de la recette, rapport, tampon `recette-ok-<sha256>.stamp` si aucun KO.

## Authentification des tests

La clé agent (`tradeio.security.agent-api-key`, en-tête `X-Api-Key`) donne `ROLE_API_AGENT`. Avec `tradeio.security.agent-api-key-admin=true` **et** le profil `recette` actif, elle reçoit aussi `ROLE_ADMIN` (endpoints Docteur, exécution…). Hors profil `recette` l'option est sans effet (`ApiKeyAuthFilter`, testé). `recette.sh` génère une clé aléatoire à chaque run, passée en argument de ligne de commande : rien en clair dans un fichier.

## Profil `recette`

Template versionné : `src/main/resources/application-recette.properties.template` (sans secret ; `tradeio.new-asset-proposal.enabled=false` : le calcul des nouveaux actifs ne tourne pas en recette). Le fichier réel `application-recette.properties` vit sur le VPS dans `/home/oklm/TradeIO-recette/` (dossier de travail dédié : `logs/`, `backup/`, `reports/` séparés), droits `600`, secrets repris de `/home/oklm/TradeIO/application-prod.properties`.

- Base : `tradeio5_recette` (copie de la prod), port `9004`, `ddl-auto=validate`.
- Aucun effet de bord : `tradeio.execution.mode=OFF`, `live-unlocked=false`, tous les crons à `-`, **pas de `TRADEIO_MASTER_KEY`** (clés chiffrées illisibles ; les 2 ERROR « clé maître absente » au démarrage sont attendus).
- Le fichier prod n'a pas de `spring.datasource.url` : les scripts retombent sur celui de `application.properties` (`localhost:3306/tradeio5`).

## Procédure

1. `recette:tradeio-5` (playbook gateway, voir [`build-deploy.md`](build-deploy.md)) : package, envoi du jar et des scripts `tools/recette/` sur le VPS (CRLF -> LF), `recette.sh` en arrière-plan, attente de fin, affichage de `reports/latest.md` ; l'opération échoue si le verdict est KO. Suivre avec `waitOperation`.
2. KO : corriger dans le dépôt puis relancer (le script recrée la copie à chaque run).
3. OK **et** « go » de Clem : `deploy:tradeio-5` (jar de la recette, tampon sha256 < 24 h exigé).

## Sans recette (aucune modification de DB)
Décision : [`../decisions/recette.md`](../decisions/recette.md). Opération `deploy-sans-recette:tradeio-5` (= `deploy-prod.sh <jar> --go --sans-recette`) : saute le tampon de recette ; il **refuse** si le jar contient une migration Flyway de version supérieure à la dernière appliquée en prod. Dump, bascule, contrôles et retour arrière automatique restent identiques. Toujours sur « go » de Clem.

## Déploiement prod (après recette OK + « go »)

`deploy-prod.sh <jar> --go` (lancé par `deploy:tradeio-5`, `runAsUser: root`) : vérifie le tampon de recette ; dump prod `~/backups/prod-avant-deploy-*.sql.gz` et copie de `application-prod.properties` ; copie du jar dans `/home/oklm/TradeIO/` ; `systemctl stop` ; bascule du lien `tradeio.jar` ; `systemctl start` ; attend `Started TradeIO5` ; contrôle Flyway (aucun échec), `/login` sur 9003, ERROR hors 401 de polling. **Démarrage en échec ⇒ retour automatique au jar précédent** (lien + redémarrage).

## Retour arrière manuel

Remettre le lien `tradeio.jar` sur le jar précédent et redémarrer. Restaurer le dump de `prod-avant-deploy-*` uniquement si une migration a modifié des données ; `flyway_schema_history` peut rester (ignorée par l'ancien jar). Flyway n'a pas de « down » : le dump est le seul vrai retour arrière des données.

## Pièges d'exploitation (VPS via ssh-gateway)

- `systemctl stop|start tradeio` exige root : appeler `execCommand` avec `runAsUser: root` (`sudo -n` seul échoue).
- Jar à envoyer : `target/TradeIO5-<version>.jar`. Le `*-spring-boot.jar` (classifier du pom) a un `Start-Class` erroné et ne démarre pas (`ClassNotFoundException`).
- `pkill -f <motif>` lancé depuis un `bash -c` contenant ce motif se tue lui-même : utiliser `kill $(pgrep -f "[m]otif")`.
- Le jar embarque `application-dev/prod.properties` locaux ; en prod le fichier externe `/home/oklm/TradeIO/application-prod.properties` prime (y mettre `ddl-auto`, secrets, modes d'exécution).
- Le service prod tourne en `LIVE` (`live-unlocked=true`) : ne jamais copier ce réglage ni `TRADEIO_MASTER_KEY` dans la recette.
