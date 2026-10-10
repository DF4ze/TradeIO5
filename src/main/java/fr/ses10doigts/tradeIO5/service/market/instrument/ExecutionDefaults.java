package fr.ses10doigts.tradeIO5.service.market.instrument;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Constantes métier mutualisées du chemin d'achat (défauts en code, surchargeables par propriété : pas de config DB,
 * cf. règle des défauts dans {@code docs/operations/flyway.md}).
 */
public final class ExecutionDefaults {

    /** Devises fiat : interdites comme nœud de tout chemin (taxation). Surcharge : {@link #FORBIDDEN_NODES_PROPERTY}. */
    public static final Set<String> FIAT_CURRENCIES = Set.of("USD", "EUR", "GBP", "CHF", "JPY", "CAD", "AUD", "NZD",
            "AED", "TRY", "BRL", "MXN", "ARS", "PLN", "SEK", "NOK", "DKK", "CZK", "HUF", "UAH", "SGD", "HKD", "KRW",
            "INR", "ZAR");

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

    /** Marge (en %) ajoutée au manque couvert par une jambe pont, pour absorber ses frais et le mouvement de prix. */
    public static final BigDecimal BRIDGE_FEE_MARGIN_PCT = new BigDecimal("0.3");

    /**
     * Plafonds ABSOLUS d'un ordre et d'une journée par utilisateur (USD), en constante : jamais surchargeables par la base
     * ({@code ExecutionControl}) ni par une propriété. Filet de sécurité contre un {@code baseAmount} ou un multiplicateur erroné.
     */
    public static final BigDecimal ABSOLUTE_MAX_ORDER_USD = new BigDecimal("500");
    public static final BigDecimal ABSOLUTE_MAX_DAY_USD = new BigDecimal("1000");

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

    /** Cotation forcée à la vente quand la paire {@code X-USDT} existe. */
    public static final String FORCED_SELL_QUOTE = "USDT";

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

    /** Nœuds interdits depuis la propriété (liste séparée par des virgules) ; vide => devises fiat. */
    public static Set<String> forbiddenNodes(String property) {
        if (property == null || property.isBlank()) {
            return FIAT_CURRENCIES;
        }
        return Arrays.stream(property.split(",")).map(String::trim).filter(v -> !v.isEmpty()).map(String::toUpperCase)
                .collect(Collectors.toSet());
    }

    private ExecutionDefaults() {
    }
}
