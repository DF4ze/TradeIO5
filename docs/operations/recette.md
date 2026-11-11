---
statut: courant
vérifié_le: 2026-11-11
modifié_le: 2026-11-11
empreinte: f23050becc
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
| `lib.sh` | Fonctions communes (chemins, lecture des properties, comptages, rapport). |

`recette.sh` enchaîne :
1. **Préconditions** : base cible ≠ prod et nom contenant `recette` ; `execution.mode=OFF` ; `live-unlocked=false` ; tous les crons à `-` ; `TRADEIO_MASTER_KEY` absente ; port 9004 ; `ddl-auto=validate` ; aucune instance recette en cours.
2. **Copie** : `mysqldump --single-transaction` de la prod (lecture seule) → `~/backups/prod-pour-recette-*.sql.gz` ; recréation de `tradeio5_recette` ; restauration ; `clean-recette.sql` ; comptage des lignes par table.
3. **Démarrage** du jar (`--spring.profiles.active=recette`, lié à 127.0.0.1, `-Xmx768m`, clé agent éphémère aléatoire).
4. **Flyway** : journal, `flyway_schema_history` (aucune migration en échec), version avant/après.
5. **Smoke tests** avec la clé agent (`X-Api-Key`) : `/`, `/login`, `/app` (302), refus sans clé, Docteur (`GET`, `POST /run`, `/alarms`), `/api/admin/decision/scenarios`. Étendre la liste dans `recette.sh` quand un lot ajoute un endpoint critique.
6. **Journal et base** : ERROR hors motifs autorisés, `Caused by`, WARN fréquents, comparaison des comptages avant/après (une table qui perd des lignes ou disparaît = KO à justifier).
7. Arrêt de la recette, rapport, tampon `recette-ok-<sha256>.stamp` si aucun KO.

## Authentification des tests

La clé agent (`tradeio.security.agent-api-key`, en-tête `X-Api-Key`) donne `ROLE_API_AGENT`. Avec `tradeio.security.agent-api-key-admin=true` **et** le profil `recette` actif, elle reçoit aussi `ROLE_ADMIN` (endpoints Docteur, exécution…). Hors profil `recette` l'option est sans effet (`ApiKeyAuthFilter`, testé). `recette.sh` génère une clé aléatoire à chaque run, passée en argument de ligne de commande : rien en clair dans un fichier.

## Profil `recette`

Template versionné : `src/main/resources/application-recette.properties.template` (sans secret). Le fichier réel `application-recette.properties` vit sur le VPS dans `/home/oklm/TradeIO-recette/` (dossier de travail dédié : `logs/`, `backup/`, `reports/` séparés), droits `600`, secrets repris de `/home/oklm/TradeIO/application-prod.properties`.

- Base : `tradeio5_recette` (copie de la prod), port `9004`, `ddl-auto=validate`.
- Aucun effet de bord : `tradeio.execution.mode=OFF`, `live-unlocked=false`, tous les crons à `-`, **pas de `TRADEIO_MASTER_KEY`** (clés chiffrées illisibles ; les 2 ERROR « clé maître absente » au démarrage sont attendus).
- Le fichier prod n'a pas de `spring.datasource.url` : les scripts retombent sur celui de `application.properties` (`localhost:3306/tradeio5`).

## Procédure

1. Construire le jar (JDK 21, `mvn -o package -DskipTests`), le copier dans `/home/oklm/TradeIO-recette/` (un `uploadFile` de ~125 Mo dépasse 80 s : vérifier `cmp`/taille avant de continuer).
2. Lancer en arrière-plan (l'appel gateway coupe à ~80 s) : `cd /home/oklm/TradeIO-recette && nohup tools/recette/recette.sh <jar> > recette-run.out 2>&1 &` puis lire `reports/latest.md` dans un appel séparé (~1 min 30).
3. KO : corriger dans le dépôt, reconstruire, relancer (le script recrée la copie à chaque run).
4. OK **et** « go » de Clem : `deploy-prod.sh <jar> --go` (voir ci-dessous).

## Déploiement prod (après recette OK + « go »)

`deploy-prod.sh <jar> --go` (en `runAsUser: root` via le gateway, en arrière-plan) : vérifie le tampon de recette ; dump prod `~/backups/prod-avant-deploy-*.sql.gz` et copie de `application-prod.properties` ; copie du jar dans `/home/oklm/TradeIO/` ; `systemctl stop` ; bascule du lien `tradeio.jar` ; `systemctl start` ; attend `Started TradeIO5` ; contrôle Flyway (aucun échec), `/login` sur 9003, ERROR hors 401 de polling. **Démarrage en échec ⇒ retour automatique au jar précédent** (lien + redémarrage).

## Retour arrière manuel

Remettre le lien `tradeio.jar` sur le jar précédent et redémarrer. Restaurer le dump de `prod-avant-deploy-*` uniquement si une migration a modifié des données ; `flyway_schema_history` peut rester (ignorée par l'ancien jar). Flyway n'a pas de « down » : le dump est le seul vrai retour arrière des données.

## Pièges d'exploitation (VPS via ssh-gateway)

- `systemctl stop|start tradeio` exige root : appeler `execCommand` avec `runAsUser: root` (`sudo -n` seul échoue).
- Un appel `execCommand` coupe à ~80 s : lancer la recette en arrière-plan (`nohup … &`), puis lire `recette.out` dans un appel séparé ; ne pas relancer sans vérifier (`pgrep -af "[T]radeIO5"`).
- `uploadFile` d'un jar (~125 Mo) dépasse 80 s : le transfert continue, vérifier la taille/`cmp` du fichier distant avant de relancer.
- `pkill -f <motif>` lancé depuis un `bash -c` contenant ce motif se tue lui-même : utiliser `kill $(pgrep -f "[m]otif")`.
- Le jar embarque `application-dev/prod.properties` locaux ; en prod le fichier externe `/home/oklm/TradeIO/application-prod.properties` prime (y mettre `ddl-auto`, secrets, modes d'exécution).
- Le service prod tourne en `LIVE` (`live-unlocked=true`) : ne jamais copier ce réglage ni `TRADEIO_MASTER_KEY` dans la recette.
