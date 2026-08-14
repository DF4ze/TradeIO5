# Prompt d'implémentation — Décision, Palier 3, Étape 5 (détection de connexion utilisateur)

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le
contexte de la conversation de conception. Il couvre l'**Étape 5** de
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md` — indépendante du reste du palier,
peut être implémentée à tout moment. Référence : `docs/etudes/etude-branchement-persistance-decision-engine.md`
§E point 5 (mention : "Nécessite un signal de connexion utilisateur (champ 'dernière connexion' mis à
jour au login) — jugé simple à ajouter, pas creusé plus avant"). Ce prompt creuse ce qui restait
non détaillé.

**Décisions prises avec Clem le 2026-08-13, avant rédaction de ce prompt** (l'étude ne précisait que
"un hook au login" sans détailler lequel — deux points d'entrée distincts existent réellement dans le
code, à ne pas confondre) :
1. **Double hook, pas un seul** : `AuthController.authenticateUserForm` (`POST /api/auth/signinForm`,
   le seul endpoint de login actif — vérifié : `/api/auth/signin` en JSON est commenté/mort dans le
   code actuel) **et** `AuthTokenFilter` (le filtre JWT qui revalide le cookie à **chaque** requête
   authentifiée). Raison : un utilisateur peut rester connecté des jours via cookie JWT sans jamais
   repasser par `signinForm` — se limiter à ce seul endpoint sous-estimerait l'activité réelle, ce qui
   compterait pour l'étape 6 (archivage sur inactivité), qui consommera ce signal.
2. **Throttle sur le hook `AuthTokenFilter`** : ne pas écrire en base à chaque requête (coût inutile,
   `OncePerRequestFilter` s'exécute sur un volume de requêtes bien plus large qu'un login) — ne mettre
   à jour `lastLogin` que si la valeur actuellement connue date de plus de **15 minutes** (valeur par
   défaut proposée, documentée et facilement ajustable via propriété — pas creusée davantage, à
   ajuster si besoin en usage réel).

**Ce que ce lot n'est PAS** : pas l'archivage sur inactivité (étape 6) — ce lot pose uniquement le
signal (champ + double hook), il ne l'exploite pas. Ne pas ajouter de requête `findByLastLoginBefore(...)`
ou de logique de purge/archivage ici : l'étape 6 ajoutera exactement ce dont elle a besoin quand son
prompt sera rédigé, pas avant (cohérent avec "petite" tel que décrit dans la roadmap).

Avant de commencer, lire dans l'ordre :
1. `security/model/User.java` — l'entité actuelle : `@Data @NoArgsConstructor @AllArgsConstructor
   @Builder`. **Point d'attention** : `@AllArgsConstructor` génère un constructeur positionnel — ajouter
   un champ change sa signature, ce qui casse tout appel positionnel existant (cf. point 3 des lectures
   ci-dessous, un seul site concerné mais réel).
2. `controller/AuthController.java` — `authenticateUserForm(...)` (`POST /signinForm`, le hook 1),
   `registerUserForm(...)` (construit un `User` via le constructeur positionnel `new User(null,
   signUpRequest.getUsername(), signUpRequest.getEmail(), encoder.encode(...), roles, true)` — **ce
   site doit être mis à jour** pour rester compilable une fois le nouveau champ ajouté).
3. `security/jwt/AuthTokenFilter.java` — le hook 2 : `doFilterInternal(...)`, résout déjà
   `UserDetailsImpl` (qui expose `getId()`) après validation du JWT. Aujourd'hui, seuls `JwtUtils` et
   `UserDetailsServiceImpl` sont injectés par `@Autowired` sur les champs (le filtre est instancié
   manuellement via `new AuthTokenFilter()` dans `WebSecurityConfig.authenticationJwtTokenFilter()`,
   mais reste géré par le conteneur Spring ensuite — l'injection par champ `@Autowired` fonctionne
   normalement sur ce genre de bean, à confirmer en compilant/testant plutôt qu'à supposer si un doute
   apparaît).
4. `security/service/impl/UserDetailsImpl.java` — `getId()` disponible, pas d'accès direct à l'entité
   `User` complète (juste id/username/email/authorities) — la mise à jour doit repasser par
   `UserRepository.findById(...)`.
5. `security/repository/UserRepository.java` — `extends JpaRepository<User, Long>`, `findById(...)`
   déjà disponible nativement.
6. `security/WebSecurityConfig.java` — `authenticationJwtTokenFilter()` : confirme qu'`AuthTokenFilter`
   est bien un bean Spring (autowiring par champ fonctionnera), et permet de vérifier si le filtre est
   scopé à certains chemins ou s'applique à toute requête (pertinent pour comprendre le volume réel de
   déclenchements que le throttle du point 2 doit absorber — à lire avant de conclure quoi que ce soit
   sur la portée, ne pas supposer).
7. `src/main/resources/application-profile.properties.template` — `spring.jpa.hibernate.ddl-auto=
   update` : nouveau champ nullable sur `User`, pas de script SQL à écrire (patron déjà établi dans ce
   projet).

Ne rien modifier en dehors de ce qui est listé ci-dessous.

---

## Étape 1 — Ajouter le champ `lastLogin` à `User`

**À faire** :
```java
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "users", ...)
public class User {
    // ... champs existants inchangés ...

    private boolean enabled;

    /**
     * Dernière connexion détectée (Palier 3, étape 5) — mise à jour à la fois au login explicite
     * (AuthController#authenticateUserForm) et, avec throttle, à chaque requête authentifiée revalidée
     * par AuthTokenFilter. Nullable : un utilisateur jamais connecté depuis l'ajout de ce champ (ou
     * créé avant) a cette valeur à null, pas une date arbitraire.
     */
    private Instant lastLogin;
}
```
Ajouter le champ **en dernière position** pour limiter la portée de la rupture du constructeur
positionnel `@AllArgsConstructor` à un seul site d'appel connu (point suivant).

**À mettre à jour en conséquence** :
`AuthController.registerUserForm(...)` : le seul appel positionnel existant à `new User(...)` (recherche
faite avant rédaction de ce prompt — un seul site trouvé, le commentaire ligne ~163 ne compte pas) :
```java
User user = new User(null, signUpRequest.getUsername(), signUpRequest.getEmail(),
        encoder.encode(signUpRequest.getPassword()), roles, true, null); // lastLogin = null à l'inscription
```

**Tests attendus** : mettre à jour tout test existant construisant un `User` via le constructeur
positionnel `@AllArgsConstructor` (rechercher `new User(` dans `src/test/java` avant de conclure qu'il
n'y en a pas — les tests de ce palier construisent `User` via `User.builder()...build()`, insensible à
l'ajout d'un champ, mais à vérifier plutôt qu'à supposer pour tout le reste de la suite).

---

## Étape 2 — Hook 1 : mise à jour au login explicite (`signinForm`)

**À faire**, dans `AuthController.authenticateUserForm(...)`, après l'authentification réussie (dans le
bloc `try`, après `SecurityContextHolder.getContext().setAuthentication(authentication)`) :
```java
UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();

userRepository.findById(userDetails.getId()).ifPresent(u -> {
    u.setLastLogin(Instant.now());
    userRepository.save(u);
});

Cookie jwtCookie = jwtUtils.generateJwtCookie(userDetails);
```
(`userRepository` est déjà injecté dans `AuthController` — pas de nouvelle dépendance à ajouter ici,
contrairement à l'étape 3 ci-dessous pour `AuthTokenFilter`.)

**Tests attendus** : test de contrôleur existant pour `signinForm` si un patron `@WebMvcTest`/équivalent
existe déjà dans ce projet pour `AuthController` (vérifier avant d'en inventer un nouveau style) —
vérifier qu'après un login réussi, `userRepository.findById(...)` (mocké) reçoit bien un appel `save`
avec `lastLogin` renseigné proche de l'instant du test. Si aucun test de contrôleur n'existe pour
`AuthController` à ce jour (vérifier), un test d'intégration léger (`@SpringBootTest` +
`MockMvc`, patron à choisir selon ce qui est déjà utilisé ailleurs pour des controllers Spring Security
dans ce projet) est acceptable en alternative — signaler le choix fait dans le rapport final.

---

## Étape 3 — Hook 2 : mise à jour throttlée à chaque requête authentifiée (`AuthTokenFilter`)

**À faire** :
```java
public class AuthTokenFilter extends OncePerRequestFilter {
    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private UserRepository userRepository;

    @Value("${tradeio.auth.last-login-throttle-minutes:15}")
    private long lastLoginThrottleMinutes;

    // ... doFilterInternal existant, ajouter après la ligne
    // SecurityContextHolder.getContext().setAuthentication(authentication); :

    updateLastLoginIfStale(userDetails);

    // ...

    private void updateLastLoginIfStale(UserDetails principal) {
        if (!(principal instanceof UserDetailsImpl userDetails)) {
            return;
        }
        userRepository.findById(userDetails.getId()).ifPresent(u -> {
            Instant now = Instant.now();
            if (u.getLastLogin() == null
                    || u.getLastLogin().isBefore(now.minus(lastLoginThrottleMinutes, ChronoUnit.MINUTES))) {
                u.setLastLogin(now);
                userRepository.save(u);
            }
        });
    }
}
```
Attention à la portée de `userDetails` dans `doFilterInternal(...)` : la variable locale existante
s'appelle déjà `userDetails` dans ce filtre — réutiliser le même nom pour l'appel
`updateLastLoginIfStale(userDetails)` plutôt que d'en introduire un second qui prêterait à confusion.

**Tests attendus** (`AuthTokenFilterTest`, nouveau si aucun test dédié n'existe déjà pour ce filtre —
vérifier avant de conclure ; `UserRepository`/`JwtUtils`/`UserDetailsServiceImpl` mockés, patron déjà
utilisé pour des filtres Spring Security dans ce projet si un exemple existe, sinon construire le
filtre directement et appeler `doFilterInternal(...)` avec des mocks `HttpServletRequest`/
`HttpServletResponse`/`FilterChain`) :
- JWT valide, `lastLogin` actuel `null` : après le passage du filtre, `save(...)` est appelé avec
  `lastLogin` renseigné.
- JWT valide, `lastLogin` actuel récent (< 15 min) : `save(...)` n'est **pas** appelé (couvre le
  throttle — c'est le comportement le plus important à couvrir, celui qui évite l'écriture à chaque
  requête).
- JWT valide, `lastLogin` actuel ancien (> 15 min) : `save(...)` est appelé, nouvelle valeur proche de
  l'instant du test.
- JWT absent/invalide : aucun appel à `userRepository` (le bloc `if (jwt != null &&
  jwtUtils.validateJwtToken(jwt))` existant protège déjà ce cas — vérifier que rien ne change ce
  comportement).

---

## À la fin : lancer les tests via la Gateway

Compiler et exécuter la suite de tests complète via l'opération CI/CD `test:tradeio-5` du gateway SSH
(`mcp__plugin_ssh-gateway_ssh-gateway__executeOperation`). Ne pas lancer `mvn` directement en sandbox
(pas de Maven/réseau disponible).

Rapporter : résultat global, nombre de tests exécutés (comparer à la baseline obtenue après l'étape 4),
détail de tout échec, et signaler explicitement :
- si d'autres sites que `AuthController.registerUserForm` construisaient `User` via le constructeur
  positionnel et ont dû être mis à jour (étape 1) ;
- si un test de contrôleur existait déjà pour `AuthController`/`AuthTokenFilter` avant ce lot (étapes 2
  et 3) ou s'il a fallu en créer un nouveau patron ;
- si la valeur de throttle par défaut (15 min) s'avère gênante à tester proprement (ex. nécessité de
  manipuler le temps) — indiquer comment ça a été contourné (horloge injectée, `Instant` fixé
  manuellement sur l'entité `User` avant l'appel, etc.).

Une fois ce lot mergé, mettre à jour le tableau de statut de
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md` (étape 5 → ✅). L'étape 6 (archivage
sur inactivité prolongée) reste bloquée par l'étape 4 (déjà faite) et cette étape 5 — débloquée à partir
d'ici, mais sa valeur exacte de délai d'archivage (2 mois proposé par Clem comme point de départ) reste
à reconfirmer avant de rédiger son prompt, comme déjà noté dans la roadmap.
