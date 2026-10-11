# Prompt — Roadmap : UX Admin d'exécution (page /admin), smoke test réel, introduction Kraken

> Référence : `docs/etudes/etude-ux-admin-execution.md` (analyse + décisions §8). Un lot à la fois, point avec Clem après chacun. Respecter `docs/CODING_RULES.md` et le style de Clem. Mettre à jour la doc concernée à chaque lot (sans historique).

## Décisions figées
Admin = `ROLE_ADMIN` ; page `/admin` à onglets, Thymeleaf + JS vanilla, `textContent` uniquement, pas de charte ; propriétaire saisit ses clés, admin supervise ; kill switch réengagé automatiquement côté back après run/reconcile ; testeur = `validate` seul ; **smoke test réel** minimal par route (plan `SMOKE_TEST` via `OrderExecutor`, aucun bypass) ; échec clé ≠ refus d'ordre ≠ verrou non levé (`FAILED_GUARD`) ; **smoke test non obligatoire** ; **aucun passe-droit en test** (verrou non levé = échec) ; infos API en historique ; sécurité des endpoints (CSRF, inscription…) **reportée à l'audit** ; jamais de retrait/transfert.

## Lots (4, point avec Clem après chacun)
1. **Page Admin** : onglets + Console (procédure plan→armer→confirmer→lever→exécuter→réconcilier→réengager auto) + Credentials (propriétaire saisit, admin supervise) + Bindings (liste admin, formulaire) + `admin_action_log`. *Accept.* : procédure OKX complète sans curl ni SQL ; aucun secret en réponse ; actions journalisées ; pas d'`innerHTML`.
2. **Kraken** : client d'ordres + routage par provider + signeur/nonce monotone + `tradeio.kraken.trade.*` + catalogue `costmin` + Testeur d'ordre (`validate` Kraken, calcul local OKX, aucun envoi). *Accept.* : `validate` `BTC-USDC`/`ETH-USDC` OK avec erreurs lisibles ; exchange simulé + serveur HTTP local verts ; `ExecutionOrderArchitectureTest` vert.
3. **Smoke test réel + Plans & audit** : plan `SMOKE_TEST` via `OrderExecutor`, états `PASSED`/`FAILED_KEY`/`FAILED_ORDER`/`FAILED_GUARD`, bouton par route/wallet/user ; onglet Plans & audit (multi-user, `UNKNOWN`, infos API en historique). *Accept.* : ordre minimal OKX puis Kraken réconcilié à l'unité ; relance idempotente ; bouton masqué après `PASSED`.
4. **Routes (PAXG)** : `PathFinder` à intermédiaires crypto + Catalogue & routes + route liée au binding + smoke test par route. *Accept.* : `USDC→ETH→PAXG` chiffrée, route fiat non sélectionnable, route `FAILED_ORDER` écartée.

## Plafonds (décision Clem, 2026-10-11)
Plafonds absolus abaissés à **10 USD / ordre et 50 USD / jour** (constantes `ExecutionDefaults`) pendant toute la phase de test (aucun déroulement de stratégie pour l'instant). À prévoir dans un lot ultérieur : plafonds **configurables par l'utilisateur** (dans les bornes absolues), avec **avertissement** à l'approche des seuils (ordre et jour) dans la page et le bandeau.

## Règles
Aucun ordre réel sans accord explicite de Clem ; appels authentifiés avec accord ; tests : API, sécurité, rendu, absence d'`innerHTML`, `ExecutionOrderArchitectureTest` inchangé dans son esprit.

## Sécurité
Remarques d'audit (auth, journalisation) : `docs/securite/remarques-audit.md`. Toute nouvelle remarque rencontrée pendant un lot y est ajoutée.
