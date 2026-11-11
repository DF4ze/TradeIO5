---
statut: courant
vérifié_le: 2026-11-11
modifié_le: 2026-11-11
empreinte: e73634c285
---
# Recette et déploiement prod

Vérifié le : 2026-11-11 (`application-recette.properties.template`, `/etc/systemd/system/tradeio.service`).

Toute migration Flyway (et tout changement de schéma) est répétée sur une **copie de la prod** avant la prod. Aucune migration manuelle : seul le démarrage de l'appli migre.

## Profil `recette`

Template versionné : `src/main/resources/application-recette.properties.template` (sans secret). Le fichier réel `application-recette.properties` vit sur le VPS dans `/home/oklm/TradeIO-recette/` (dossier de travail dédié : `logs/`, `backup/` séparés), droits `600`, secrets repris de `/home/oklm/TradeIO/application-prod.properties`.

- Base : `tradeio5_recette` (copie de la prod restaurée depuis un dump), port `9004`, `ddl-auto=validate`.
- Aucun effet de bord : `tradeio.execution.mode=OFF`, `live-unlocked=false`, tous les crons à `-`, **pas de `TRADEIO_MASTER_KEY`** (clés TRADE chiffrées illisibles ; les 2 ERROR « clé maître absente » au démarrage sont attendus).

## Procédure

1. Construire le jar (JDK 21, `mvn -o package -DskipTests`), le copier dans `/home/oklm/TradeIO-recette/`.
2. Recréer `tradeio5_recette` depuis un dump de la prod (`DROP`/`CREATE DATABASE`, `zcat dump | mysql tradeio5_recette`), sans SQL manuel.
3. Lancer depuis `/home/oklm/TradeIO-recette` : `java -Xmx768m -jar <jar> --spring.profiles.active=recette` (en arrière-plan, journal `recette.out`). Vérifier : migrations Flyway appliquées, `Started TradeIO5`, aucune autre ERROR, `/` et `/login` = 200, `/app` = 302.
4. Problème : corriger dans le dépôt (remonte au dev local), reconstruire, recommencer à l'étape 2.
5. Arrêter la recette (`kill` du PID, pas `pkill -f` depuis un `bash -c`).

## Déploiement prod (après recette OK)

1. Dump de la prod (`mysqldump --single-transaction --routines --triggers --events tradeio5 | gzip > ~/backups/...`).
2. Copier le jar dans `/home/oklm/TradeIO/`, sauvegarder `application-prod.properties`.
3. `systemctl stop tradeio` (en `runAsUser root` via le gateway), basculer le lien `tradeio.jar`, `systemctl start tradeio`.
4. Surveiller `journalctl -u tradeio` : Flyway (version courante), `Started TradeIO5`, port 9003, aucune ERROR hors 401 de polling.

## Retour arrière

Remettre le lien `tradeio.jar` sur le jar précédent et redémarrer. Restaurer le dump uniquement si une migration a modifié des données ; `flyway_schema_history` peut rester (ignorée par l'ancien jar).
