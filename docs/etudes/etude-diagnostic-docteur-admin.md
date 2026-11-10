---
statut: journal
modifié_le: 2026-11-06
empreinte: 6d1dcd6fd3
---
# Étude — Page Admin « Diagnostic / Docteur » + gestionnaire d'alarmes

Daté : 2026-11-06. Étude seulement, rien de codé. Journal de conception (cf. `README.md` du dossier).
Objectif : détecter toute défaillance qui mettrait en péril la bonne exécution (run Rainbow → plan → ordres).

## 1. Constat sur l'existant (vérifié dans le code)

| Sujet | État |
|---|---|
| Jobs `@Scheduled` (9) | Seul trace = `log.info`. **Aucune table de runs de jobs** : impossible de savoir « le dernier run s'est-il bien passé ? » sans lire les logs. |
| Logs | logback : `tradeIO5.log` + `tradeIO5-error.log` (ERROR seul), rotation 30 j. WARN non isolé. Rien d'exposé en GUI. |
| Run Rainbow | `rainbow_live_run` (1 ligne/preset/jour) = trace de données, pas de statut de job (un preset en échec = pas de ligne, indiscernable de « pas dû »). |
| Plans / steps | `PlanStatus` (BLOCKED, EXPIRED, FAILED, PARTIAL…), `StepStatus` (REJECTED, UNKNOWN…) : sources d'alarmes déjà en base. |
| Kill switch / mode / LIVE | `AdminStateDto` (bandeau Admin) : déjà exposé. |
| Alertes **utilisateur** | `UserAlertService` / `AlertType` : alertes dérivées des statuts, par user, sans état (pas d'acquittement). Périmètre ≠ alarmes système. |
| Providers candles | `MarketDataProviderRegistry` + fallback, mais pas de mémoire « dernier succès / dernier échec » par provider. |
| Actuator / healthcheck | Aucun. |
| Cron désactivés par défaut | Un job non planifié (`-`) est silencieux : risque n°1 = **panne par absence**. |

Conséquence : le Docteur ne peut pas être qu'un « agrégateur de l'existant ». Il manque une brique de **traçabilité** (journal d'exécution des jobs, état des providers).

## 2. Briques conceptuelles

1. **Check** (sonde) : fonction pure `() -> CheckResult{status OK|WARN|CRIT|UNKNOWN, message, detail, mesuréÀ}`. Sans effet de bord.
2. **Famille** : regroupement affiché (Planification, Données, Exécution, Infra, Logs, Sécurité).
3. **Alarme** : état *persistant* dérivé d'un check qui sort de OK. Cycle de vie : `ACTIVE → ACKED → (RESOLVED auto quand check revient OK)`. Une alarme a une clé de dédoublonnage (`checkId + sujet`, ex. `JOB_LAST_RUN/RainbowLivePass2355`), un compteur d'occurrences, first/last seen, ackedBy/ackedAt/commentaire.
4. **Journal d'exécution** (`job_run`) : 1 ligne par exécution de job : job, début/fin, durée, statut (OK/PARTIAL/FAILED), résumé, nb éléments OK/KO, exception tronquée. Alimenté par un wrapper commun des jobs (aspect ou `JobRunTemplate`), y compris pour les déclenchements manuels (champ `trigger = CRON|MANUAL`).
5. **Heartbeat / attendu vs observé** : pour chaque job actif, calcul de la prochaine échéance attendue (cron) → check « en retard » si aucune exécution dans la fenêtre + tolérance. Détecte « le serveur était HS à l'heure du cron » et « cron désactivé par erreur ».
6. **Notification** : canal de sortie (push externe) quand une alarme passe CRIT. Indispensable : une page Admin que personne n'ouvre ne protège de rien.
7. **Sévérité vs criticité métier** : un check porte un `impact` (BLOQUE_ORDRES / DEGRADE / INFO) distinct du statut, pour trier ce qui menace *l'argent* en premier.

## 3. Catalogue des points de contrôle (proposition)

Légende impact : 🔴 menace l'exécution réelle · 🟠 dégrade la qualité des décisions · ⚪ confort.

### A. Planification
| ID | Contrôle | Source | Impact |
|---|---|---|---|
| A1 | Dernier run Rainbow 23:55 : exécuté dans la fenêtre attendue, statut OK, nb presets traités = nb presets actifs | `job_run` + `rainbow_live_run` | 🔴 |
| A2 | Run en retard / jamais exécuté (heartbeat) pour chaque job *activé* | `job_run` + cron résolu | 🔴 |
| A3 | Dernier `ExecutionPlanJob` OK, 1 plan par (user, jour) attendu | `job_run` + plans | 🔴 |
| A4 | Dernier `ExecutionJob` OK ; plans `EXECUTING` bloqués > N min | plans | 🔴 |
| A5 | Crons « attendus actifs » réellement actifs (liste de référence : run, plan, execution, orchestrator…) | config résolue | 🔴 |
| A6 | Jobs secondaires : EtfFlowHistorization, MediaWatch ingestion/extraction, backup hebdo | `job_run` | 🟠 |
| A7 | Dérive d'horloge / fuseau (JVM vs UTC) ; `DomainClock` en mode fixe oublié | runtime | 🔴 |

### B. Données de marché
| ID | Contrôle | Impact |
|---|---|---|
| B1 | Provider « générique » de candles joignable (ping léger + dernier fetch OK/KO + latence) | 🔴 |
| B2 | Provider de chaque lien (provider × actif) : idem, car la source = provider du lien | 🔴 |
| B3 | Fraîcheur des candles D1/H1 en DB par actif (dernière bougie ≤ seuil) — cf. incident ETH gelé 16/07→13/08 | 🔴 |
| B4 | Trous dans l'historique (bougies manquantes sur N derniers jours) | 🟠 |
| B5 | Mismatch inter-providers au-delà du seuil (warn déjà loggé) | 🟠 |
| B6 | Quotas / rate-limit proches (Twelve Data, Coinalyze, SoSoValue) | 🟠 |
| B7 | Indicateurs externes (ETF flow, DXY, fear&greed, OI/funding) : dernier rafraîchissement | 🟠 |

### C. Exécution / argent
| ID | Contrôle | Impact |
|---|---|---|
| C1 | Kill switch levé (état + depuis quand) | 🔴 |
| C2 | Mode (DRY/LIVE), `liveUnlocked`, clé maître configurée : cohérence avec ce qui est attendu | 🔴 |
| C3 | Credentials : clé rejetée / lecture solde KO par user×exchange (dernier test OK) | 🔴 |
| C4 | Steps `REJECTED`/`UNKNOWN`, plans `FAILED`/`PARTIAL`/`EXPIRED` récents | 🔴 |
| C5 | Stock en transit (jambe 2) vieux > N h | 🔴 |
| C6 | Plafonds journaliers (montant consommé vs plafond) proches | 🟠 |
| C7 | Écart état moteur ↔ réalité (resync §12) | 🟠 |

### D. Infra
| ID | Contrôle | Impact |
|---|---|---|
| D1 | DB : connexion, latence, migrations Flyway (quand activé) | 🔴 |
| D2 | Disque (logs, DB), RAM/heap, threads | 🟠 |
| D3 | Pool de threads du scheduler saturé / job qui dépasse sa durée normale | 🟠 |
| D4 | Uptime / dernier redémarrage (détecter un restart non prévu) | 🟠 |
| D5 | Backup hebdo : dernier OK + taille plausible | 🟠 |
| D6 | Expiration certificats / domaine (si exposé) | ⚪ |

### E. Logs
| ID | Contrôle | Impact |
|---|---|---|
| E1 | Nb d'ERROR sur 24 h / depuis dernier acquittement, top 5 groupés par logger+message normalisé | 🔴/🟠 |
| E2 | Nb de WARN idem, avec **liste blanche** des warns connus bruyants (sinon alarme permanente = ignorée) | 🟠 |
| E3 | Exception nouvelle (jamais vue) vs récurrente | 🟠 |

Implémentation E : ajouter un `Appender` logback en mémoire/DB (ERROR+WARN, signature = hash(logger + message sans chiffres)) → table `log_event_agg` (signature, niveau, 1er/dernier vu, compteur, dernier échantillon + stacktrace). Plus fiable et moins coûteux que parser les fichiers.

### F. Sécurité (phase audit, plus tard)
Échecs de login répétés, actions Admin inhabituelles (lien avec `docs/securite/remarques-audit.md`).

## 4. Gestionnaire d'alarmes

- Table `alarm` : `id, key (unique parmi non résolues), checkId, subject, severity, impact, state, message, firstSeen, lastSeen, count, ackedBy, ackedAt, ackComment, resolvedAt, snoozeUntil`.
- Moteur : `DoctorService.runAll()` (planifié toutes les ~5 min + bouton « Relancer maintenant ») :
  1. exécute les checks, chacun avec timeout et isolation (un check qui plante => `UNKNOWN`, jamais d'exception globale) ;
  2. réconcilie : non-OK → upsert alarme (réactive si ACKED et sévérité aggravée) ; OK → RESOLVED auto ;
  3. notifie sur transitions (nouvelle CRIT, escalade, résolution), jamais à chaque tick.
- **Acquittement** : ACK = « vu, je gère » (masque du bandeau, garde dans la liste) ; **Snooze** durée (ex. warn connu 7 j) ; ré-ouverture automatique si sévérité monte ou si la condition revient après résolution. L'ACK ne résout jamais : seul le retour à OK résout.
- Historique conservé (audit) : qui a acquitté quoi/quand (lien avec journalisation des actions Admin).
- **Méta-surveillance** : le Docteur ne peut pas détecter sa propre mort. Prévoir un heartbeat sortant (ping vers un service externe type healthchecks.io / Uptime Kuma) à chaque tick, et alerte si le ping cesse. Sans cela, « serveur HS » = silence = faux sentiment de sécurité.

## 5. Notifications (décision 2026-11-06)
Pas de notification pour l'instant : la page Admin est le seul canal (« la page que personne n'ouvre » assumée en phase dev/test). Pistes reportées en TODO : bot Telegram (push mobile) et « Doctor advertising » (le Docteur pousse ses alarmes), puis notifications par utilisateur — voir [`../known-gaps/doctor-diagnostic.md`](../known-gaps/doctor-diagnostic.md). Le modèle d'alarme (transitions, ack) est conçu pour qu'un canal s'y branche sans refonte.

## 6. Page Admin (onglet « Diagnostic »)
- Bandeau global : pastille worst-of (OK/WARN/CRIT) visible depuis toute page Admin (à côté du kill switch).
- Vue 1 « Alarmes actives » : tri impact 🔴 d'abord, boutons Ack / Snooze / Détail ; filtre état.
- Vue 2 « Contrôles » : grille Famille × check, statut, dernière mesure, bouton « relancer ce check ».
- Vue 3 « Journal des jobs » : dernières exécutions, durée, statut, déclencheur ; bouton relancer (réutilise endpoints `POST /api/admin/...` existants).
- Vue 4 « Logs » : agrégat ERROR/WARN par signature, acquittables.
- API : `GET /api/admin/doctor` (synthèse), `POST /api/admin/doctor/run`, `GET /api/admin/doctor/alarms`, `POST .../alarms/{id}/ack|snooze`, `GET /api/admin/doctor/jobs`.

## 7. Phasage proposé
1. **Socle traçabilité** : `job_run` + wrapper sur les 9 jobs (sans GUI). Prérequis de A1–A6.
2. **Moteur + checks 🔴** (A, C1–C5, B1–B3, D1) + table `alarm` + ack + page minimale (observateur).
3. **Logs agrégés** (E) + whitelist.
4. Checks 🟠/⚪, snooze.
5. Hors périmètre actuel (TODO) : Telegram, Doctor advertising, heartbeat sortant, notifications par utilisateur.

## 8. Décisions de Clem (2026-11-06)
1. **Notification** : aucune pour l'instant ; Telegram et « Doctor advertising » en TODO. La méta-surveillance (heartbeat sortant, §4) suit le même sort.
2. **Observateur uniquement** : le Docteur ne relance rien et n'agit jamais seul (ni job, ni kill switch). Raison : phase dev/test, éviter la boucle incident → correction automatique → nouvel incident qui masque la cause. Seuls les boutons manuels de l'Admin agissent.
3. **Crons attendus actifs** (référence du check A5) : vérifiés sur le VPS (config effective = jar + fichier externe, cf. [`../operations/scheduled-jobs.md`](../operations/scheduled-jobs.md)) : orchestrateur 06:00, Rainbow 23:55, plan 23:58, exécution 23:59 (UTC pour les trois derniers) ; snapshot et archivage désactivés. La copie locale du profil prod diverge du VPS : la liste de référence du check A5 devra être **configurable** et non codée en dur, et idéalement comparée à la config effective lue au runtime (`Environment`/`@Scheduled` enregistrés).
4. **Périmètre** : `DecisionOrchestratorJob` exclu de l'analyse pour l'instant (TODO à l'implémentation).
5. **Alarmes par utilisateur** : uniquement côté Admin pour l'instant ; notification par utilisateur en TODO.

Restent ouverts : seuils de tolérance (globaux ou par check), rétention du journal de jobs et des alarmes résolues.
