package fr.ses10doigts.tradeIO5.service.dca;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.exceptions.DcaException;
import fr.ses10doigts.tradeIO5.model.dto.dca.DcaOccurrence;
import fr.ses10doigts.tradeIO5.model.dto.dca.DcaResult;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool MCP exposant une simulation DCA (Dollar-Cost Averaging) : prix moyen d'achat pondéré,
 * total investi, PnL à date, pour un calendrier d'achats à montant fixe et fréquence régulière.
 * Cf. docs/etude-dca-tool-mcp.md.
 * <p>
 * Même patron que {@link fr.ses10doigts.tradeIO5.service.tree.api.mcp.TreeAnalysisMcpTools} :
 * le tool renvoie une {@code String} JSON (jamais une {@code Map} directement) — avec
 * spring-ai-starter-mcp-server-webmvc 1.0.9, un retour {@code Map<String,Object>} est poussé
 * dans {@code structuredContent} sans remplir correctement {@code content}, ce qui fait échouer
 * la validation côté client MCP strict. On sérialise donc nous-mêmes, et on n'oublie jamais de
 * capturer les exceptions (le serveur MCP les avale silencieusement sinon).
 */
@Component
public class DcaMcpTools {

    private static final Logger logger = LoggerFactory.getLogger(DcaMcpTools.class);

    private final DcaCalculatorService dcaCalculatorService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DcaMcpTools(DcaCalculatorService dcaCalculatorService) {
        this.dcaCalculatorService = dcaCalculatorService;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize MCP tool result", e);
        }
    }

    /** Cf. TreeAnalysisMcpTools#toJsonOrError : jamais laisser fuiter une exception hors d'un @Tool. */
    private String toJsonOrError(String toolName, java.util.function.Supplier<Map<String, Object>> supplier) {
        try {
            return toJson(supplier.get());
        } catch (Exception e) {
            logger.error("❌ Tool MCP '{}' a échoué", toolName, e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", true);
            error.put("tool", toolName);
            error.put("exception", e.getClass().getName());
            error.put("message", e.getMessage());
            List<String> frames = new ArrayList<>();
            for (StackTraceElement el : e.getStackTrace()) {
                frames.add(el.toString());
                if (frames.size() >= 15) break;
            }
            error.put("stackTop", frames);
            if (e.getCause() != null) {
                error.put("causeType", e.getCause().getClass().getName());
                error.put("causeMessage", e.getCause().getMessage());
            }
            return toJson(error);
        }
    }

    @Tool(
            name = "calculate_dca",
            description = "Simule une stratégie DCA (Dollar-Cost Averaging) entre deux dates : génère le "
                    + "calendrier d'achats à montant fixe selon la fréquence donnée, récupère les prix "
                    + "historiques réels (bougies H1, prix d'ouverture) pour chaque échéance, et calcule le "
                    + "prix moyen d'achat pondéré, le total investi, les frais, la quantité totale acquise, "
                    + "le prix courant et le PnL à date. L'heure d'achat est en UTC. Fréquences acceptées : "
                    + "D1 (quotidien), W1, W2, M1, M2, M3, M6, Y1, Y3 (les timeframes infra-journalières type "
                    + "H1/H4/MIN1 n'ont pas de sens comme cadence DCA et sont rejetées). Source recommandée : "
                    + "BINANCE (seule à fournir un historique H1 profond ; KRAKEN/OKX sont limités à ~25 jours "
                    + "et échouent explicitement au-delà)."
    )
    public String calculateDca(
            @ToolParam(description = "Symbole du marché, ex: BTCUSDT") String symbol,
            @ToolParam(description = "Date de la première échéance d'achat, format ISO yyyy-MM-dd") String startDate,
            @ToolParam(description = "Date de la dernière échéance possible, format ISO yyyy-MM-dd (bornée à aujourd'hui si future)") String endDate,
            @ToolParam(description = "Fréquence des achats : D1, W1, W2, M1, M2, M3, M6, Y1, Y3") TimeFrame frequency,
            @ToolParam(description = "Heure d'achat en UTC, entier entre 0 et 23") int purchaseHourUtc,
            @ToolParam(description = "Montant investi à chaque échéance (devise de cotation du symbole, ex: USDT)") double amount,
            @ToolParam(description = "Frais en pourcentage appliqués à chaque achat (0-100), défaut 0", required = false) Double feePercent,
            @ToolParam(description = "Source des prix historiques : BINANCE (recommandé, défaut), KRAKEN, OKX", required = false) MarketDataSource source
    ) {
        return toJsonOrError("calculate_dca", () -> {
            LocalDate start = parseDate(startDate, "startDate");
            LocalDate end = parseDate(endDate, "endDate");

            DcaResult result = dcaCalculatorService.calculate(
                    symbol,
                    start,
                    end,
                    frequency,
                    purchaseHourUtc,
                    BigDecimal.valueOf(amount),
                    feePercent != null ? BigDecimal.valueOf(feePercent) : null,
                    source
            );
            return dcaResponse(result);
        });
    }

    private static LocalDate parseDate(String value, String fieldName) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new DcaException(fieldName + " invalide, format attendu yyyy-MM-dd : " + value, e);
        }
    }

    private static Map<String, Object> dcaResponse(DcaResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("symbol", result.getSymbol());
        response.put("source", result.getSource().name());
        response.put("frequency", result.getFrequency().name());
        response.put("purchaseHourUtc", result.getPurchaseHourUtc());
        response.put("firstOccurrence", result.getFirstOccurrence().toString());
        response.put("lastOccurrence", result.getLastOccurrence().toString());
        response.put("occurrenceCount", result.getOccurrenceCount());
        response.put("missingCount", result.getMissingCount());
        response.put("totalInvested", result.getTotalInvested());
        response.put("totalFees", result.getTotalFees());
        response.put("totalQuantity", result.getTotalQuantity());
        response.put("avgPrice", result.getAvgPrice());
        response.put("currentPrice", result.getCurrentPrice());
        response.put("currentValue", result.getCurrentValue());
        response.put("pnl", result.getPnl());
        response.put("pnlPercent", result.getPnlPercent());
        response.put("occurrences", occurrencesJson(result.getOccurrences()));
        return response;
    }

    private static List<Map<String, Object>> occurrencesJson(List<DcaOccurrence> occurrences) {
        List<Map<String, Object>> list = new ArrayList<>(occurrences.size());
        for (DcaOccurrence occurrence : occurrences) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timestamp", occurrence.getTimestamp().toString());
            map.put("plannedAmount", occurrence.getPlannedAmount());
            map.put("missing", occurrence.isMissing());
            map.put("price", occurrence.getPrice());
            map.put("fee", occurrence.getFee());
            map.put("quantity", occurrence.getQuantity());
            list.add(map);
        }
        return list;
    }
}
