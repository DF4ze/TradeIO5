---
statut: courant
modifié_le: 2026-11-11
empreinte: 690f889930
---
# Coding rules

Règles de développement pour ce projet, à respecter par tout contributeur (humain ou agent).

## Principes généraux

- **Ne pas coder ce dont on n'a pas besoin.** Pas de généralisation anticipée (config DB, multi-actif,
  abstraction) tant que le besoin concret n'est pas là. Exemple : pas de table de config pour les
  paramètres d'indicateur avant d'avoir plus d'un actif à calibrer.
- **Tester le plus simple au début pour confirmer les bases.** Valider la logique/les hypothèses sur
  un périmètre réduit (un seul actif, un prototype léger) avant d'investir dans l'intégration complète.
- **Un WARN de données manquantes/incomplètes = un appel réseau vers un Provider externe vient
  d'être déclenché.** Si ce WARN se répète en boucle (même trou, même plage, sur de nombreux appels),
  c'est le signe qu'on resollicite le Provider pour la même chose de façon répétée — donc un risque
  réel d'atteindre son rate limit. Un rate limit atteint peut déclencher une indisponibilité (429,
  ban IP temporaire, réponses vides) qui peut devenir bloquante en **production**, pas seulement
  gênante en bench/dev. Ne jamais traiter ce genre de warning répété comme "du bruit de logs" sans
  se poser la question du volume de requêtes réseau induit : dès qu'un pattern "boucle + Provider
  externe" apparaît, mitiger (mémoïsation, cache, throttling) même si l'impact fonctionnel immédiat
  est mineur. Cf. `CachingMarketDataApiClient` (mémoïsation en mémoire des trous H1 définitifs,
  2026-09-13) et `MacroMarketOpinion` (rate-limit Twelve Data sur DXY, 17/08) pour deux précédents
  concrets sur ce projet.
- **Utiliser Lombok**
- **Loguer** Mettre en INFO les points clés comme les changements d'état, mettre en DEBUG les valeurs clés.
- **Utiliser getFirst()/getLast() sur les List/Collection**, ne pas faire de .get(0) ou autre pour récupérer ces valeurs.
- **Rester dans les clous fiscaux (France)** : aucun chemin d'ordre ni fonctionnalité ne doit créer un événement taxable (pas de nœud fiat, cf. `ExecutionDefaults.FIAT_CURRENCIES`). Ne le signaler au propriétaire que si une demande fait sortir des clous.
- Collections : `isEmpty()` plutôt que `size()==0` ; `getFirst()`/`getLast()` plutôt que `get(0)`/`get(size-1)` (prod ET tests).
- JUnit : `assertTrue(x)`/`assertFalse(x)` directs, pas `assertEquals(true,x)` ni `assertTrue(!x)`.
- Pas de variable locale redondante (retour direct) ni d'initializer écrasé juste après.
- **Schéma DB** : toute modification d'entité (table, colonne, contrainte, valeur d'enum) = un nouveau `src/main/resources/db/migration/V<n>__description.sql`, idempotent ; une migration appliquée n'est jamais modifiée ; ne rien supprimer que le code lit encore. Pas d'`ALTER` manuel (cf. [`operations/flyway.md`](operations/flyway.md)).
- Constante métier dupliquée entre classes interdite : source unique dès la 1re duplication.
- Conception : poser les briques avant de demander un choix fermé ; si un point de design est ouvert, demander à Clem avant de rédiger un prompt.

<!-- Ajouter ici les futures règles, au fil des décisions prises avec Clem. -->
