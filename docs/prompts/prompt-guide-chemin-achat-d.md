# Prompt D — Session guidée : clé Trade OKX, déverrouillage LIVE et 1ᵉʳ ordre réel de montant minimal

> À coller dans une nouvelle discussion (Claude Code / Cowork sur TradeIO-5) **avec Clem présente du début à la fin**. Étape (d) de `docs/prompts/prompt-roadmap-chemin-achat-execution.md`. Ce n'est **pas** un prompt d'implémentation : c'est un déroulé pas à pas où **chaque action réelle (création de clé, déverrouillage, ordre) attend un « go » explicite de Clem**. Prérequis : étapes a, b, c (C1 + C2) livrées, mergées et validées ; vérification réelle du wallet OKX (clé Read) faite. Si l'un manque, **stopper et le dire**.

## 0. Règles de la session
- Réponses en français, courtes, techniques ; **une étape à la fois**, attendre la confirmation de Clem avant la suivante.
- **Aucun ordre** sans « go » explicite de Clem sur le montant, l'instrument et le sens, formulés à voix haute par Claude juste avant (« j'envoie : vendre X USDC sur USDC-USDT, limit IOC à Y, plafond Z — go ? »).
- **Ne jamais demander ni afficher** le secret, la passphrase ou la clé maître : Clem les saisit elle-même (page/API admin, variable d'environnement VPS). Ne rien écrire de secret dans un fichier, un log, un commit ni un message.
- Au moindre écart (erreur inattendue, montant différent, étape `UNKNOWN`, ordre non soldé) : **couper l'exécution (kill switch), réconcilier avant toute autre action, expliquer à Clem**.
- Tout reste réversible tant qu'aucun ordre n'est parti : le kill switch + mode `DRY_RUN` suffisent à revenir en arrière.

## 1. Pré-vol (lecture seule, avant de toucher à OKX)
1. Lire `docs/README.md`, l'étude `etudes/etude-chemin-achat-execution.md`, `architecture/07-security.md`, la page d'architecture de l'exécution (c), la roadmap.
2. Vérifier avec Clem et par le code/les tests : build vert, tests d'architecture verts (`ConnectorNoOrderMethodTest`, `RainbowLiveNoExchangeDependencyTest`, test du port d'ordres), mode actuel `DRY_RUN`, `live-unlocked=false`, cron d'exécution désactivé, kill switch **posé** (true) avant toute chose.
3. Vérifier côté VPS (via le ssh-gateway/`getAllServicesStatus` si disponible, sinon Clem) : version déployée = version testée, IP publique du VPS (à whitelister), variable d'environnement de la clé maître **absente ou à créer** par Clem, sauvegarde de la base récente.
4. Relire avec Clem un **plan dry-run réel** de la veille (étape b) : montants, chemin, coût en %, niveau Fee Test (aucune surprise attendue). Confirmer que le coût du pont `USDC→USDT` est bien celui lu via `trade-fee` (maker/taker et signe).
5. Décider ensemble le **montant du test** : pont `USDC→USDT` au **minimum accepté par OKX** (`minSz` de `USDC-USDT` = 1 USDC d'après le catalogue ; vérifier la valeur courante dans `exchange_instrument`) — viser quelques USDC, jamais plus que ce que Clem accepte de perdre en frais/slippage (ordre de grandeur attendu : quelques centimes). Noter montant, plafond de prix, résultat attendu.

## 2. Création guidée de la clé Trade OKX (Clem le fait dans OKX, Claude guide)
Dire à Clem, étape par étape, et attendre sa confirmation à chaque point :
1. Se connecter à OKX (compte EEA, domaine déjà utilisé pour la clé Read), activer la 2FA si ce n'est pas fait.
2. Créer une **nouvelle** clé API distincte de la clé Read : nom explicite (ex. `tradeio-trade`), **permission « Trade » uniquement**, **« Withdraw » désactivé**, passphrase **nouvelle et forte** (conservée par Clem dans son gestionnaire de mots de passe).
3. **Allowlist d'IP = IP publique du VPS uniquement.** Une clé Trade sans IP allowlistée est refusée par Claude (stop, on corrige d'abord).
4. Ne pas copier la clé nulle part sauf dans le champ prévu. Vérifier dans OKX qu'aucune permission supplémentaire n'est cochée (relire l'écran de récap).
5. Optionnel mais recommandé : limiter le compte de trading au strict nécessaire (solde de test faible ; le gros du capital hors du compte Trading si possible — décision de Clem).

## 3. Mise en place sur le VPS (secrets saisis par Clem)
1. Clem définit la **clé maître de chiffrement** dans l'environnement du service (variable prévue en C ; jamais dans un fichier versionné, jamais en base) puis redémarre le service ; Claude vérifie seulement que le service démarre et que le chiffrement TRADE est « prêt » (pas de valeur affichée).
2. Clem enregistre la credential **TRADE** via l'API admin prévue en C (clé, secret, passphrase saisis par elle ; `scope=TRADE`, rattachée à son `User`, wallet OKX). Claude vérifie : credential créée, **chiffrée en base** (contrôle SQL en lecture sur la colonne : valeur illisible), `scope=TRADE`, aucune fuite dans les logs récents.
3. Test **non destructif** de la clé : appel authentifié lecture seule (solde ou `trade-fee`) avec la credential TRADE, pour confirmer signature, passphrase et IP ; en cas de rejet 501xx, diagnostiquer (IP, passphrase, permission) sans rien envoyer d'autre.

## 4. Armement progressif (aucune étape ne passe sans « go »)
1. Garder le kill switch **actif**. Positionner le binding concerné : `executionEnabled=false` pour l'instant.
2. Poser `tradeio.execution.live-unlocked=true` et `tradeio.execution.mode=LIVE` (propriétés prod, redémarrage). Vérifier : le service démarre, `OkxSpotOrderClient` est présent, **mais** kill switch actif ⇒ aucune exécution possible. Test de fumée : lancer l'exécuteur sur un plan de test et constater le refus « kill switch ».
3. Plafonds posés **très bas** pour le test (par ordre = quelques USDC, par jour idem, au minimum du plafond absolu) ; vérifier qu'un ordre supérieur est refusé.
4. L'admin (Clem) **arme** le binding (`firstLiveApprovedAt`), puis l'utilisateur **confirme** le premier ordre réel (`firstLiveConfirmedAt`) : les deux traces d'audit apparaissent.
5. Activer `executionEnabled=true` sur le seul binding de test.

## 5. Premier ordre réel : pont USDC→USDT au montant minimal
1. Construire un **plan de test** manuel limité à une seule étape : vente `USDC-USDT`, `sz` minimal, `limit IOC` avec prix plafond issu du devis ± tolérance (0,05-0,1 %), `clOrdId` déterministe. Afficher à Clem : instrument, sens, `sz`, prix plafond, coût attendu en %, solde avant.
2. **Go explicite de Clem** → lever le kill switch → déclencher l'exécution (endpoint admin sur ce plan uniquement) → **remettre le kill switch aussitôt après** l'ordre.
3. Réconciliation immédiate : état de l'ordre par `clOrdId`, fills (`fillPx`, `fillSz`, `fee`, `feeCcy`), solde après. Comparer au plan : quantité reçue, frais prélevés (devise des frais ? net ou brut ?), slippage réel vs mid du devis, écart de coût vs Fee Test.
4. Vérifier dans OKX (historique d'ordres, relevé de trades) que ce qui est enregistré en base correspond **exactement** (ids, prix, quantités, frais). Vérifier `Transaction` créée sans doublon et l'audit append-only complet.
5. Rejouer la même exécution (même plan, même `clOrdId`) pour **prouver l'idempotence** : attendu = lecture de l'état, aucun nouvel ordre (le contrôler dans OKX).
6. Documenter les constats réels (frais, devise de prélèvement, minimums exacts, comportement IOC) pour corriger l'étude et le code si besoin.

## 6. Critères de réussite
- Un seul ordre réel de montant minimal, exécuté ou refusé proprement, **réconcilié** avec OKX à l'unité près.
- Aucun secret en clair (base, logs, repo) ; credential TRADE chiffrée ; permission Trade seule, Withdraw désactivé, IP VPS allowlistée.
- Idempotence prouvée (pas de double ordre), kill switch efficace, plafonds effectifs, audit complet.
- Retour à l'état sûr à la fin : kill switch actif, `executionEnabled=false`, cron d'exécution **désactivé** (décision de Clem : exécution **manuelle** quelques jours avant toute activation automatique).

## 7. Après le test (mise à jour des docs, sans historique)
`architecture/07-security.md` (clé Trade réelle, procédure), la page d'architecture de l'exécution (constats réels : frais, minimums, devise de prélèvement), `known-gaps/rainbow-dca-chantiers-ouverts.md` §5c, `known-gaps/decision-to-order-gap.md`, l'étude (corriger ce qui était supposé), la roadmap (statut d). Ne pas laisser de secret dans les docs.

## 8. Suite (hors session, à décider avec Clem)
Exécution manuelle quelques jours (endpoint admin) sur le plan quotidien, puis relecture ensemble des écarts plan/réel ; ensuite seulement : cron d'exécution, extension à PAXG (pont + achat `PAXG-USDT` minimal), puis BTC/ETH ; relever progressivement les plafonds ; envisager `post_only` pour viser les frais maker.
