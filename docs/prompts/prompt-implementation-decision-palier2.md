# Prompt d'implémentation — Décision, Palier 2 (socle multi-utilisateur)

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le
contexte de la conversation de conception. Il couvre le **Palier 2** défini avec Clem le 2026-08-10 :
`docs/etudes/etude-mecanique-decision-dca-intelligent.md` §11 (5 briques), affiné le 2026-08-11.
**Prérequis** : le Palier 1 (`docs/prompts/prompt-implementation-decision-palier1.md` — 3 TODO
critiques de `DecisionEngine`/`DefaultScenarioEngine`/`DefaultMarketScenario`) doit être mergé avant
de commencer celui-ci.

**Ce que ce lot n'est PAS** (à ne pas faire ici, même si la tentation existe en touchant ce code) :
- Pas la formule de sizing réelle (curseur → montant) — §4 de l'étude, volontairement non tranché.
- Pas la taxonomie riche des rôles de wallet — §5 de l'étude, chantier à part entière. Ce lot ajoute
  uniquement la **capacité de référencer** un wallet depuis une `Decision`, pas un système de rôles.
- Pas le branchement Spring de `DecisionEngine`/`DefaultScenarioEngine` dans l'application qui tourne
  (§12 point 1), pas la persistance/rehydratation de l'état vivant (§12 point 2). Ces classes restent
  des POJO testés isolément après ce lot, comme après le Palier 1.
- Pas de correctif "cache cross-utilisateur" sur `BalanceCacheManager` — fausse alerte de l'étude
  initiale, corrigée le 2026-08-11 (les appelants passent déjà une clé par credential). Un nettoyage
  mineur optionnel est proposé en étape 3, pas un correctif de sécurité.

Avant de commencer, lire dans l'ordre :
1. `docs/etudes/etude-mecanique-decision-dca-intelligent.md` — §6 (multi-utilisateur), §11 (briques),
   §12 (analyse critique, notamment points 1-2 pour le contexte de ce qui reste volontairement hors
   scope).
2. `model/dto/tree/scenario/ScenarioOwner.java` — l'interface scellée `SystemOwner`/`UserOwner`
   actuelle, `isVisible(...)`.
3. `security/model/User.java`, `security/repository/UserRepository.java` — l'entité utilisateur réelle
   (JPA, `Long id`) à réconcilier avec `ScenarioOwner.UserOwner(String userId)`.
4. `security/service/IAuthenticationFacade.java` et son impl `AuthenticationFacade.java` — pattern
   déjà en place pour résoudre l'utilisateur connecté (`getConnectedUser()`) dans un controller.
5. `controller/AssetOverviewController.java` + `service/agregation/AssetOverviewService.java` +
   `service/WalletService.java` (méthode `getWalletsForCurrentUser()`) + `repository/
   WalletRepository.java` (`findByUserAndEnabledTrue(User)`) — **patron à réutiliser au maximum pour
   l'étape 3** : ce service fait déjà, pour un autre besoin (overview de portefeuille), exactement
   l'agrégation multi-wallet par utilisateur dont `WalletSnapshotService` a besoin. Ne pas dupliquer
   cette logique de zéro.
6. `model/dto/tree/opinion/WalletSnapshot.java`, `UserProfile.java`, `OpinionContext.java` — les DTO
   cibles à peupler réellement.
7. `model/entity/currency/Wallet.java` — patron d'entité avec FK vers `User` (`@ManyToOne` +
   `@JoinColumn`, `@Table` + `@UniqueConstraint`, Lombok `@Data @Builder @NoArgsConstructor
   @AllArgsConstructor`) à reproduire pour la nouvelle entité de l'étape 2.
8. `model/dto/tree/decision/ActionStep.java`, `DecisionCandidate.java` — les records à étendre en
   étape 4.
9. Tests à prendre comme patron : `service/tree/scenario/DefaultScenarioEngineUnitTest.java` (accès
   package-private, `FixedDomainClock`, `EventBus`+`InMemoryEventStore` réels),
   `service/tree/decision/DecisionEngineTest.java` (créé au Palier 1 — vérifier qu'il existe et
   suivre son patron).
10. `src/main/resources/application-profile.properties.template` (`spring.jpa.hibernate.ddl-auto=
    update`) — pas de Flyway/Liquibase, schéma généré automatiquement, ne pas écrire de script SQL.

Ne rien modifier en dehors de ce qui est listé ci-dessous.

---

## Étape 1 — Unifier l'identité utilisateur (`ScenarioOwner` ↔ `User.id`)

**Contexte** : `ScenarioOwner.UserOwner(String userId)` existe et fonctionne (isolation testée dans
`DefaultScenarioEngineUnitTest#shouldNotExposeOtherUserScenario`), mais avec des identifiants de test
arbitraires ("user1"/"user2"), jamais dérivés d'un vrai `User.id`. Aucun point du code ne fait
aujourd'hui la conversion `User` → `ScenarioOwner`. Objectif : établir **un seul point de conversion
canonique**, pas une convention informelle que chaque futur appelant réinventerait à sa façon.

**À faire** :

1. Ajouter une méthode factory statique sur `ScenarioOwner` (dans l'interface scellée elle-même,
   à côté de `fromString(...)`/`user(...)` déjà présents) :
   ```java
   static ScenarioOwner of(fr.ses10doigts.tradeIO5.security.model.User user) {
       if (user == null || user.getId() == null) {
           throw new IllegalArgumentException("User must be persisted (non-null id) to become a ScenarioOwner");
       }
       return user(String.valueOf(user.getId()));
   }
   ```
   Attention au sens de dépendance : `ScenarioOwner` est dans `model.dto.tree.scenario`, `User` dans
   `security.model` — vérifier qu'aucun cycle de package ou contrainte d'architecture existante
   (module-info, ArchUnit, etc. — grep avant de supposer qu'il n'y en a pas) n'interdit cet import ;
   si c'est gênant architecturalement, poser la conversion dans une classe utilitaire séparée (ex.
   `security/service/ScenarioOwnerResolver.java`) plutôt que dans `ScenarioOwner` lui-même, et le
   signaler dans le rapport final.
2. Ajouter le sens inverse, utilisé par tout futur code ayant besoin de recharger le `User` réel
   depuis un `ScenarioOwner.UserOwner` (ex. `UserRepository`) :
   ```java
   // dans UserOwner ou en méthode statique utilitaire à côté
   default Optional<Long> asUserId() {
       // retourne Optional.empty() pour un SystemOwner, Optional.of(Long.valueOf(userId)) pour un UserOwner
   }
   ```
   (squelette indicatif — adapter la forme exacte selon ce qui s'intègre le mieux dans l'interface
   scellée existante ; garder le principe : un seul endroit qui sait faire cette conversion).

**Tests attendus** (nouveau fichier, ex. `model/dto/tree/scenario/ScenarioOwnerUserTest.java`, ou
classe de test existante si `ScenarioOwnerTest` existe déjà — vérifier avant de créer) :
- `ScenarioOwner.of(user)` avec un `User` ayant `id=42L` produit un `UserOwner` dont `getId()` vaut
  `"42"`.
- `ScenarioOwner.of(null)` ou un `User` avec `id=null` lève `IllegalArgumentException`.
- Le round-trip `asUserId()` sur le résultat de `of(user)` retourne bien `Optional.of(42L)`.

**Test d'isolation avec de vrais utilisateurs persistés** (`@DataJpaTest` ou équivalent — s'inspirer
de `AssetProviderRepositoryTest` du Palier fallback pour la configuration H2 de test, premier test de
ce type sur `User`/`ScenarioOwner` combinés) :
- Persister deux `User` réels (`UserRepository`), dériver leurs `ScenarioOwner` via `of(...)`,
  reproduire un test équivalent à `shouldNotExposeOtherUserScenario` (Palier existant) mais avec ces
  deux `ScenarioOwner` réels plutôt que des chaînes arbitraires — objectif : prouver que la chaîne
  identité JPA → `ScenarioOwner` → isolation `DecisionEngine`/`ScenarioEngine` tient bout en bout, pas
  seulement au niveau de chaînes de test inventées.

---

## Étape 2 — Persister un curseur de risque par utilisateur (structure seule)

**Contexte** : `RiskProfile` (enum LOW/MEDIUM/HIGH) n'est stocké nulle part côté utilisateur
aujourd'hui. L'étude §4 a déjà tranché avec Clem : le pilotage cible est un **curseur continu 0-10**
(pas l'enum), mais uniquement la **structure de persistance et l'API de lecture/écriture** sont dans
le scope de ce lot — pas le calcul de sizing qui le consommera plus tard (§4/§7, non tranchés).

**À faire** :

1. Nouvelle entité `model/entity/user/UserTradingSettings.java` (package à ajuster selon la
   convention du projet pour des entités liées à `User` hors `security` — regarder s'il existe déjà
   un package `model.entity.user` ou équivalent avant d'en créer un nouveau), patron `Wallet.java` :
   ```java
   @Entity
   @Table(name = "user_trading_settings",
           uniqueConstraints = @UniqueConstraint(name = "uk_trading_settings_user", columnNames = "user_id"))
   @Data @Builder @NoArgsConstructor @AllArgsConstructor
   public class UserTradingSettings {
       @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
       private Long id;

       @OneToOne(optional = false)
       @JoinColumn(name = "user_id", nullable = false, unique = true)
       private User user;

       /** Curseur de risque continu, 0 = conservateur, 10 = super agressif. Cf. étude §4. */
       @Column(nullable = false)
       private int riskCursor;
   }
   ```
2. `repository/UserTradingSettingsRepository.java` : `extends JpaRepository<UserTradingSettings,
   Long>`, méthode dérivée `Optional<UserTradingSettings> findByUser(User user)`.
3. `service/UserTradingSettingsService.java` :
   - `int getRiskCursor(User user)` : retourne la valeur persistée, ou une valeur par défaut neutre
     (`5`, à documenter comme choix arbitraire "milieu de l'échelle", pas une recommandation produit)
     si aucune ligne n'existe encore pour cet utilisateur — ne pas créer de ligne implicitement à la
     lecture, seulement à l'écriture.
   - `void setRiskCursor(User user, int riskCursor)` : valide `0 <= riskCursor <= 10` (exception
     dédiée ou `IllegalArgumentException` — vérifier s'il existe déjà une exception de validation
     générique dans le projet avant d'en créer une nouvelle), puis upsert (créer si absent, sinon
     mettre à jour la ligne existante trouvée via `findByUser`).
4. `controller/UserTradingSettingsController.java`, patron exact d'`AssetOverviewController.java`
   (`@RestController`, `@RequestMapping("/api/user/risk-cursor")`, `@PreAuthorize("isAuthenticated()")`,
   résolution de l'utilisateur courant via `IAuthenticationFacade.getConnectedUser()` injecté) :
   - `GET` : retourne le curseur actuel (`{"riskCursor": 5}` ou type de retour simple `int`/DTO minimal
     — vérifier le style de retour des autres controllers du projet, ex. `MainController`, avant de
     choisir).
   - `PUT` (ou `POST`, à aligner sur la convention déjà utilisée ailleurs dans le projet pour une
     mise à jour de ressource unique) avec un body/param `riskCursor` : appelle `setRiskCursor(...)`.

**Tests attendus** :
- `UserTradingSettingsRepositoryTest` (`@DataJpaTest`, patron `AssetProviderRepositoryTest`) :
  persister un `User` + son `UserTradingSettings`, vérifier `findByUser(...)` ; vérifier que la
  contrainte unique `user_id` est bien appliquée (deux settings pour le même user → violation).
- `UserTradingSettingsServiceTest` : `getRiskCursor` sur un utilisateur sans ligne persistée retourne
  la valeur par défaut documentée ; `setRiskCursor` avec une valeur hors `[0,10]` lève l'exception
  attendue ; `setRiskCursor` deux fois de suite sur le même utilisateur met à jour la même ligne
  (pas de doublon — vérifier via le repository mocké ou un test d'intégration léger).
- Test de contrôleur (`@WebMvcTest` ou équivalent au style déjà utilisé dans le projet pour les autres
  controllers — vérifier s'il en existe un exemple avant d'inventer un patron).

---

## Étape 3 — `WalletSnapshotService` réel

**Contexte** : `WalletSnapshot.builder().build()` est construit vide partout (`TreeAnalysisFacade`).
`AssetOverviewService` fait déjà, pour un autre besoin, l'agrégation multi-wallet par utilisateur
(`WalletService.getWalletsForCurrentUser()` → boucle `ProviderApiService.getAllBalances(wallet)` +
`getMarketPrice(...)`) — **réutiliser ce chemin, pas le redupliquer**.

**À faire** :

1. `service/WalletService.java` : vérifier s'il existe déjà une méthode acceptant un `User` explicite
   (pas seulement `getWalletsForCurrentUser()`, qui dépend implicitement du contexte de sécurité
   HTTP). Si absente, ajouter `List<Wallet> getWalletsForUser(User user)` en s'appuyant directement
   sur `WalletRepository.findByUserAndEnabledTrue(user)` — nécessaire pour que `WalletSnapshotService`
   soit appelable **hors contexte de requête HTTP** (ex. futur scheduler, tests), pas uniquement
   depuis un controller authentifié.
2. Nouveau `service/tree/opinion/WalletSnapshotService.java` (ou package `service/wallet` si plus
   cohérent avec l'existant — vérifier la convention avant de choisir) :
   ```java
   public WalletSnapshot buildSnapshot(User user, String quoteCurrency) {
       List<Wallet> wallets = walletService.getWalletsForUser(user);
       Map<String, Double> balances = new HashMap<>(); // agrégées, sommées si un même asset existe sur plusieurs wallets
       double totalValue = 0;
       // pour chaque wallet : providerApiService.getAllBalances(wallet), fusionner dans balances,
       // valoriser via providerApiService.getMarketPrice(wallet, asset, quoteCurrency) comme le fait
       // déjà AssetOverviewService (même traitement spécial "USDC/EUR ~ 1", à reprendre à l'identique
       // pour ne pas diverger de la valorisation déjà utilisée ailleurs dans l'app)
       return WalletSnapshot.builder()
               .balances(balances)
               .openPositions(Map.of()) // hors scope ici : nécessite l'historique de transactions, cf. TransactionService — pas dans ce lot
               .totalValue(totalValue)
               .investedValue(0) // idem : nécessite TransactionService.getTotalBuyValue(...), pas dans ce lot
               .build();
   }
   ```
   `openPositions`/`investedValue` restent volontairement à leur valeur par défaut dans ce lot — les
   brancher demanderait de reproduire la logique `TransactionService` d'`AssetOverviewService`, hors
   scope du socle multi-user (le sizing n'existe pas encore pour en avoir besoin). Documenter ce choix
   dans le javadoc de la méthode, pas le laisser silencieux.
3. **Nettoyage optionnel, bas risque** (à faire seulement si le temps le permet, ne bloque rien) :
   dans `BalanceCacheManager`, le paramètre nommé `asset` est en réalité une clé composite
   `credential.getApiKey() + ":" + baseUrl` construite par chaque appelant
   (`BinanceApiClient`/`KrakenApiClient`). Remplacer la signature par `getBalances(BalanceProvider
   provider, ApiCredential credential)`, dérivant la clé en interne à partir de `credential.getId()`
   (identifiant stable, plutôt qu'une concaténation de chaînes reconstruite à chaque appel) ; mettre à
   jour les deux appelants en conséquence. Si ce nettoyage est fait, l'accompagner d'un test vérifiant
   que deux `ApiCredential` différents ne partagent pas d'entrée de cache.

**Tests attendus** (`WalletSnapshotServiceTest`, `ProviderApiService`/`WalletService` mockés — patron
Mockito déjà utilisé ailleurs dans le projet, ex. `CachingMarketDataApiClientTest`) :
- Un utilisateur avec 2 wallets sur des exchanges différents, chacun avec des soldes différents pour
  un même asset (ex. 0.1 BTC sur Binance, 0.05 BTC sur Kraken) : `buildSnapshot(...)` retourne bien
  `balances.get("BTC") == 0.15` (agrégation correcte, pas juste le dernier wallet écrasant les
  précédents — piège classique d'agrégation par `Map.put` au lieu de `merge`/somme).
  `totalValue` cohérent avec la somme des valorisations.
- Un utilisateur sans aucun wallet : `buildSnapshot(...)` retourne un `WalletSnapshot` avec
  `balances` vide et `totalValue == 0`, pas d'exception.

---

## Étape 4 — `ActionStep` peut référencer un wallet cible (champ nullable, pas encore peuplé)

**Contexte** : aucun `ActionStep`/`DecisionCandidate` ne sait aujourd'hui "sur quel wallet agir".
Ce lot ajoute uniquement la **capacité de porter cette information** dans le modèle de données —
personne ne sait encore *choisir* le bon wallet (ça dépendra du futur système de rôles, §5, et du
Sizing, §4/§7, tous deux hors scope) : le champ reste `null` partout où il est construit dans ce lot,
et c'est un résultat attendu, pas un oubli.

**À faire** :

1. `model/dto/tree/decision/ActionStep.java` : ajouter un champ `Long walletId` (nullable) au record :
   ```java
   public record ActionStep(
           String stepId,
           ExecutionAction executionAction,
           BigDecimal quantity,
           Long walletId // nullable : pas encore résolu tant que le Sizing (étude §4/§7) n'existe pas
   ) {}
   ```
2. `model/dto/tree/decision/DecisionCandidate.java` : même ajout, `Long walletId` nullable, pour que
   l'information puisse un jour circuler depuis l'`ActionIntent`/`Scenario` jusqu'à l'`ActionStep`
   sans nouveau changement de signature à ce moment-là.
3. Mettre à jour tous les points de construction existants (`DecisionEngine.createDecision(...)`,
   `mapToCandidate(...)`, et tout `new ActionStep(...)`/`new DecisionCandidate(...)` dans les tests du
   Palier 1) pour passer `null` explicitement — **ne pas** essayer de deviner ou déduire un wallet ici.

**Tests attendus** : mettre à jour les tests existants impactés par le changement de signature des
deux records (compilation cassée sinon — `DecisionTest.java`, `DecisionEngineTest.java` du Palier 1,
et tout autre test construisant ces records). Pas de nouveau test métier nécessaire pour cette étape
au-delà de la non-régression : c'est un changement de forme, pas de comportement.

---

## Étape 5 — Test d'intégration bout-en-bout minimal, 2 utilisateurs

**Contexte** : combine les étapes 1 et 3 pour prouver que la chaîne tient réellement de bout en bout
avec des données par utilisateur distinctes, pas seulement des `ScenarioOwner` isolés comme en
étape 1.

**À faire** : un seul nouveau test d'intégration (ex.
`service/tree/decision/MultiUserIsolationIntegrationTest.java`), construit sur le patron de
`ScenarioEngineIntegrationTest.java` existant :
1. Persister deux `User` réels avec chacun un wallet (mocker `ProviderApiService` pour retourner des
   soldes distincts par wallet, pas besoin d'un vrai appel exchange).
2. Construire les deux `WalletSnapshot` via `WalletSnapshotService` (étape 3) et vérifier qu'ils sont
   bien différents l'un de l'autre (pas de valeur partagée par accident).
3. Instancier `DefaultScenarioEngine`/`DecisionEngine` pour chaque `ScenarioOwner` dérivé (étape 1),
   faire vivre un scénario pour l'utilisateur A jusqu'à `VALIDATED` (réutiliser le patron
   `opResult(...)`/`context(...)` de `DefaultMarketScenarioTest`), et vérifier qu'aucune `Decision`
   n'est jamais visible/comptée côté utilisateur B (`getActiveScenarios(ownerB, ...)` reste vide pour
   le scénario de A).

Ce test est délibérément le dernier de ce lot : s'il échoue, ça signale une régression d'isolation
introduite par les étapes précédentes, pas un bug dans l'étape 5 elle-même.

---

## À la fin : lancer les tests via la Gateway

Compiler et exécuter la suite de tests complète via l'opération CI/CD `test:tradeio-5` du gateway SSH
(`mcp__plugin_ssh-gateway_ssh-gateway__executeOperation`). Ne pas lancer `mvn` directement en sandbox
(pas de Maven/réseau disponible).

Porter une attention particulière au premier démarrage après l'ajout de `UserTradingSettings` :
si `ddl-auto=update` échoue à créer la table (conflit de nom, contrainte déjà existante), le signaler
explicitement — ce n'est pas un échec de test au sens strict mais bloquerait toute la suite si le
contexte Spring ne démarre pas.

Rapporter : résultat global, nombre de tests exécutés (comparer à la baseline post-Palier 1), détail
de tout échec, et signaler explicitement tout écart pris par rapport à ce prompt (ex. package choisi
différent pour `UserTradingSettings`, nettoyage `BalanceCacheManager` non fait faute de temps, etc.)
— pas besoin de tout suivre à la lettre, mais le rapport doit permettre de savoir précisément ce qui
a été fait vs. laissé de côté.

Une fois ce lot mergé, mettre à jour l'étude (§11, cocher les 5 briques) avant de discuter avec Clem
du prochain palier (probablement §5 rôles de wallet, maintenant que `ActionStep` peut les référencer,
ou §4 sizing, maintenant que `WalletSnapshot`/curseur de risque sont réels).
