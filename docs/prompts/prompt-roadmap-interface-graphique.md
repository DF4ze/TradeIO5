# Prompt — Roadmap : interface graphique (backends puis pages)

> À donner à un agent de développement. Trois gros lots (Back, Front, Finitions) enchaînés en autonomie : **point avec Clem à la fin de chaque gros lot** (et seulement avant si un point ambigu ou irréversible bloque). Respecter `docs/CODING_RULES.md` (ne pas coder ce dont on n'a pas besoin, Lombok, logs INFO/DEBUG, `getFirst()/getLast()`, pas de nœud fiat). Mettre à jour la doc concernée à chaque lot, **sans historique** (ce qui n'est plus vrai est supprimé). Demander à Clem avant de trancher tout point ambigu.

## Objectif

Construire l'interface utilisateur de TradeIO5 (mobile d'abord, PC soigné, multi-utilisateurs), dans l'ordre : **(1)** lister précisément les backends manquants, **(2)** les implémenter, **(3)** réaliser les pages sur la charte graphique. Admin = pages séparées (hors périmètre sauf lot final).

## À lire d'abord (références, dans cet ordre)

1. `docs/README.md` puis `docs/architecture/08-rainbow-live.md`, `10-execution-plan.md`, `10-execution-reelle.md`, `docs/api/rest-endpoints.md`.
2. **`docs/graphique/inventaire-options-affichage.md`** — liste des actions utilisateur et des informations affichées, avec l'état [OK] / [Prévu] / [À implémenter]. **C'est le document à tenir à jour** : chaque besoin back découvert y est ajouté (§6), chaque élément livré passe à disponible.
3. `docs/etudes/etude-architecture-interface.md` — hiérarchie de l'information, navigation, écrans, modes, états limites, admin.
4. **`docs/graphique/charte-graphique.md`** — tokens CSS, typographie, Rainbow, composants, graphiques, catalogue de widgets. Mode sombre uniquement (V1) ; le mode clair viendra après.
5. Maquettes : Artifact « TradeIO Maquettes » (boards `PlaqueB_Tel`, `PlaqueB_PC`, Accueil / Mes actifs / Fiche / Journal / Réglages / Onboarding en mobile et PC, `Widgets courbes v3`). Les données y sont simulées.

## Décisions figées

- Pile : Thymeleaf + JS vanilla (comme `/user` et `/admin`), `textContent` uniquement (jamais `innerHTML`), pas de framework front. Graphique de la fiche : **TradingView Lightweight Charts** + plugin de mesure Shift-clic (% et durée) écrit pour l'occasion ; widgets simples en SVG.
- Navigation : Accueil | Mes actifs | Journal | Réglages. Fiche actif = écran central. Le mot « lien / binding » n'apparaît jamais côté utilisateur (« + Ajouter un actif »).
- Accueil : patrimoine + PnL, décisions du jour, alertes seulement si présentes. Onboarding tant qu'aucun actif.
- Modes Simple / Avancé / Expert : seul Expert est fonctionnel aujourd'hui ; contenu de Simple et Avancé à trancher avec Clem (proposition dans l'inventaire §5).
- Graphique : bandes Rainbow **figées** quand elles existent, **recalculées** (pointillé, « simulé ») avant le premier run.
- L'affichage ne lit que la **base** (aucun appel exchange, sauf `check` et `path-quote` déclenchés par l'utilisateur) : respecter les règles de rate limit de `CODING_RULES.md`.
- Jour = jour UTC ; passe quotidienne 23:55 UTC.
- Widgets : Clem **choisit** ceux de la V1 dans le catalogue de la charte (§7) ; la V2 laissera l'utilisateur choisir. **Ne construire que les backs des widgets retenus.**
- Sécurité (CSRF, inscription publique, authentification) : audit **dans une autre conversation**, à faire avant d'inviter des utilisateurs ; ne pas le traiter ici, mais ajouter toute remarque rencontrée dans `docs/securite/remarques-audit.md`.
- Aucun ordre réel, aucun appel authentifié à un exchange sans accord explicite de Clem.

## Lots

Regroupement voulu par Clem (peu disponible) : **Lot B = lots 1 à 4 (back)**, **Lot F = lots 5 à 7 (front : socle, pages, widgets)**, **Lot 8 = finitions**. Le lot 0 (cadrage) est clos : widgets V1 et décisions dans `inventaire-options-affichage.md` §6. Dans chaque gros lot, les sous-lots restent l'ordre de travail ; tests verts et doc à jour avant de rendre la main.

### Lot 0 — Cadrage (avant tout code)
1. Présenter à Clem le catalogue de widgets et la liste des pages ; **elle fige les widgets de la V1** et le contenu des modes Simple / Avancé.
2. Pour chaque page et widget retenu : donnée affichée → source existante (endpoint/DTO) ou manquante. Produire le **tableau des backends manquants** en complétant `inventaire-options-affichage.md` §6 (besoin, DTO cible, endpoint, source en base, volume/fréquence, utilisé par).
3. Points de conception à trancher avec Clem, par écrit, avant le lot 1 (voir « Points à instruire » ci-dessous).
4. Livrable : inventaire mis à jour + plan de lots back et front validé.

### Lot 1 — Historisation (socle de données)
Instantanés **quotidiens immuables** (au jour UTC) nécessaires aux courbes : valeur du patrimoine, valeur en actifs et en stable, par actif / provider / wallet ; exposition ; bornes de modulation en vigueur ce jour-là (historique des réglages utilisateur). Job planifié branché sur la passe existante, idempotent, rattrapage des jours manquants **non** prévu sauf accord. Migration (Flyway préparé, non activé : suivre `docs/operations/flyway.md`). *Accept.* : une journée de passe produit exactement un instantané par périmètre, relançable sans doublon.

### Lot 2 — Endpoints de lecture « patrimoine et performance »
Patrimoine + PnL par période (7 j / 30 j / 90 j / 1 an / Tout), valeur et PnL par actif, stable vs actifs par périmètre (par actif providers regroupés ; actif / provider ; par provider actifs regroupés) avec bornes min / max, exposition dans le temps, autonomie courante agrégée, record et marge de sécurité (dérivables), investi par mois, prix moyen vs cours. Uniquement ceux des widgets retenus.

### Lot 3 — Endpoints « graphique et décisions »
Lecture des achats / ventes réels et prix moyen d'achat (transactions) ; historique des bandes Rainbow par jour (blocs de signal des runs, aujourd'hui non exposés) + calcul `simulate` avant le premier run ; Rainbow vs DCA fixe (PnL réel / potentiel cumulés, mêmes dates et mêmes dépôts) ; calendrier des décisions par jour (dont carry) ; prochain achat estimé ; prochaine passe (prochain déclenchement du planificateur).

### Lot 4 — Journal, alertes, réglages
Journal : filtres serveur (actif, wallet, statut, raison, période), décisions et alertes hors exécution. Alertes utilisateur (agrégation + configuration). Clés de lecture saisies par l'utilisateur, plafonds utilisateur configurables dans les bornes absolues (avec avertissement à l'approche), profil Prudent / Équilibré / Dynamique (bande de départ), préférences d'apparence (thème sombre / clair, mode d'affichage) persistées côté serveur.

### Lot 5 — Socle front
Feuille de styles à partir des tokens de la charte (thème via `data-theme`), mise en page mobile et PC, navigation, composants communs (carte, pastille de statut, puces, segmenté, interrupteur, barre, curseur, jauge en arc, infobulle), chargement des polices, gestion 401/403 → `/login`, états vide / chargement / erreur / « — ». Page de démonstration des composants.

### Lot 6 — Pages
Dans cet ordre, point avec Clem après chacune : **Accueil** → **Mes actifs** → **Fiche actif** (graphique Lightweight Charts, frise Signal → Modulation → Plafonds → Plan → Exécution → Résultat, « Pourquoi ce montant ? ») → **Journal** → **Réglages** (modulation puissance / biais / bande, mode, clés, plafonds, apparence) → **Onboarding** (4 étapes). L'ancienne page `/user` reste jusqu'à parité fonctionnelle, puis est retirée.

### Lot 7 — Widgets
Catalogue retenu intégré aux pages ; sélection par l'utilisateur = V2, non faite ici.

### Lot 8 — Finitions
Admin (pages séparées : Exploitation, Données, Stratégies, Utilisateurs, Paramètres globaux, Santé) : reprise de la charte sur l'existant, l'écran Données (`asset_provider`) exigeant d'abord de résoudre l'écrasement par `AssetInitializer`. Mode clair de la charte. Accessibilité et revue de contraste.

## Points à instruire au lot 0 (étapes qui risquent d'être oubliées)

- **Historisation** : la courbe de patrimoine n'existe pas sans instantanés quotidiens ; décider à partir de quelle date ils démarrent (pas de reconstruction rétroactive ?) et ce qu'on affiche avant.
- **Répartition par actif / provider en %** : dépend de l'étude de répartition de l'investissement (pas de rééquilibrage entre exchanges, % sur la part investie, modulateur par provider, tolérance admin) — non écrite ; trancher si elle précède le widget « Stable vs actifs par provider ».
- **Données de démonstration** : jeu de données fictif + mode démo pour développer et tester les pages sans passe live.
- **Tests** : DTO / endpoints (isolation multi-utilisateurs, 404 sur ressource d'autrui), rendu des pages, absence d'`innerHTML`, cas limites (UNAVAILABLE, BELOW_MIN, carry, plan expiré), parcours mobile et PC.
- **Livraison des alertes** : affichage dans l'app seulement, ou aussi e-mail / notification ? (hors périmètre sauf décision.)
- **Performance** : volume des séries (1 an quotidien), agrégations côté serveur, cache des lectures.
- **Langue** : français uniquement ? (pas d'i18n sauf décision.)
- **Navigateurs et mobile** : navigateurs ciblés, installation en application (PWA) ou non.
- **Migration de `/user`** : calendrier de retrait, redirections.
- **Gel des widgets V1** et contenu des modes Simple / Avancé (lot 0, point 1).

## Règles

Un gros lot = un point avec Clem. Documenter à chaque lot : `inventaire-options-affichage.md`, `docs/api/rest-endpoints.md`, `docs/architecture/*` concernés, `docs/README.md` si un fichier est ajouté. Tests verts avant chaque point. Ne pas introduire de dépendance front sans accord.
