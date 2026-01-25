package fr.ses10doigts.tradeIO5.service.tree.decision.advisor;

import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.LlmAdvice;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.UserProfile;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.WalletSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Data
public abstract class AbstractAdvisor implements DecisionAdvisor{
    private final Logger logger = LoggerFactory.getLogger(AbstractAdvisor.class);

    private Map<String, LlmAdvice> cache = new ConcurrentHashMap<>();
    private Duration timeout = Duration.ofSeconds(60);



    @Override
    public LlmAdvice advise(DecisionContext ctx) {
        String key = cacheKey(ctx);

        return cache.getOrDefault(
                key,
                executeWithTimeoutAndFallback(ctx, key)
        );
    }

    protected abstract LlmAdvice callModel(DecisionContext ctx);

    protected String cacheKey(DecisionContext ctx) {
        return getType().toString()+ctx.hashCode();
    }

    private LlmAdvice executeWithTimeoutAndFallback(DecisionContext ctx, String key) {
        try {
            LlmAdvice advice = CompletableFuture
                    .supplyAsync(() -> callModel(ctx))
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .join();

            cache.put(key, advice);
            return advice;

        } catch (Exception e) {
            logger.warn("LLM timeout/failure → fallback", e);
            return LlmAdvice.invalid();
        }
    }

    /////////// Prompt Gen
    ///

    protected String buildPrompt(DecisionContext ctx) {
        StringBuilder sb = new StringBuilder();

        sb.append(systemHeader());
        sb.append(userProfileBlock(ctx.userProfile()));
        sb.append(walletBlock(ctx.walletSnapshot()));
        sb.append(marketBlock(ctx.marketContext()));
        sb.append(indicatorsBlock(ctx.marketContext()));
        sb.append(expectedOutputBlock());

        return sb.toString();
    }

    protected String systemHeader() {
        return """
    Tu es un assistant d’analyse de marché crypto.
    Je vais te fournir une liste d'indicateurs que j'ai calculé.
    Tu fournis une recommandation BUY / SELL / HOLD avec un niveau le confiance (entre 0 et 1) de ta réponse.
    """;
    }

    protected String expectedOutputBlock() {
        return """
    Réponds STRICTEMENT au format JSON :
    {
      "action": "BUY|SELL|HOLD",
      "confidence": 0.0-1.0,
      "rationale": "texte très court qui explique ton choix"
      "valid":true
    }
    """;
    }

    protected String userProfileBlock(UserProfile up) {
        return "";
    }

    protected String walletBlock(WalletSnapshot ws){
        return "";
    }

    protected String marketBlock(MarketContext mc){
        StringBuilder sb = new StringBuilder();

        sb.append("Asset: ").append(mc.symbol()).append("\n");
        sb.append("LastPrice: ").append(mc.lastPrice()).append("\n");

        return sb.toString();
    }

    protected String indicatorsBlock(MarketContext mc) {
        if (mc.indicatorValues().isEmpty()) {
            return "\nAucun indicateur technique disponible.\n";
        }

        StringBuilder sb = new StringBuilder("\nIndicateurs techniques :\n");

        mc.indicatorValues().forEach((key, result) -> {
            if (result == null || !result.isValid()) return;

            sb.append("- ")
                    .append(formatIndicatorTitle(key.getParams()))
                    .append(" : ")
                    .append(formatIndicatorResult(result))
                    .append("\n");
        });

        return sb.append("\n").toString();
    }

    protected String formatIndicatorTitle( IndicatorParameters params ){
        StringBuilder sb = new StringBuilder();

        sb.append(params.getIndicatorType());


        if( params.getBooleans() != null || params.getStrings() != null || (params.getNumerics() != null && !params.getNumerics().isEmpty()) ){
            sb.append("( ");

            if( params.getBooleans() != null ){
                params.getBooleans().forEach((key, value) ->{
                    sb.append(key).append(":").append(value).append(" ");
                });
            }

            if( params.getStrings() != null ){
                params.getStrings().forEach((key, value) ->{
                    sb.append(key).append(":").append(value).append(" ");
                });
            }

            if( params.getNumerics() != null && !params.getNumerics().isEmpty() ){
                params.getNumerics().forEach((key, value) ->{
                    sb.append(key).append(":").append(value).append(" ");
                });
            }

            sb.append(")");
        }

        return sb.toString();
    }

    protected String formatIndicatorResult(IndicatorResult r) {
        StringBuilder sb = new StringBuilder();

        if( r.getValue() != null || r.getMin() != null || r.getMax() != null || r.getValues() != null ) {

            if (r.getValue() != null) {
                sb.append("value=").append(r.getValue()).append(", ");
            }

            if( r.getMin() != null ){
                sb.append("min=").append(r.getMin()).append(", ");
            }

            if( r.getMax() != null ){
                sb.append("max=").append(r.getMax()).append(", ");
            }

            if( r.getValues() != null && !r.getValues().isEmpty() ){
                r.getValues().forEach( (key, value) ->{
                    sb.append(key).append("=").append(value).append(", ");
                });
            }
        }

        return sb.toString();
    }

    /**
     * - RSI(14) : OVERBOUGHT (value=78.2)
     * - EMA(50/200) : BULLISH_CROSS
     * - VOLATILITY : HIGH
     */

}
