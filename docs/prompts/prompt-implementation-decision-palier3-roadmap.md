# Roadmap d'implémentation — Palier 3 : branchement, orchestrateur, persistance des Decisions & Co

Point d'entrée pour séquencer le Palier 3. Ne répète pas les décisions déjà actées — elles vivent
dans `docs/etudes/etude-branchement-persistance-decision-engine.md` (§A à §E) et dans la mémoire
projet `tradeio5_decision_dca_intelligent_etude`. Ce document ne fait que séquencer, dans l'ordre de
dépendance réel, et lister ce qui reste à trancher avant de rédiger le prompt détaillé de chaque
étape. Ne pas lancer une étape avant que la précédente soit terminée (compilée + testée) — chaque
étape suivante suppose l'existant de l'étape d'avant.

**Note de suivi (2026-08-12)** : l'étape 1 a été implémentée avant que les précisions sur le rôle du
bus d'événements sous B3 (retrait de l'abonnement `OpinionEvent`, filtre `isVisible` devenu inutile,
appel direct par l'orchestrateur...) ne soient identifiées. Plutôt que de considérer ces précisions
acquises rétroactivement, une **étape 2 dédiée** a été insérée pour les intégrer explicitement, et
toutes les étapes suivantes ont été décalées d'un cran. Détail des précisions :
`docs/etudes/etude-branchement-persistance-decision-engine.md` §B, option B3, addendum du 2026-08-12.

**Note de suivi (2026-08-13)**, décisions prises avec Clem avant rédaction du prompt de l'étape 3
(`docs/prompts/prompt-implementation-decision-palier3-etape3.md`) : l'étape 3 reste un lot
d'**extensions de modèle pures** (DTO/entités en écriture uniquement). Le bug
`JpaEventStore.toDomain()` (switch sans cas `DECISION`, cf. étude §C1 point (a)) est **explicitement
reporté à l'étape 4** — l'étape 3 ne touche ni `JpaEventStore`, ni aucun chemin de désérialisation/
rejeu. Le nouveau champ `ScenarioEvent.scope` introduit par l'étape 3 est nullable, sans migration des
lignes déjà persistées dans `scenario_events` — cette compatibilité historique reste également à
traiter, si besoin, à l'étape 4.

**Note de suivi (2026-08-13, suite)**, décisions prises avec Clem avant rédaction du prompt de
l'étape 4 (`docs/prompts/prompt-implementation-decision-palier3-etape4.md`) :
- Deux tables de snapshot dédiées (`scenario_snapshots`, `decision_snapshots`), pas de table générique.
- Nouvelles méthodes "toutes les données actives, tous owners" sur `ScenarioEngine`/`DecisionEngine`
  (choix de méthode laissé à l'appréciation de l'implémenteur par Clem).
- Job de photo quotidienne désactivé par défaut + endpoint REST admin pour déclenchement manuel (patron
  `EtfFlowAdminController`) — pas d'activation automatique en prod dans ce lot.
- Rejeu delta au redémarrage : couvre mutations **et** créations d'entités apparues après la dernière
  photo (requête globale par date, pas ciblée par `targetId` connu).
- **Découverte en préparant ce prompt** : `Decision.id` (utilisé comme `targetId` des `DecisionEvent`
  persistés) et `DecisionSnapshot.decisionId()` sont deux identifiants distincts — une reconstruction
  de `Decision` doit préserver `Decision.id`, pas seulement `snapshot.decisionId()`. Un constructeur de
  reconstruction est ajouté à `Decision` (même patron que `DefaultMarketScenario`, étape 3).
- **Bug préexistant découvert au même moment, sans rapport avec la persistance** :
  `Decision.generateDecisionId()` est appelé avant que `type`/`createdAt` soient assignés dans le
  constructeur existant, produisant systématiquement un id `"null-null-<8 chars>"` en prod — corrigé
  dans l'étape 4 (réordonnancement des assignations), les id déjà persistés ne sont pas rétroactivement
  corrigés.

**Note de suivi (2026-08-13, suite)**, décisions prises avec Clem avant rédaction du prompt de
l'étape 5 (`docs/prompts/prompt-implementation-decision-palier3-etape5.md`) : double hook pour le champ
`User.lastLogin` — `AuthController.authenticateUserForm` (login explicite) **et** `AuthTokenFilter`
(chaque requête authentifiée revalidant le cookie JWT, throttlé à 15 min par défaut pour éviter une
écriture en base à chaque requête). Un seul hook (login explicite seulement) aurait sous-estimé
l'activité réelle, un utilisateur pouvant rester connecté des jours via cookie sans repasser par
`signinForm`. Aucune logique d'exploitation du signal (requête "inactif depuis X", archivage) n'est
ajoutée dans ce lot — réservée à l'étape 6.

**Note de suivi (2026-08-14)** : étape 2 mergée — nettoyage `DecisionEngine` (retrait des deux lignes
de code mort), retrait du mécanisme `symbols`/`addSymbolSurvey` de `DefaultScenarioEngine` (devenu
inerte depuis l'étape 1, aucun appelant externe trouvé), `removeSymbolSurvey` renommée
`purgeScenariosForSymbol`, et décision documentée sur le maintien de la publication `OpinionEvent`
(cf. tableau et §B de l'étude). Suite de tests complète passée en vert (confirmé par Clem). L'instruction
de fin du prompt d'étape 2 demandait de rédiger ensuite le prompt de l'étape 3 — obsolète : les étapes
3, 4, 5 et 6 ont déjà été implémentées le 2026-08-13 (menées avant que 2 ne le soit, cf. tableau
ci-dessous), donc rien à rédiger sur ce point. Avec 1 et 2 désormais faites, l'étape 7 (orchestrateur)
est débloquée sur sa dépendance dure — reste à trancher les points (a)/(b)/(c) listés plus bas avant
d'en rédiger le prompt détaillé.

## Périmètre de ce palier

Rattachement du moteur de décision à l'application qui tourne, orchestration multi-utilisateur
(scheduler), sauvegarde et restauration de l'état (`Decision`/`MarketScenario`). **Hors périmètre,
explicitement** : le composant d'exécution réelle (`ProviderApiService.buy/sell`, argent réel,
dry-run obligatoire — point d'avancement 2026-08-10 §6.2 pt 5) et le calcul de sizing (étude §4/§7).
Ce palier prépare le terrain pour eux, ne les construit pas.

## Ordre des étapes

| # | Étape | Statut | Dépend de | Doc de référence |
|---|---|---|---|---|
| 1 | Branchement — moteur unique partagé (option B3) | ✅ Fait (2026-08-12) | — | `etude-branchement-persistance-decision-engine.md` §B, option B3 + §E pt 1 |
| 2 | Compléments au branchement — alignement fin sur le partage owner-en-paramètre | ✅ Fait (2026-08-14) | 1 | `etude-branchement-persistance-decision-engine.md` §B, option B3, addendum 2026-08-12 |
| 3 | Extensions de modèle pour la persistance | ✅ Fait (2026-08-13) | 1 (recommandé, pas strictement bloquant) | `etude-branchement-persistance-decision-engine.md` §C ("Ce qui a été vérifié en creusant") + §E pt 4 |
| 4 | Persistance — photo quotidienne + rejeu delta + restauration + fix `JpaEventStore.toDomain()` (switch cas `DECISION`, reporté depuis l'étape 3 le 2026-08-13) | ✅ Fait (2026-08-13) | 1, 3 | `etude-branchement-persistance-decision-engine.md` §C (C1/C2/C4) + §E pt 3 |
| 5 | Détection de connexion utilisateur | ✅ Fait (2026-08-13) | — (indépendant, peut démarrer n'importe quand) | `etude-branchement-persistance-decision-engine.md` §E pt 5 (mention) |
| 6 | Archivage sur inactivité prolongée | ⬜ À faire, bloqué par 4 et 5 | 4, 5 | `etude-branchement-persistance-decision-engine.md` §E pt 5 |
| 7 | Orchestrateur — calcul Opinion + propagation User×Wallet×Asset + verrou anti-doublon | ⬜ À faire, bloqué par 1 et 2 ; s'appuie sur 4 et 5 | 1, 2 (dur — appelle directement la méthode que l'étape 2 introduit), 4 et 5 (recommandé) | `etude-branchement-persistance-decision-engine.md` §E pt 6 + point d'avancement 2026-08-10 §6.2 pt 7 (addendum) |
| 8 | Calendrier macro dans le cycle de l'orchestrateur (optionnel, en fin de palier) | ⬜ À faire, bloqué par 7 | 7 | point d'avancement 2026-08-10 §3 et §6.2 pt 6 (addendum) |

**Pourquoi cet ordre précisément** :
- 1 avant tout le reste : sans le moteur partagé, "persister l'état" et "orchestrer par utilisateur"
  n'ont pas d'objet — on ne sait même pas encore quelle forme prend l'état à sauvegarder tant que le
  refactor n'a pas fixé comment l'owner circule.
- 2 juste après, en petit lot dédié plutôt que noyé ailleurs : c'est le nettoyage direct de ce que 1 a
  laissé en suspens (abonnement `OpinionEvent` à retirer, `symbols`/liste de surveillance
  probablement à supprimer, `isUnanimousAcrossScopes` à corriger pour lire `event.getOwner()`, filtre
  `isVisible` devenu inutile). Le faire tôt évite que le reste du palier ne s'appuie sur une forme
  transitoire du moteur.
- 3 dépend surtout de 1 (pas de 2) : les extensions de modèle (`DecisionEvent`+`ActionStep`,
  reconstruction `DefaultMarketScenario`, `ScenarioEvent`+`scope`) sont des changements de DTO
  indépendants du nettoyage fait en 2 — peut en théorie se faire en parallèle de 2, listé après par
  clarté de séquencement plutôt que par dépendance stricte.
- 4 dépend de 1 et 3 : la mécanique de photo/rejeu n'a rien à sauvegarder de fiable tant que les
  extensions de modèle ne sont pas en place.
- 5 est volontairement isolée et placée tôt dans le reste de la liste : petite (un champ + un hook au
  login), indépendante de tout le reste, et deux étapes plus loin en ont besoin — autant la sortir du
  chemin critique immédiatement.
- 6 dépend de 4 (il faut savoir prendre une photo avant de pouvoir en prendre une "de sortie") et de 5
  (signal de reconnexion).
- 7 (l'orchestrateur) dépend cette fois **directement et fortement** de 2 : il appelle la méthode
  directe que 2 introduit à la place de l'ancien abonnement `OpinionEvent` — sans 2, il n'a rien à
  appeler. Placé après 4 par prudence, pas par obligation stricte : un orchestrateur qui tourne sans
  persistance en place recréerait le même risque qu'aujourd'hui (perte d'état à chaque redémarrage),
  en pire — puisqu'il produirait de l'état en continu au lieu de rien. Bénéficie aussi de 5 pour son
  déclenchement "à la connexion".
- 8 en dernier et optionnelle, comme demandé par Clem le 2026-08-12 pour ne pas l'oublier sans pour
  autant bloquer le reste du palier dessus — le calendrier macro peut s'accrocher au cycle
  quotidien de 7 une fois que celui-ci existe, ou rester explicitement reporté encore une fois si
  Clem le décide à ce moment-là.

## Points encore à trancher avant de rédiger le prompt détaillé de chaque étape

Ne pas improviser ces réponses au moment d'écrire le code — à reconfirmer avec Clem, brièvement,
juste avant de lancer l'étape concernée :

- **Étape 2** : ✅ tranché le 2026-08-14 (cf. `etude-branchement-persistance-decision-engine.md` §B) —
  publication conservée (audit/persistance + unique canal de transmission du résultat d'Opinion),
  documenté en javadoc sur `DefaultScenarioEngine.onOpinionEvent(...)`.
- **Étape 4** : ✅ tranché le 2026-08-13 (cf. note de suivi ci-dessus) — deux tables dédiées, rejeu
  delta mutations+créations, cron désactivé par défaut + endpoint admin.
- **Étape 6** : ✅ tranché le 2026-08-13 — 2 mois retenu tel quel, taggé `// TODO parametrize` dans le
  code ; job d'archivage même patron que la photo (désactivé par défaut + endpoint admin) ; marqueur
  explicite `User.archivedAt` ; hook de restauration uniquement sur `signinForm` (le JWT expire à 24h,
  `AuthTokenFilter` ne peut structurellement jamais authentifier un utilisateur inactif depuis 2 mois).
- **Étape 7** : (a) l'itération se fait-elle sur les 3 actifs fixes du périmètre DCA (BTC/ETH/PAXG)
  pour tout utilisateur actif, ou sur une liste dérivée autrement ? Le tour d'horizon du 2026-08-12 a
  écarté "déduire depuis les soldes de wallet" (exclurait à tort un utilisateur qui veut démarrer un
  DCA sur un actif qu'il ne possède pas encore) — itérer sur les 3 actifs fixes semble le choix le
  plus simple, à confirmer explicitement plutôt que supposer. (b) durée exacte du verrou anti-doublon
  (1h proposé par Clem comme point de départ). (c) l'activation réelle de l'orchestrateur (cron actif
  en prod) fait-elle partie du périmètre de cette étape, ou seulement sa conception/son code,
  activation restant elle-même postposée comme l'était le scheduler jusqu'ici ?
- **Étape 8** : à ce moment-là seulement — inclure ou reporter explicitement, ne pas laisser la
  question retomber dans l'oubli une deuxième fois.

## Suivi

Une fois une étape terminée, mettre à jour son statut dans le tableau ci-dessus (✅) avant de lancer
la suivante — c'est ce tableau qui sert de source de vérité sur l'avancement, pas la mémoire d'une
conversation précédente. Rédiger le prompt d'implémentation détaillé d'une étape seulement juste
avant de la lancer (pas toutes à l'avance) — les points encore à trancher ci-dessus évolueront
peut-être d'ici là.
