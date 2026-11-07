# Prompt d'opération — Migration des presets vers les Affectations (local puis VPS)

> Opération liée à l'[Espace Expert](prompt-roadmap-espace-expert.md) (lots 1 à 5). À exécuter **une fois le lot 5 terminé, mergé et `mvn test` vert**.
> Lire d'abord `docs/README.md`, `docs/operations/` (flyway, configuration), `docs/architecture/08-rainbow-live.md`.

## Objectif

Déployer sur le VPS la version avec Affectations **sans perdre aucune donnée utile**. Le VPS fait tourner depuis plusieurs jours un bench grandeur nature (runs, états moteur, liens, historique) : c'est la donnée la plus précieuse. Rien ne part en production avant répétition locale concluante **et** validation explicite de Clem.

## Ce qu'il faut savoir de la migration (code existant)

- `AffectationMigrationInitializer` (`@Order(62)`, CommandLineRunner) appelle `AffectationMigrationService.migrate()` **au démarrage**, après le seed des presets de base. Idempotente.
- Elle lit les tables legacy `rainbow_live_preset`, `rainbow_live_trend_config` (+ `rainbow_asset_strategy`, `rainbow_live_user_settings`), crée TrendPreset / RainbowPreset / `AssetAffectation`.
- Un seul preset legacy par (user, actif) est retenu (`choose`). **Les runs, états moteur et liens (`rainbow_live_run`, `rainbow_live_engine_state`, `rainbow_live_binding`) des presets non retenus sont supprimés** ; ceux du preset retenu sont rattachés à l'Affectation (`rekey`). Les colonnes `preset_id` de ces 3 tables passent nullables (DDL au démarrage).
- Le schéma évolue par `spring.jpa.hibernate.ddl-auto=update` (Flyway non activé) : les nouvelles tables sont créées au démarrage, rien n'est supprimé (risque : colonnes orphelines).
- Les états moteur sont réinitialisés quand une Affectation change de preset.
- Les tables legacy ne sont **pas** supprimées par cette opération (suppression = chantier ultérieur, cf. `docs/known-gaps/nettoyages.md`).

## Décisions déjà prises

- Répétition locale sur **une copie de la base VPS**.
- Sauvegarde VPS : **dump complet + vérification de restauration** (restauré en local), copie conservée **hors VPS**.
- **Arrêt court du service** pendant l'opération, hors créneau des crons (voir `docs/operations/scheduled-jobs.md` : choisir une fenêtre sans cron ni ordre réel).
- Actions VPS exécutées par Claude via **ssh-gateway, une étape à la fois, annoncée puis validée par Clem avant exécution**. Aucune action destructive sans accord explicite.
- Build local avec JDK 21 (cf. contraintes `mvn`/JAVA_HOME), JetBrains MCP ou gateway selon ce qui marche ; déploiement via l'opération gateway `build-deploy:tradeio-5` ou équivalent (jar dans `/home/oklm/TradeIO/`, symlink `tradeio.jar`, service systemd `tradeio`, port 9003).

## Phase 0 — Cadrage (lecture seule, local + VPS)

1. Vérifier que le lot 5 est mergé, tests verts, version à déployer identifiée (commit).
2. Sur le VPS (lecture seule) : état du service, version du jar actuel, espace disque, version MariaDB/MySQL, taille de la base et des tables principales, jobs/crons actifs.
3. **Inventaire AVANT** (à sauvegarder dans un fichier `migration-avant.txt`, hors repo) :
   - nombre de lignes de : `rainbow_live_preset`, `rainbow_live_trend_config`, `rainbow_asset_strategy`, `rainbow_live_user_settings`, `rainbow_live_run`, `rainbow_live_engine_state`, `rainbow_live_binding`, `rainbow_live_preset_event`, `admin_action_log` ;
   - par (user, actif) : nombre de presets legacy, lequel sera retenu (même règle que `choose`), nombre de runs / états / liens rattachés à chacun ;
   - plage de dates des runs (min/max), dernier run par (user, actif).
4. Conserver le jar actuellement en production (nom + checksum) pour le retour arrière.

**Point d'arrêt : présenter le cadrage à Clem avant de continuer.**

## Phase 1 — Sauvegarde et copie (VPS -> local)

1. Dump complet de la base `tradeio5` sur le VPS (`mysqldump --single-transaction --routines --events`), fichier daté, checksum, **dans un répertoire hors du dossier de déploiement**.
2. Si la taille le justifie (candles volumineuses), prévoir en plus un dump **sans** les tables de candles pour la répétition locale ; les candles sont re-téléchargeables, mais le dump complet reste la sauvegarde de référence.
3. Rapatrier le dump en local (via ssh-gateway : choisir la méthode qui fonctionne, vérifier checksum identique des deux côtés). Ne jamais écrire de secrets dans le repo.
4. **Vérifier la restauration** : restaurer dans une base locale **dédiée et séparée** (`tradeio5_rehearsal`, jamais la base de dev habituelle), comparer les comptes de lignes par table avec le VPS.
5. Copie de sécurité du dump hors du poste de travail principal si possible (Clem décide où).

**Point d'arrêt : confirmer à Clem que la sauvegarde est exploitable (restauration prouvée).**

## Phase 2 — Répétition locale

1. Lancer l'application locale du commit à déployer, **profil dédié pointant sur `tradeio5_rehearsal`**, crons et connecteurs d'ordres réels **désactivés** (aucun appel d'exchange, aucun ordre). Vérifier la config avant démarrage.
2. Laisser la migration s'exécuter au démarrage ; relever les logs (`Migration : …`, `Migration Affectations : N créée(s) pour M preset(s) legacy`), toute erreur DDL ou exception.
3. **Inventaire APRÈS**, mêmes requêtes que la phase 0, puis comparaison automatisée :
   - une Affectation par (user, actif) ayant un preset legacy retenu, `enabled` cohérent ;
   - Trend / Rainbow UP / Rainbow DOWN conformes aux règles de migration (FIXED -> Trend de base + Rainbow USER ; stratégie Actif -> presets système Bull UP / Bear DOWN ; presets modifiés -> USER avec nommage validé) ;
   - **runs : aucun run du preset retenu perdu** (même nombre, mêmes dates min/max), états moteur et liens rattachés à l'Affectation ; seules les lignes des presets **non retenus** peuvent avoir disparu, et leur nombre doit correspondre exactement à l'inventaire AVANT ;
   - aucune autre table touchée de façon inattendue (comparer les comptes de toutes les tables).
4. **Idempotence** : redémarrer l'application une seconde fois ; vérifier que rien ne change (aucun doublon d'Affectation, comptes identiques).
5. Tester sur la copie : parcours Expert (liste des Affectations, ouverture d'un preset, simulation courte), page Actifs gérés, pages publiques ; relever erreurs console / logs.
6. Mesurer la durée de démarrage + migration (pour dimensionner l'arrêt VPS).
7. Chronométrer un **retour arrière à blanc** : restauration du dump dans la base de répétition + relance de l'ancien jar.
8. Rédiger un compte rendu court : comptes avant/après, écarts attendus, durée, anomalies.

**Point d'arrêt : Clem valide le compte rendu. Sans accord explicite, ne pas passer au VPS. En cas d'anomalie : corriger en local (nouveau commit), puis rejouer toute la phase 2 depuis un dump restauré propre.**

## Phase 3 — Déploiement VPS (après validation, étape par étape)

Annoncer chaque étape, attendre « go » de Clem pour les étapes marquées (⛔).

1. ⛔ Choix de la fenêtre (hors crons, pas d'ordre réel en cours). Prévenir les éventuels utilisateurs.
2. Build du jar validé (même commit que la répétition), checksum noté.
3. ⛔ Arrêt du service `tradeio` ; vérifier qu'il n'y a plus de processus Java TradeIO ni d'écriture en base.
4. ⛔ **Nouveau dump complet** juste après l'arrêt (état exact avant migration), checksum, copie hors dossier de déploiement. L'ancien dump de phase 1 ne remplace pas celui-ci.
5. Inventaire AVANT sur la base VPS à l'arrêt (mêmes requêtes) ; comparer à celui de la phase 0 (écarts = activité entre-temps, à expliquer).
6. Upload du nouveau jar, **conserver l'ancien jar** à côté, bascule du symlink `tradeio.jar`.
7. ⛔ Démarrage ; suivre les logs en direct : migration, DDL, erreurs.
8. Inventaire APRÈS sur le VPS, comparaison avec les attentes de la phase 2 (mêmes critères). Vérifier : service actif, port 9003 en écoute, aucun ERROR/Exception, HTTP 200 sur un endpoint de lecture authentifié, page Expert accessible, derniers runs toujours lisibles.
9. Redémarrage de contrôle (idempotence) : comptes inchangés.
10. Surveiller le premier cycle de cron réel (run quotidien) : un run attendu par Affectation active, sans erreur, sans doublon, états moteur cohérents.
11. Compte rendu final à Clem : comptes avant/après, durée d'arrêt, anomalies, emplacement des dumps et de l'ancien jar.

## Retour arrière (à appliquer dès qu'un critère de la phase 3 échoue)

1. Arrêter le service.
2. Restaurer le dump de l'étape 4 (base remise à l'état exact d'avant migration) — **⛔ après accord de Clem** car cela écrase l'état courant.
3. Remettre le symlink sur l'ancien jar, redémarrer, vérifier comme à l'étape 8.
4. Ne pas relancer la migration avant analyse de la cause et nouvelle répétition locale.

## Garde-fous

- Ne jamais lancer la migration sur la base de dev habituelle de Clem ni sur le VPS sans dump vérifié.
- Aucun `DROP`, `DELETE` ou `TRUNCATE` manuel en production dans cette opération (la suppression du legacy est un chantier distinct, à faire seulement après constat de réussite).
- Pas de secrets (mots de passe DB, clés API) dans les logs, rapports ou fichiers du repo.
- Si une commande du gateway ne répond pas (timeouts connus sur sorties longues), rediriger la sortie vers un fichier et la relire, ne pas relancer à l'aveugle une commande non idempotente.
- Après réussite : mettre à jour `docs/known-gaps/nettoyages.md` (« migration constatée sur le VPS le <date> », restes legacy prêts à supprimer) ; aucun autre changement de code dans cette opération.

## Critères de fin

Dumps conservés et vérifiés, répétition locale concluante et rejouée, VPS migré avec comptes conformes, premier cron réel OK, compte rendu remis à Clem, `nettoyages.md` à jour.
