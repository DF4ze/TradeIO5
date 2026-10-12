package fr.ses10doigts.tradeIO5.service.market.instrument;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Constantes métier mutualisées du chemin d'achat (défauts en code, surchargeables par propriété : pas de config DB,
 * cf. règle des défauts dans {@code docs/operations/flyway.md}).
 */
public final class ExecutionDefaults {

    /** Devises fiat : interdites comme nœud de tout chemin (taxation). Surcharge : {@link #FORBIDDEN_NODES_PROPERTY}. */
    public static final Set<String> FIAT_CURRENCIES = Set.of("USD", "EUR", "GBP", "CHF", "JPY", "CAD", "AUD", "NZD",
            "AED", "TRY", "BRL", "MXN", "ARS", "PLN", "SEK", "NOK", "DKK", "CZK", "HUF", "UAH", "SGD", "HKD", "KRW",
            "INR", "ZAR");

    /** Stablecoin exclu partout (jamais un nœud de chemin) : les données de marché {@code *USDT} (Binance, candles) ne sont pas concernées. */
    public static final String EXCLUDED_STABLE = "USDT";

    /** Nœuds interdits par défaut : devises fiat + stablecoin exclu. Surcharge : {@link #FORBIDDEN_NODES_PROPERTY}. */
    public static final Set<String> DEFAULT_FORBIDDEN_NODES = Stream.concat(FIAT_CURRENCIES.stream(), Stream.of(EXCLUDED_STABLE))
            .collect(Collectors.toUnmodifiableSet());

    /** Intermédiaires non stables autorisés dans un chemin (doivent avoir eux-mêmes une paire stable sur l'exchange). */
    public static final Set<String> INTERMEDIARIES = Set.of("BTC", "ETH");

    /** Intermédiaires choisis automatiquement (les autres sont chiffrés et affichés, jamais choisis). */
    public static final Set<String> CHOSEN_INTERMEDIARIES = Set.of("BTC");

    /** Nombre maximal de jambes d'un chemin. */
    public static final int MAX_PATH_DEPTH = 2;

    /** Fee Test : en dessous = GREEN ; de {@code warn} à {@code red} inclus = WARNING ; au-delà = RED (en %). */
    public static final BigDecimal FEE_TEST_WARN_PCT = new BigDecimal("0.2");
    public static final BigDecimal FEE_TEST_RED_PCT = new BigDecimal("0.8");

    /** Catalogue d'instruments : rafraîchi au plus 1×/jour ; après un échec, pas de nouvelle tentative avant ce délai. */
    public static final Duration CATALOG_REFRESH_AFTER = Duration.ofHours(24);
    public static final Duration CATALOG_RETRY_AFTER_FAILURE = Duration.ofMinutes(5);

    /** Durée de vie du cache mémoire des frais du compte. */
    public static final Duration FEE_CACHE_TTL = Duration.ofHours(24);

    /** Profondeur du carnet demandée (OKX {@code sz}). */
    public static final int BOOK_DEPTH = 50;

    /** Plan d'ordres : durée de vie d'une photo de plan (au-delà : EXPIRED, l'exécution re-devise). */
    public static final Duration PLAN_TTL = Duration.ofMinutes(5);

    /** Tolérance appliquée au prix de référence pour le prix plafond d'un ordre limit IOC (en %). */
    public static final BigDecimal SLIPPAGE_TOLERANCE_PCT = new BigDecimal("0.1");

    /** Stable de cotation du cash (seul membre du groupe USD) : une étape cotée en cette devise porte le notionnel USD des plafonds. */
    public static final String CASH_STABLE = "USDC";

    /** Jambe 2 d'une route via intermédiaire : nombre maximal d'envois (1 + retries), délai entre deux, re-devis à chaque essai. */
    public static final int LEG2_MAX_ATTEMPTS = 3;
    public static final Duration LEG2_RETRY_DELAY = Duration.ofSeconds(2);

    /**
     * Plafonds ABSOLUS d'un ordre et d'une journée par utilisateur (USD), en constante (valeurs basses de la phase de test : 30 / 100 ; à relever et à rendre configurables par utilisateur plus tard) : jamais surchargeables par la base
     * ({@code ExecutionControl}) ni par une propriété. Filet de sécurité contre un {@code baseAmount} ou un multiplicateur erroné.
     */
    public static final BigDecimal ABSOLUTE_MAX_ORDER_USD = new BigDecimal("30");
    public static final BigDecimal ABSOLUTE_MAX_DAY_USD = new BigDecimal("100");

    /** Plafonds par défaut en multiples du {@code baseAmount} du preset (ordre = 1 ×, jour/utilisateur = 2 ×). */
    public static final BigDecimal DEFAULT_MAX_ORDER_MULTIPLIER = BigDecimal.ONE;
    public static final BigDecimal DEFAULT_MAX_DAY_MULTIPLIER = BigDecimal.valueOf(2);

    /** Dérive maximale tolérée (en points de %) entre le coût du plan et le coût re-devisé avant une étape. */
    public static final BigDecimal COST_DRIFT_POINTS = new BigDecimal("0.05");

    /** Après l'envoi d'un ordre IOC : nombre de relectures de l'état et délai entre deux, avant de conclure {@code UNKNOWN}. */
    public static final int ORDER_QUERY_ATTEMPTS = 5;
    public static final Duration ORDER_QUERY_INTERVAL = Duration.ofMillis(300);

    /** Délai minimal entre deux appels réseau du client d'ordres (limites OKX : 60 requêtes / 2 s par endpoint). */
    public static final Duration ORDER_CLIENT_MIN_INTERVAL = Duration.ofMillis(100);

    /** Utilisateur technique inscrit sur le kill switch quand le back le rengage seul après un {@code run}. */
    public static final String AUTO_REENGAGE_ACTOR = "auto-reengage";

    /** Réponse brute d'exchange gardée pour l'affichage et l'audit (testeur d'ordre) : liste blanche de champs, tronquée à 4 Ko. */
    public static final int RAW_RESPONSE_MAX_CHARS = 4096;

    /** Pagination des lectures (audit, journal admin) : taille par défaut et borne haute. */
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    /** Nombre maximal de paires renvoyées par la liste admin du catalogue (le filtre {@code q} s'applique avant la borne). */
    public static final int ADMIN_CATALOG_MAX_RESULTS = 200;

    /** Fenêtre par défaut (jours) de la liste admin des plans. */
    public static final int ADMIN_PLAN_WINDOW_DAYS = 7;

    public static final String FEE_TEST_WARN_PROPERTY = "tradeio.execution.fee-test.warn-pct";
    public static final String FEE_TEST_RED_PROPERTY = "tradeio.execution.fee-test.red-pct";
    public static final String MODE_PROPERTY = "tradeio.execution.mode";
    /** Verrou LIVE : le mode {@code LIVE} échoue au démarrage tant qu'il n'est pas vrai (déverrouillage = étape d, avec Clem). */
    public static final String LIVE_UNLOCKED_PROPERTY = "tradeio.execution.live-unlocked";
    /** Variable d'environnement du VPS portant la clé maître (base64 de 32 octets) : jamais en base, jamais dans le binaire. */
    public static final String MASTER_KEY_ENV = "TRADEIO_MASTER_KEY";
    public static final String ORDER_QUERY_INTERVAL_PROPERTY = "tradeio.execution.order-query-interval";
    public static final String PLAN_TTL_PROPERTY = "tradeio.execution.plan-ttl";
    public static final String SLIPPAGE_TOLERANCE_PROPERTY = "tradeio.execution.slippage-tolerance-pct";
    /** Placeholder {@code @Scheduled} du job de plan : défaut {@code -} (désactivé), cron cible {@code 0 58 23 * * *}. */
    public static final String PLAN_CRON_PROPERTY = "tradeio.execution.plan-cron";
    public static final String PLAN_CRON = "${" + PLAN_CRON_PROPERTY + ":-}";
    /** Placeholder {@code @Scheduled} du job d'exécution : défaut {@code -} (désactivé), aucun déclenchement implicite. */
    public static final String RUN_CRON_PROPERTY = "tradeio.execution.run-cron";
    public static final String RUN_CRON = "${" + RUN_CRON_PROPERTY + ":-}";
    public static final String SCHEDULER_ZONE = "UTC";
    public static final String FORBIDDEN_NODES_PROPERTY = "tradeio.execution.forbidden-nodes";
    public static final String CATALOG_REFRESH_AFTER_PROPERTY = "tradeio.execution.instrument-catalog.refresh-after";

    /** Nœuds interdits depuis la propriété (liste séparée par des virgules) ; vide => fiat + USDT. */
    public static Set<String> forbiddenNodes(String property) {
        if (property == null || property.isBlank()) {
            return DEFAULT_FORBIDDEN_NODES;
        }
        return Arrays.stream(property.split(",")).map(String::trim).filter(v -> !v.isEmpty()).map(String::toUpperCase)
                .collect(Collectors.toSet());
    }

    private ExecutionDefaults() {
    }
}
