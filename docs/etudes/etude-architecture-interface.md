# Étude — Architecture de l'interface

Journal de conception (cf. [`README.md`](README.md) du dossier : à ne pas lire comme référence d'architecture du code). Date : 2026-10-15.

Périmètre : organisation des écrans, hiérarchie de l'information, navigation. **Pas** de charte graphique : voir [`../graphique/charte-graphique.md`](../graphique/charte-graphique.md). Maquettes filaires (Artifact « TradeIO Maquettes » dans claude.ai) : Accueil, Mes actifs, Fiche actif, Journal, Réglages, Onboarding, en mobile et PC.

Besoins collectés en amont : [`../graphique/remarques-gui.md`](../graphique/remarques-gui.md). Écrans existants : [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md), [`../architecture/10-execution-reelle.md`](../architecture/10-execution-reelle.md), [`etude-ux-admin-execution.md`](etude-ux-admin-execution.md).

## 1. Cible et principes

- Public : **multi-utilisateurs avéré** (amis invités). Prérequis avant ouverture : audit sécurité (CSRF, inscription publique, authentification) — cf. [`../securite/remarques-audit.md`](../securite/remarques-audit.md).
- **Mobile d'abord** (amis), **PC soigné** (usage principal du propriétaire). Même contenu, deux mises en page : barre d'onglets en bas sur mobile, nav latérale sur PC.
- **Admin = pages séparées**, jamais un calque de l'espace utilisateur. La profondeur côté utilisateur passe par les **modes** Simple / Avancé / Expert.
- Un seul composant de statut (OK / ATTENTION / BLOQUÉ / INCONNU) partout.
- Le **jour** est l'axe temporel (passe quotidienne 23:55 UTC) : « aujourd'hui » = ce que la dernière passe a décidé et exécuté.
- Lecture et action séparées : l'exécution réelle n'est déclenchée que depuis un interrupteur explicite (verrou par défaut, cf. exécution réelle).
- Vocabulaire utilisateur : **« actif »** (exchange + actif). Le « lien / binding » (provider × actif) reste un terme technique : visible uniquement en admin et dans le code. « + Ajouter un actif » crée le lien de façon transparente.

## 2. Hiérarchie de l'information

| Niveau | Contenu | Où |
|---|---|---|
| N1 — toujours visible | état d'exécution (réelle / simulation), alertes, patrimoine + PnL, exposition, cash, autonomie, décisions du jour | Accueil, bandeau d'en-tête |
| N2 — par actif | statut, valeur, position, PnL, dernière décision, graphique | Mes actifs, Fiche actif |
| N3 — réglage | montant de base, clés, bande d'exposition, puissance, biais, plafonds, mode | Réglages, bloc réglages de la fiche |
| N4 — historique | décisions, exécutions, alertes, détail d'un ordre | Journal, historique de la fiche |
| Admin | exploitation, données, stratégies système, utilisateurs, paramètres globaux, santé | pages `/admin/*` séparées |

Pipeline affiché sur la fiche, de gauche à droite : Signal → Modulation → Plafonds → Plan → Exécution → Résultat. « Pourquoi ce montant ? » déroule le calcul (montant de base × multiplicateur de signal × puissance, biais, bande d'exposition, plafonds, montant final).

## 3. Navigation utilisateur

Onglets : **Accueil | Mes actifs | Journal | Réglages**. Une fiche actif s'ouvre depuis Accueil ou Mes actifs (retour vers Mes actifs). L'onboarding est un parcours à part, affiché tant qu'aucun actif n'est configuré.

## 4. Écrans utilisateur

| Écran | Contenu | Remarques |
|---|---|---|
| Accueil | alerte (si présente), patrimoine + PnL (courbe, périodes), exposition (jauge dans la bande), cash, autonomie, décisions du jour | mobile : 1 colonne ; PC : 2 colonnes (patrimoine + exposition \| décisions du jour) |
| Mes actifs | une carte par actif (statut, mini-courbe avec points d'achat, valeur, position, PnL, dernière décision), filtre par exchange, « + Ajouter un actif » | PC : tableau dense + panneau d'aperçu de l'actif sélectionné |
| Fiche actif | **écran central** : graphique, PnL total, dernière décision (pipeline + « Pourquoi ce montant ? »), historique, réglages de l'actif | PC : graphique large à gauche, PnL / décision / réglages à droite |
| Journal | décisions et alertes groupées par jour, filtres (tout / exécutions / décisions / alertes, actif, période), détail d'une ligne | exécutions : état, mise, chemin, frais (réels, Fee Test), slippage, raison de blocage ; carry visible |
| Réglages | mode, clés d'échange (lecture seule), bande d'exposition / puissance / biais, plafonds, sécurité (exécution réelle, alertes), compte | Avancé et Expert n'apparaissent que dans leur mode |
| Onboarding | 4 étapes en accordéon : exchange (clé lecture), actif, profil (Prudent / Équilibré / Dynamique), montant et confirmation | exécution réelle coupée par défaut ; PC : récapitulatif à droite |

### Graphique de la fiche

Contenu : prix, bandes du Rainbow (zone d'achat, zone de vente, moyenne), **achats et ventes réels** (triangle plein / vide), **prix moyen d'achat**, point du jour. Bouton « Bandes » pour les masquer. Périodes 7 j / 30 j / 90 j / 1 an / Tout.

- Bibliothèque retenue : **TradingView Lightweight Charts** (Apache 2.0, tactile, léger) : zoom, pan, réticule.
- Outil de mesure au Shift-clic (% et durée entre deux points) : **non natif** dans Lightweight Charts ; **retenu : primitive de dessin (plugin) écrite pour l'occasion** (Advanced Charts, licence séparée, écartée).
- Bandes historiques : **retenu** — **figées** quand elles existent (valeurs réellement vues par le moteur au run du jour, lues dans les runs), **recalculées** (`simulate`) avant le premier run du preset, tracées en pointillé et libellées « simulé ».
- Étape V2 (non planifiée) : n'afficher que les achats/ventes réels et passer en « simulé » dès que la vue sort de la période où des actions ont eu lieu ; ligne en pointillés prolongeant la tendance des dernières bougies.

## 5. Modes d'affichage

| Mode | Ajoute | État |
|---|---|---|
| Simple | décisions, PnL, montant de base, interrupteur d'exécution | à construire |
| Avancé | + bande d'exposition, puissance, biais, plafonds, part de la position offerte | à construire |
| Expert | + paramètres de la stratégie, presets, modulation détaillée | seul mode fonctionnel aujourd'hui (page `/user`) |

## 6. Composants communs

- **Pastille de statut** : OK / ATTENTION / BLOQUÉ / INCONNU (pointillé quand les données sont indisponibles).
- **Carte actif** : utilisée dans Mes actifs, Accueil (décisions) et le panneau d'aperçu PC.
- **Frise de décision** : Signal → Montant → Plan → Exécution → Résultat, identique sur la fiche et dans le détail du Journal.
- **Jauge d'exposition** : bande choisie, zone libre, position actuelle.
- **Étiquettes de mode** (AVANCÉ / EXPERT) sur les réglages réservés.

## 7. États limites à prévoir

| Situation | Affichage |
|---|---|
| Données indisponibles / soldes illisibles | carte en pointillés, pastille « Indisponible », dernière lecture datée, aucune décision calculée |
| Mise sous le minimum (BELOW_MIN, carry) | badge « Reporté », somme reportée, choix : augmenter la mise ou laisser reporter ; avertir que modifier le montant de base remet le carry à zéro |
| Plan bloqué (plafond, solde insuffisant) | pastille BLOQUÉ + raison dans la frise et le Journal |
| Clé rejetée / lecture refusée | alerte en tête d'Accueil, action « Corriger » vers Réglages |
| Cash épuisé / pleinement exposé (« bag vide ») | alerte sur l'Accueil, autonomie à 0 |
| Exécution réelle coupée | bandeau « Simulation » permanent, aucun ordre envoyé |
| Aucun actif | onboarding |

## 8. Admin (pages séparées)

Texte seul, sans maquette. Réservé aux administrateurs ; bandeau permanent d'état d'exécution conservé.

| Page | Contenu |
|---|---|
| Exploitation | console d'exécution réelle (kill switch, plans, ordres, Fee Test, testeur d'ordre), journal global des exécutions (filtres : période, actif, lien, wallet, statut, raison) |
| Données | `asset_provider` (exchange, paire, priorité, `enabled`, `maxHorizonDays`, lignes créées à la volée, source réelle des candles et substitution), catalogue d'actifs, routes |
| Stratégies | stratégies et presets système |
| Utilisateurs | comptes, credentials, liens (bindings) |
| Paramètres globaux | plafonds par défaut, bornes de modulation |
| Santé | état des services (idée « Doctor ») |

À trancher avant de construire l'écran Données : l'`AssetInitializer` resynchronise `providerSymbol` / `priority` / `maxHorizonDays` à chaque démarrage et écraserait une édition GUI (seul `enabled` est préservé).

## 9. Points ouverts

- Contenu exact des modes Simple et Avancé (§5).
- Lot restant : états limites dessinés sur la fiche, pages admin PC.
- Mode clair de la charte : après gel du sombre ([`../graphique/charte-graphique.md`](../graphique/charte-graphique.md)).
