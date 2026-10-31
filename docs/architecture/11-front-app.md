# Application utilisateur `/app` (front)

Vérifié le : 2026-10-30. Charte : [`../graphique/charte-graphique.md`](../graphique/charte-graphique.md) ; données affichées : [`../graphique/inventaire-options-affichage.md`](../graphique/inventaire-options-affichage.md) ; endpoints : [`../api/rest-endpoints.md`](../api/rest-endpoints.md).

## Pages

`AppPageController` (`@PreAuthorize("isAuthenticated()")`) sert une coquille Thymeleaf par page ; **aucune donnée de trading dans le rendu serveur** : tout vient des `/api/**` chargés par le navigateur. Le serveur pose seulement `data-theme` (préférence), `data-mode` (Simple / Avancé / Expert), `data-user`, `data-page`, `data-symbol`.

| Route | Contenu |
|---|---|
| `/app` | Accueil : alertes, sélecteur de portefeuille (un ou tous), patrimoine (soldes live + courbe estimée), détail par actif (regroupé entre wallets, dépliable), exposition (donut actifs collés / stables séparés par un espace ; Avancé : + bande), transactions par exchange (manuelles et TradeIO, tagguées ; synchro en lecture seule à l'ouverture, bouton Actualiser), décisions du jour groupées par actif (dépliable par exchange si plusieurs), cash live et autonomie (`/api/portfolio/autonomy`), prix moyen vs cours (achats enregistrés, `/api/portfolio/asset-performance`) ; Avancé : zone libre (bande d'exposition : hors bande hachuré, marges de régulation rouge → orange, zone libre vert → bleu → violet, repères Min / Max, point = exposition live), puissance, biais, reporté. La prochaine passe n'est plus un widget : elle reste dans l'en-tête de page |
| `/app/assets` | **Actifs gérés** (actifs que TradeIO gère, pas tout le patrimoine) : un groupe par provider (total live du groupe), une ligne repliable par lien (position et valeur **live** via `/api/portfolio/holdings`, PnL % via `/api/portfolio/asset-performance`, dernière décision, puce d'exécution) ; détail replié : prix moyen/cours/PnL, montant de base (valeur du preset affichée si non surchargé, `globals.baseAmount` de `/api/rainbow-live/presets`), interrupteur d'exécution (confirmation en ligne), confirmation du 1ᵉʳ ordre ; Expert : part vendable de la position (`bagPercent`, 100 % par défaut, plafonne les ventes) |
| `/app/assets/{symbole}` | Fiche : graphique (Lightweight Charts : cours, bandes Rainbow figées ou simulées, achats ▲ / ventes ▽, prix moyen, mesure Shift-clic), frise Signal → Modulation → Plafonds → Plan → Exécution → Résultat, « Pourquoi ce montant ? » |
| `/app/journal` | Plans / exécutions (frais réels, slippage, Fee Test, carry, audit) et décisions, filtres serveur |
| `/app/settings` | Mode, profil de départ, modulation (bande, puissance, biais), clés de lecture, plafonds, alertes, apparence, compte |
| `/app/onboarding` | 4 étapes : clé de lecture → actif et wallet → profil → montant et création du lien (exécution réelle désactivée) |
| `/app/components` | Catalogue des composants (données fixes) |

`GET /` redirige un utilisateur connecté vers `/app`.

## Pages publiques (visiteur)

`MainController` sert, hors `AppPageController`, trois pages sans donnée serveur : `/` (présentation : accroche, couverture multi-actifs / multi-plateformes, fonctionnement, aperçus statiques des widgets en SVG/CSS à données fictives, transparence, avertissement de risque, pied de page à liens vides), `/login` et `/register` (cartes de la charte, lien retour à l'accueil, pas de choix de rôle). Un utilisateur connecté est redirigé vers `/app`. Gabarits : `templates/home.html`, `login.html`, `register.html` et `fragments/public.html` (`head`, `topbar`, `footer`). Le logo et les icônes viennent de `fragments/brand.html` (`logo`, `icons`), inclus par `fragments/public.html` et `app/fragments.html` (cf. [charte, section Logo](../graphique/charte-graphique.md)). Styles : `css/public.css` en plus de `tradeio.css` ; `js/public.js` applique le thème (sombre par défaut, bascule clair mémorisée dans `localStorage` du navigateur, jamais côté serveur car le visiteur n'a pas de préférences). `/user` reste l'espace Expert (presets, stratégies). `/admin` (6 onglets : Console, Credentials, Bindings, Testeur d'ordre, Journal, Modulation) et `/info` utilisent la même coquille et les mêmes tokens (classe `tio tio-admin`, thème de l'utilisateur) ; `css/admin-compat.css` rend les anciennes classes de mise en page (grille, boutons, alertes, tableaux, onglets) sur les tokens de la charte, sans Bootstrap ; `js/admin-tabs.js` gère les onglets. Leurs scripts restent sous `/assets/js/admin/`.

## Modes d'affichage

Préférence serveur `displayMode` (`PUT /api/user/preferences`). Simple : décisions, PnL, montant de base, interrupteur d'exécution, prix moyen. Avancé : + bande, puissance, biais, plafonds, part de la position offerte, reporté. Expert : + journal brut de modulation et lien vers `/user`.

## Mode démo

`?demo=1` : jeu de données fictif côté navigateur (`demo.js`), mêmes formes que l'API, aucun appel réseau. Réglages (préférences, modulation) gardés en mémoire de la page ; interrupteur d'exécution, confirmation du 1ᵉʳ ordre, clés et création de lien sont refusés. Ne touche jamais au kill switch ni à l'exécution.

## Organisation du code statique (`static/assets/app/`)

- `css/tradeio.css` : tokens (sombre par défaut, clair via `data-theme="light"`, mêmes noms) et composants ; polices Manrope locales (`fonts/`), Lightweight Charts v4 locale (`vendor/`).
- `js/core.js` (DOM sans HTML injecté, `TIO.api` : 401/403 → `/login`, formats « — »), `ui.js` (carte, pastille de statut, puces, segmenté, interrupteur, barre, curseur, jauge en arc, états chargement / vide / erreur, libellés), `charts.js` (courbes SVG), `asset-chart.js` (fiche), `explain.js` (frise et « Pourquoi ce montant ? »), `demo.js`, `app.js` (démarrage), `page-*.js` (une par page).
- Icônes crypto : `img/crypto/<symbole en minuscules>.svg` (483 SVG couleur du jeu spothq/cryptocurrency-icons, licence CC0, `LICENSE-CC0.md` joint) + `crypto-icons.json` (liste des symboles disponibles, chargée une fois). `ui.coin(symbole)` est le point d'appel unique des pastilles d'actif : glyphe/dégradé par défaut, remplacé par l'icône si le symbole est dans la liste, retour au glyphe si le chargement échoue. Symbole sans icône (ex. XAU) ⇒ glyphe. Ajouter un actif = déposer `<symbole>.svg` et l'ajouter à `crypto-icons.json`.
- Règles : `textContent` / création de nœuds uniquement (test `AppPageControllerTest` interdit `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval(`) ; couleurs lues dans les variables CSS ; un widget en échec n'empêche pas les autres (`ui.widget`) ; donnée absente ⇒ « — ».
