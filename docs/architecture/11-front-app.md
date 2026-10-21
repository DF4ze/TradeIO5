# Application utilisateur `/app` (front)

Vérifié le : 2026-10-21. Charte : [`../graphique/charte-graphique.md`](../graphique/charte-graphique.md) ; données affichées : [`../graphique/inventaire-options-affichage.md`](../graphique/inventaire-options-affichage.md) ; endpoints : [`../api/rest-endpoints.md`](../api/rest-endpoints.md).

## Pages

`AppPageController` (`@PreAuthorize("isAuthenticated()")`) sert une coquille Thymeleaf par page ; **aucune donnée de trading dans le rendu serveur** : tout vient des `/api/**` chargés par le navigateur. Le serveur pose seulement `data-theme` (préférence), `data-mode` (Simple / Avancé / Expert), `data-user`, `data-page`, `data-symbol`.

| Route | Contenu |
|---|---|
| `/app` | Accueil : alertes, patrimoine + PnL (« hors dépôts/retraits »), décisions du jour, prochaine passe, cash et autonomie, prix moyen vs cours ; Avancé : exposition, zone libre, puissance, biais, reporté |
| `/app/assets` | Une carte par lien : statut, position, valeur, PnL, dernière décision, montant de base, interrupteur d'exécution (confirmation en ligne), confirmation du 1ᵉʳ ordre ; Avancé : part de la position offerte |
| `/app/assets/{symbole}` | Fiche : graphique (Lightweight Charts : cours, bandes Rainbow figées ou simulées, achats ▲ / ventes ▽, prix moyen, mesure Shift-clic), frise Signal → Modulation → Plafonds → Plan → Exécution → Résultat, « Pourquoi ce montant ? » |
| `/app/journal` | Plans / exécutions (frais réels, slippage, Fee Test, carry, audit) et décisions, filtres serveur |
| `/app/settings` | Mode, profil de départ, modulation (bande, puissance, biais), clés de lecture, plafonds, alertes, apparence, compte |
| `/app/onboarding` | 4 étapes : clé de lecture → actif et wallet → profil → montant et création du lien (exécution réelle désactivée) |
| `/app/components` | Catalogue des composants (données fixes) |

`GET /` redirige un utilisateur connecté vers `/app`. `/user` reste l'espace Expert (presets, stratégies). `/admin` (6 onglets : Console, Credentials, Bindings, Testeur d'ordre, Journal, Modulation) et `/info` utilisent la même coquille et les mêmes tokens (classe `tio tio-admin`, thème de l'utilisateur) ; `css/admin-compat.css` rend les anciennes classes de mise en page (grille, boutons, alertes, tableaux, onglets) sur les tokens de la charte, sans Bootstrap ; `js/admin-tabs.js` gère les onglets. Leurs scripts restent sous `/assets/js/admin/`.

## Modes d'affichage

Préférence serveur `displayMode` (`PUT /api/user/preferences`). Simple : décisions, PnL, montant de base, interrupteur d'exécution, prix moyen. Avancé : + bande, puissance, biais, plafonds, part de la position offerte, reporté. Expert : + journal brut de modulation et lien vers `/user`.

## Mode démo

`?demo=1` : jeu de données fictif côté navigateur (`demo.js`), mêmes formes que l'API, aucun appel réseau. Réglages (préférences, modulation) gardés en mémoire de la page ; interrupteur d'exécution, confirmation du 1ᵉʳ ordre, clés et création de lien sont refusés. Ne touche jamais au kill switch ni à l'exécution.

## Organisation du code statique (`static/assets/app/`)

- `css/tradeio.css` : tokens (sombre par défaut, clair via `data-theme="light"`, mêmes noms) et composants ; polices Manrope locales (`fonts/`), Lightweight Charts v4 locale (`vendor/`).
- `js/core.js` (DOM sans HTML injecté, `TIO.api` : 401/403 → `/login`, formats « — »), `ui.js` (carte, pastille de statut, puces, segmenté, interrupteur, barre, curseur, jauge en arc, états chargement / vide / erreur, libellés), `charts.js` (courbes SVG), `asset-chart.js` (fiche), `explain.js` (frise et « Pourquoi ce montant ? »), `demo.js`, `app.js` (démarrage), `page-*.js` (une par page).
- Règles : `textContent` / création de nœuds uniquement (test `AppPageControllerTest` interdit `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval(`) ; couleurs lues dans les variables CSS ; un widget en échec n'empêche pas les autres (`ui.widget`) ; donnée absente ⇒ « — ».
