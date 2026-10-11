# Prompt — Analyse : UX Admin d'exploitation (exécution réelle multi-exchange), pilotage du test Kraken

> À coller tel quel dans une nouvelle discussion (Claude Code / Cowork sur TradeIO-5). **Mission = ANALYSE + maquettes + proposition + questions, aucun code ni aucun ordre tant que Clem n'a pas validé.** Prérequis : lot B chemin d'achat a–e livré, 1er ordre réel OKX passé (`BTC-USDC`).

## 0. Démarrage
1. Lis `docs/README.md`, puis `architecture/07-security.md`, `08-rainbow-bench-grandeur-nature.md`, `10-execution-plan.md`, `10-execution-reelle.md`, `api/rest-endpoints.md`, `known-gaps/rainbow-dca-chantiers-ouverts.md` (§5, §5b, §5c), `etudes/etude-chemin-achat-execution.md`, `docs/CODING_RULES.md`, `glossary.md`.
2. Lis le code : `controller/MainController` (`/admin`, `/user`), `templates/adminPage.html` (quasi vide), `templates/userPage.html`, `fragments/*`, `static/**/rainbow-live.js` (patron à réutiliser : `textContent` uniquement), `controller/ExecutionAdminController`, `Rainbow*Controller` (`/api/rainbow-live/**`), `service/execution/**`, `ApiCredentialInitializer`, `ApiCredential`, `ExecutionGuard`, la config de sécurité (rôles ADMIN / owner).
3. Réponds en français, court et technique (flèches/symboles bienvenus). Un point flou → **pose la question avant d'avancer** (AskUserQuestion).

## 1. Objectif
Remplacer l'exploitation « curl + endpoints + SQL » par une **page Admin d'exécution** qui permet à Clem de piloter et superviser les tests d'ordres réels, **en priorité pour introduire Kraken** (test `validate`, puis ordre réel minimal), sans laisser de secret ni de raccourci dangereux dans l'UI.

## 2. Constat (2026-10-11)
- `adminPage.html` ≈ 76 lignes, vide : rien à migrer.
- Exécution manuelle OKX aujourd'hui : kill switch engagé ⇒ `/plan` ⇒ `/bindings/{id}/arm` ⇒ confirmation propriétaire ⇒ levée kill switch ⇒ `/run?planId=` ⇒ réconciliation si `UNKNOWN` ⇒ réengagement. Credentials TRADE : `POST /api/admin/execution/credentials` (OKX seulement) ou `ApiCredentialInitializer` (properties). Bindings : API seule (pas de formulaire ni de liste).
- Kraken : connecteurs en lecture seule (`KrakenApiClient`, `KrakenBalanceReader`, `KrakenInstrumentListClient`) ; **aucun client d'ordres** ; `SpotOrderPort` n'a qu'un implémenteur OKX ; `AddOrder` Kraken offre `validate=true` (dry-run natif).
- PAXG/Kraken : seules `USD`, `EUR`, `XBT`, `ETH` en cotation ⇒ routes possibles `USDC → BTC/ETH → PAXG` (sans fiat) ou via fiat ; `PathFinder` n'accepte aujourd'hui que des nœuds intermédiaires stables.

## 3. Décisions de Clem — ne pas les remettre en question
- Le fonctionnel back prime ; **pas de charte graphique** pour l'instant : page sobre, fonctionnelle, fragments réutilisables (la charte viendra ensuite).
- Même stack que l'existant : Thymeleaf + JS vanilla, `textContent` uniquement (pas d'`innerHTML`), pattern `rainbow-live.js`.
- Les credentials sont rattachées au **User** en base, jamais dans les properties/Flyway (properties gitignorées = amorçage dev seulement) ; une **page de saisie des credentials** est indispensable.
- Garde-fous existants à ne pas affaiblir : `LIVE` verrouillé, kill switch engagé par défaut, double validation du 1er ordre, plafonds absolus, **aucun retrait/transfert API, jamais**, fiat interdit dans un chemin, Fee Test RED = warning seulement pour l'instant.
- Ordres par défaut : limit IOC plafonné. Test Kraken : paire `BTC-USDC` ou `ETH-USDC`, montant minimal, après un `validate=true`.
- Idée notée (hors périmètre immédiat, à prévoir dans le design) : **binding du chemin d'achat par actif et provider** avec affichage côte à côte des routes (ex. PAXG/Kraken : `USDC→ETH/BTC→PAXG` vs `USD→fiat→PAXG`), Fee Test, warnings liquidité et passage par le fiat. Ouvre à terme la découverte totale du portefeuille.
- Hors lot : charte graphique, pages utilisateur, modes Auto/Simple/Avancé/Expert, Stratégie Wallet, token natif d'exchange.

## 4. Ce que tu dois produire
1. Reformulation (10 lignes max) + coquilles/ambiguïtés/questions.
2. **État des lieux vérifié** : endpoints admin/owner existants (méthode, rôle, effet), ce qui manque pour chaque écran ci-dessous, config de sécurité (ADMIN vs propriétaire, CSRF, journalisation), ce que `ExecutionAdminController` expose déjà.
3. **Écrans proposés** (à challenger, ordre de priorité = ordre de livraison) :
   - **Console d'exécution** : kill switch (état/bascule), mode (lecture), procédure guidée plan → armer → confirmer → lever kill switch → exécuter → réconcilier → réengager (chaque étape = bouton + état ; réengagement proposé automatiquement en fin).
   - **Credentials** : liste par user/provider/scope (READ|TRADE), actif, dernier test, saisie/rotation (secrets jamais ré-affichés), test de validité d'une clé sans ordre.
   - **Bindings** : token → provider → wallet, `executionEnabled`, statut de tradabilité, état du 1er ordre réel, formulaire de création.
   - **Testeur d'ordre** : provider/paire/sens/montant/prix plafond ⇒ `validate` (Kraken `AddOrder validate=true` ; OKX = calcul local/démo), réponse brute + erreur lisible ; ordre réel minimal depuis le même écran avec double confirmation et plafonds.
   - **Plans & audit** : plans (statut, étapes, fills, frais, slippage, `UNKNOWN`) et flux `rainbow_live_order_event` en lecture seule.
   - **Catalogue & routes** : paires par provider (`ordermin`/`minSz`, `costmin`, tick), routes candidates par actif avec coût (Fee Test), warnings (liquidité, fiat), route liée au binding.
4. **Maquettes textuelles** (ASCII ou tableaux) de chaque écran, états vides/erreur, messages des blocages (`51155`, `NOT_TRADABLE_WITHOUT_FIAT`, `KILL_SWITCH`, `PORT_UNAVAILABLE`…).
5. **Architecture** : découpage (`AdminExecutionController` vs existant), DTO dédiés (jamais d'entité JPA), fragments Thymeleaf et JS (un fichier par onglet), règles anti-XSS, contrôle d'accès, tests (API + rendu + absence d'`innerHTML`, `ExecutionOrderArchitectureTest` inchangé dans son esprit), **impact sur la doc** (`api/rest-endpoints.md`, `architecture/10-execution-reelle.md`, `07-security.md`).
6. **Pilotage Kraken** : liste précise de ce que l'UI doit montrer pour introduire Kraken (clé Trade, droits Query Funds + Create/Modify Orders sans Withdraw, IP, résultat `validate` par paire, `ordermin`/`costmin`/`tick_size`/`lot_decimals`, frais `TradeVolume`, fee currency, réconciliation par `cl_ord_id`), et ce qui doit exister côté back en préalable (routage `SpotOrderPort` par provider, signeur Kraken HMAC-SHA512, nonce monotone par clé, clé Trade séparée de la Read, `ApiCredentialInitializer` `tradeio.kraken.trade.*`, endpoint de création non limité à OKX).
7. **Feuille de route par lots** petits et testables avec critères d'acceptation et un point avec Clem après chaque lot. Proposition de départ : (1) socle page admin + onglets + Console ; (2) Credentials + Bindings ; (3) client Kraken + routage provider (en parallèle, prompt séparé) ; (4) Testeur d'ordre (`validate` Kraken) ; (5) ordre réel minimal `BTC-USDC` Kraken ; (6) Plans & audit ; (7) `PathFinder` avec intermédiaires crypto + écran Routes (PAXG).
8. **Questions ouvertes** à poser à Clem — au minimum : l'admin = Clem seule ou plusieurs ? les écrans Credentials/Bindings sont-ils admin ou propriétaire (user) ? la page vit-elle sous `/admin` ou une nouvelle route ? réengagement automatique du kill switch après exécution ? ordre réel déclenchable depuis l'UI ou uniquement `validate` + plan ? visualisation des réponses brutes d'exchange (masquage des identifiants) ? journal d'audit des actions admin (qui a levé le kill switch) ? comportement si Kraken refuse la paire pour conformité.

## 5. Règles
- Respecter `docs/CODING_RULES.md` et le style de Clem (isEmpty()/getFirst(), constantes métier mutualisées, pas de variables redondantes, assertTrue/False directs, `DomainClock`).
- Aucune modification de code ni aucun ordre pendant l'analyse. Appels réseau limités aux endpoints **publics** ; toute lecture authentifiée avec accord de Clem.
- Livrable : `docs/etudes/etude-ux-admin-execution.md` (+ roadmap `docs/prompts/prompt-roadmap-ux-admin-execution.md` après validation) + questions posées à Clem. La doc reste sans historique (supprimer ce qui n'est plus vrai).
