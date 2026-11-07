# Prompt d'implémentation — Espace Expert, lot 4 : écrans `/app/expert/{trend|rainbow|affectation}`

Contexte : TradeIO-5 (Spring Boot / Thymeleaf / JS natif sans framework, Java 21, Maven). Lire dans cet ordre avant toute chose :
1. `docs/README.md` (la doc doit rester à jour : tout ajout, suppression ou modification d'architecture est documenté, sans historique ; ce qui n'est plus valable est supprimé).
2. `docs/etudes/etude-espace-expert.md` (décisions, trame commune, tableau des lignes du pine, graphique de référence).
3. `docs/prompts/prompt-roadmap-espace-expert.md` (règles transverses ; tu fais le **lot 4**).
4. `docs/architecture/11-front-app.md` (coquille `/app`, menu, modes d'affichage, conventions JS), `docs/graphique/charte-graphique.md` (charte : thème sombre, Rainbow discret), `docs/api/rest-endpoints.md` (API `/api/expert/*` des lots 2 et 3).
5. `docs/known-gaps/nettoyages.md` (y renvoyer tout nettoyage différé).

## Objectif

Livrer l'Espace Expert côté utilisateur : trois écrans qui consomment l'API des lots 2 et 3, dans la coquille et la charte de `/app`, visibles et servis **uniquement en mode d'affichage EXPERT**. Supprimer l'ancienne page `/user`. Le backend est quasi complet ; ce lot ne touche au backend que pour les petits compléments listés plus bas.

## État de départ (lots 1 à 3 livrés)

- Coquille : `AppPageController` (pages Thymeleaf minces, aucune donnée de trading dans le rendu serveur), `AppShell.populate` (pose `displayMode` en minuscules, `admin`, thème sombre seul), `templates/app/fragments.html` (menu `nav`, scripts), JS par page `static/assets/app/js/page-*.js` sur les helpers `core.js` (`TIO.h`, `TIO.api`, `TIO.toast`, `TIO.ui.*`, `TIO.modeAtLeast`…), graphiques Lightweight Charts déjà embarqués (`static/assets/app/vendor/`, `charts.js`, `asset-chart.js`, `page-asset.js`).
- Le menu contient encore l'entrée « Espace Expert » vers `/user` (`side-only`, toujours visible) ; `MainController` sert `/user` (`userPage.html`).
- API : `/api/expert/assets` (providers, `exploitable`, `simulable`, éligibilité), presets Trend et Rainbow (liste par actif, détail, création, modification avec `revision`, duplication vers le même actif ou un autre, suppression, `editable`, `mine`, usages), Affectations (`GET`/`PUT`, écart de provider), simulations asynchrones (`POST` → 202, `GET /{id}` → progression puis `SimulationResultDto` : méta, éligibilité, phases, bascules, métriques Trend, `chart` aux noms de colonnes du gabarit de rejeu, `lines` = lignes du tableau du pine).
- Graphique de référence : `tools/rainbow-replay/replay-template.html` (généré par `RainbowAtrReplayMain`) : à **reprendre comme base** (prix + bornes ATR / zones / ATH, marqueurs achat / vente / Bull-Bear / To the moon / stop moon, PnL, exposition actif / stable / total, **taille du bag** et réserve moon, détail du jour au survol, tableau des jeux). Ne pas relancer `RainbowAtrReplayMain` en réel sans accord de Clem (coûteux) ; le gabarit se lit comme source de code.

## Décisions déjà prises (ne pas rouvrir)

**Accès et navigation**
- Routes : `/app/expert` (redirige vers `/app/expert/trend`), `/app/expert/trend`, `/app/expert/rainbow`, `/app/expert/affectation`. Coquille et menu de `/app`, charte actuelle (thème sombre).
- Serveur : si le mode d'affichage de l'utilisateur n'est pas EXPERT ⇒ redirection vers `/app` (réutiliser la lecture de préférences d'`AppPageController`). Anonyme ⇒ login comme les autres pages.
- Menu : l'entrée « Espace Expert » ne s'affiche **que si `displayMode == 'expert'`** et pointe vers `/app/expert`. Sous-navigation à trois onglets dans la page : Trend · Rainbow · Affectation. Aucune notion de passes, planification, plan, exécution, audit.
- **`/user` est supprimé** : route de `MainController`, `userPage.html`, tests associés, JS / fragments devenus orphelins (`rainbow-live.js`, `fragments/rainbowLive.html`… à vérifier : supprimer s'ils ne servent qu'à `/user`, sinon consigner dans `docs/known-gaps/nettoyages.md`), toutes les mentions en doc.

**Mobile**
- PC d'abord. Sur téléphone, tout reste utilisable (empilement, graphique pleine largeur, tableaux défilants) sans optimisation poussée, **et un bandeau visible** (dans la page, sous le titre, sur petits écrans seulement) indique en français que la vue est incomplète sur téléphone et qu'il vaut mieux utiliser un PC.

**Trame commune Trend / Rainbow**
1. En-tête : sélecteur d'**actif** (liste de `/api/expert/assets`), sélecteur de **provider** (providers de l'actif, avec indication du provider de référence du preset), sélecteur de **preset** (groupes Système / Mes presets), puis les paramètres du preset en champs typés avec libellé français, unité et aide courte (descripteurs de champs côté JS ; **ne pas dupliquer les bornes de validation** : afficher les erreurs par champ renvoyées par l'API).
2. Graphique Lightweight Charts qui formalise le preset (voir plus bas).
3. Fenêtre de résultats = les **14 lignes du tableau du pine** (`lines`, toutes affichées pour commencer) + **sélecteur de plage** (début / fin, raccourcis 6 mois / 12 mois / 24 mois / maximum ; défaut 24 mois comme le serveur) + métriques Trend pour l'onglet Trend.
- Actif et provider choisis sont partagés entre les onglets (paramètres d'URL `?asset=BTC&provider=…`, conservés à la navigation).

**Lancement des simulations**
- **Bouton « Simuler »** (rien ne part tout seul). Les paramètres modifiés mais non enregistrés forment un **brouillon** envoyé tel quel (`draft`) ; l'enregistrement est un geste distinct.
- Le dernier résultat reste affiché et est marqué **« périmé »** dès que preset, brouillon, actif, provider ou plage changent. Les paramètres de la dernière simulation (par type, actif, preset) sont mémorisés côté navigateur pour pré-remplir plage et provider à la réouverture et proposer « Relancer » (le résultat lui-même n'est pas persisté : durée de vie serveur de 30 minutes).
- Afficher l'avancement (`QUEUED → LOADING_DATA → COMPUTING`), les erreurs (422 éligibilité : points requis / disponibles par provider ; 429 débit ; 409 simulation déjà en cours ; 403) en messages clairs, et les avertissements de `meta.warnings` (provider substitué : provider demandé et provider utilisé visibles).

**Droits et édition**
- Preset SYSTEM pour un USER : champs en lecture seule, bandeau « Preset système : duplique-le pour le modifier » + bouton **Dupliquer** (même actif ou autre actif, nom proposé). L'USER peut toujours **simuler** un preset système.
- ADMIN sur un preset SYSTEM : champs éditables ; avant d'enregistrer, confirmation qui indique que la modification se propage à toutes les Affectations qui l'utilisent et réinitialise leur moteur, avec le **nombre** d'Affectations concernées dont celles à moteur actif (voir compléments backend).
- **Avertissement obligatoire quand on modifie un preset déjà engagé dans un moteur actif** : si au moins une Affectation à `enabled = true` utilise le preset (celles de l'utilisateur pour un preset USER ; toutes pour un preset SYSTEM modifié par un ADMIN), la confirmation d'enregistrement le dit explicitement (« moteur actif sur BTC : l'enregistrement réinitialise son état »). Même avertissement avant un changement d'Affectation sur un actif dont le moteur est actif.
- Création (copie d'un preset ou défauts), renommage, suppression (preset USER ; 409 si utilisé : afficher la liste des Affectations qui l'utilisent), conflit de `revision` (409 : proposer de recharger). Un brouillon non enregistré qui quitte la page déclenche un avertissement.

**Onglet Affectation**
- Pour un actif : trois sélecteurs (Trend, Rainbow UP, Rainbow DOWN) alimentés par les presets de l'actif ; bouton **Affecter** (confirmation + avertissement ci-dessus) ; signalement des **écarts de provider** entre presets et liens ; état du moteur **affiché en lecture seule** (allumé / éteint) avec lien vers la fiche de l'actif dans « Actifs gérés » (l'interrupteur n'existe qu'à cet endroit).
- Graphique de la simulation `AFFECTATION` : phases UP / DOWN colorées en fond, preset Rainbow actif sur chaque phase (légende), bascules.
- Un preset peut occuper UP et DOWN à la fois ; une Affectation incomplète est signalée.

**Graphique (v1, base du rejeu Java)**
- Un composant JS réutilisable (ex. `expert-chart.js`) dérivé de `replay-template.html`, alimenté par `ChartDto` (pas par des données embarquées) : prix en chandeliers avec bornes ATR / zones / ATH ; marqueurs achat / vente / Bull-Bear / To the moon / stop moon ; zones **inactives** (hors régime) grisées d'après `trend` / `active` ; courbes PnL (réalisé, potentiel, total, DCA fixe) ; exposition actif / stable / total (`wallet`) ; **taille de position (bag) et réserve moon** ; échelles de temps synchronisées ; panneau de détail du jour (survol / clic) ; options d'affichage activables (marqueurs, bandes, panneaux). Onglet Trend : prix + phases UP / DOWN + bascules, sans les panneaux Rainbow.
- Charte : thème sombre, Rainbow discret, couleurs vert / rouge conventionnelles ; libellés et nombres via les helpers de formatage existants (`TIO.fmt`).

## Compléments backend autorisés (petits, testés, documentés)

1. L'usage d'un preset (`UsageDto`) porte aussi `enabled` de l'Affectation concernée, pour que l'écran sache si un moteur actif est engagé.
2. Endpoint ADMIN d'**impact** d'un preset SYSTEM (`GET /api/expert/{trend-presets|rainbow-presets}/{id}/impact`) : nombre d'Affectations qui l'utilisent et nombre à moteur actif ; **comptes seulement**, aucune identité d'utilisateur (la vue Admin « quel preset chez quel utilisateur » reste un TODO).
3. Si l'API ne permet pas de retrouver la dernière simulation de l'utilisateur pour (type, actif), un `GET /api/expert/simulations?type=&asset=` renvoyant l'identifiant de la dernière simulation encore en mémoire (404 sinon) ; sinon s'en passer.

## Points à valider avec Clem avant de coder (poser les questions, ne pas deviner)

1. Libellés des champs des presets Trend et Rainbow : proposer une liste (nom, unité, aide d'une ligne) pour validation avant de câbler les formulaires, car c'est ce que Clem lira le plus.
2. Rendu des **zones inactives** sur les graphiques Rainbow : grisé léger (proposition) ou masquage total ; et affichage des événements qui continuent hors régime (trailing stop, To the moon) — maintenus visibles.
3. Panneaux affichés par défaut (proposition : prix + bag + PnL ; exposition repliée) et mémorisation des options d'affichage.

## Ce que tu dois faire

1. Cartographier : `AppPageController`, `AppShell`, `fragments.html` (nav, scripts), `core.js` / `ui.js` / `charts.js` / `asset-chart.js` / `page-asset.js` (conventions, composants), `tradeio.css` (classes disponibles), `MainController` (`/user`), tests de pages existants ; repérer ce qui est réutilisable pour éviter les doublons.
2. Compléments backend (si retenus) + tests.
3. Pages et menu : routes `/app/expert*` avec garde EXPERT, entrée de menu conditionnelle, suppression de `/user` et de ses orphelins.
4. JS : `page-expert-trend.js`, `page-expert-rainbow.js`, `page-expert-affectation.js`, module commun (sélecteurs actif / provider / preset, formulaire de paramètres, client de simulation avec sondage de progression, gestion d'erreurs, brouillon / périmé, confirmations), composant graphique `expert-chart.js`, fenêtre de résultats. Pas de nouvelle dépendance. Respecter `?demo=1` des autres pages s'il s'applique ; sinon ne pas le casser.
5. CSS dans `tradeio.css` (ou fichier dédié chargé sur ces pages) : mise en page PC, repli téléphone, bandeau « vue incomplète sur téléphone ».
6. Tests : contrôleur de pages (EXPERT ⇒ 200 ; SIMPLE / ADVANCED ⇒ redirection `/app` ; anonyme ⇒ login ; `/user` n'existe plus), visibilité du menu selon le mode, compléments backend (usage `enabled`, impact ADMIN seulement, comptes sans identités). Si le dépôt a une infra de test JS, tester les fonctions pures (brouillon, périmé, formatage) ; sinon vérification manuelle décrite.
7. **Vérification dans un navigateur** (Claude in Chrome ou navigateur intégré si disponible, serveur de dev) : parcours USER (consulter un preset système, simuler, dupliquer, modifier sa copie, affecter) et ADMIN (modifier un preset système avec confirmation d'impact), simulation sur BTC / ETH / PAXG, affichage sur largeur téléphone. Ne rien lancer d'irréversible ; annoncer ce qui n'a pas pu être vérifié.
8. Doc : `docs/architecture/11-front-app.md` (routes, menu conditionnel, pages Expert, composant graphique, suppression de `/user`), `docs/api/rest-endpoints.md` (compléments), `docs/graphique/` (inventaire / charte si concernés), étude (écrans passés en « décidé », points tranchés), sans historique.

## Hors périmètre

Découverte et création d'actifs (chantier à part : seuls BTC / ETH / PAXG sont proposés), vue Admin des Affectations par utilisateur (TODO), optimisation mobile poussée, nettoyage final et code mort hors `/user` (lot 5), toute modification de la logique du moteur de simulation (lot 3).

## Critères d'acceptation

- Build vert (MCP JetBrains), tests verts ; annoncer le résultat réel, ne pas supposer.
- Un utilisateur non EXPERT ne voit ni le menu ni les pages (redirection) ; `/user` a disparu du code et de la doc.
- Un USER simule les trois types, duplique un preset système et modifie sa copie ; un ADMIN modifie un preset système après confirmation d'impact ; l'avertissement « moteur actif » apparaît dans tous les cas prévus.
- Le graphique reprend les éléments du rejeu Java (achats / ventes / To the moon / ATH, PnL, exposition, taille du bag) et grise les zones inactives ; les 14 lignes du pine sont affichées ; la plage est sélectionnable.
- Sur téléphone, le bandeau « vue incomplète » s'affiche et rien n'est inutilisable.
- Aucune requête exchange déclenchée hors simulation ; doc à jour, rien d'historisé, nettoyages différés consignés dans `docs/known-gaps/nettoyages.md`.

## Style (préférences de Clem)

`isEmpty()` / `getFirst()` / `getLast()` ; `assertTrue` / `assertFalse` directs ; pas de variables locales redondantes ; constantes métier mutualisées plutôt que dupliquées ; valeurs par défaut uniquement dans les Initializers.
