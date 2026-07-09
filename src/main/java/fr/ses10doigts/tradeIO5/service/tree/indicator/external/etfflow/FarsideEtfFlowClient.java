package fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow;

import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.EtfFlowResponse;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.AbstractExternalIndicator;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Scraping de la page Farside (étude "indicateurs-macro-externes", §14, item I) : page HTML
 * servie côté serveur, un simple client HTTP suffit (pas de rendu JS nécessaire). Structure
 * vérifiée en direct : {@code https://farside.co.uk/btc/} et {@code /eth/}, même mise en page.
 * <p>
 * Contrairement aux autres clients externes du projet (JSON -> DTO Jackson), ce client parse du
 * HTML avec Jsoup selon des règles de format spécifiques (notation comptable des négatifs,
 * distinction {@code -} vs {@code 0.0}, lignes de synthèse à ignorer par contenu de cellule plutôt
 * que par position). Voir {@link #parse(String)} pour le détail, isolé de l'appel réseau pour être
 * testable sur une fixture HTML statique (patron {@code DefiLlamaStablecoinClient.aggregate}).
 */
@Component
public class FarsideEtfFlowClient extends AbstractExternalIndicator implements EtfFlowProvider {

    private static final Logger logger = LoggerFactory.getLogger(FarsideEtfFlowClient.class);

    // Format observé sur la page réelle : "22 Jun 2026".
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private static final Pattern DATE_PATTERN =
            Pattern.compile("^\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{4}$");

    // Lignes de synthèse/métadonnées à ignorer explicitement : la ligne "Fee" juste après
    // l'en-tête, et les 4 lignes de synthèse en bas de tableau. Reconnues par le contenu de la
    // première colonne (pas par position, cf. étude : plus fiable si la mise en page bouge).
    private static final Set<String> SUMMARY_ROW_LABELS =
            Set.of("fee", "total", "average", "maximum", "minimum");

    @Override
    public EtfFlowResponse fetch(ApiCredentialDTO credential, EtfFlowAsset asset) {
        try {
            String html = getHtml(credential, asset);
            return parse(html);
        } catch (ExternalApiException e) {
            logger.warn("Farside ({}) unavailable: {}", asset, e.getMessage());
            return EtfFlowResponse.invalid();
        } catch (Exception e) {
            logger.error("Farside ({}) unexpected error", asset, e);
            return EtfFlowResponse.invalid();
        }
    }

    private String getHtml(ApiCredentialDTO credential, EtfFlowAsset asset) {
        return getWebClient(credential).get()
                .uri(asset.getPath())
                .retrieve()
                .onStatus(
                        HttpStatusCode::is4xxClientError,
                        response -> response.bodyToMono(String.class)
                                .map(body -> new ExternalApiException("Farside 4xx: " + body))
                )
                .onStatus(
                        HttpStatusCode::is5xxServerError,
                        response -> response.bodyToMono(String.class)
                                .map(body -> new ExternalApiException("Farside 5xx: " + body))
                )
                .bodyToMono(String.class)
                // même marge que les autres clients externes du projet (20s).
                .timeout(Duration.ofSeconds(20))
                .block();
    }

    /**
     * Chaque étape peut échouer proprement vers {@link EtfFlowResponse#invalid()}, avec un log
     * warn distinct précisant *quelle* étape a échoué (en-tête introuvable, ligne de donnée avant
     * tout en-tête, aucune colonne émetteur, aucune ligne ne parse comme une date, format de
     * nombre invalide) plutôt qu'un "erreur de parsing" générique — objectif explicite de l'item :
     * qu'un changement de structure de la page soit détecté vite, pas noyé en silence dans des
     * `invalid()` pendant des semaines.
     */
    static EtfFlowResponse parse(String html) {
        if (html == null || html.isBlank()) {
            logger.warn("Farside parsing failed at step 'fetch': empty HTML body");
            return EtfFlowResponse.invalid();
        }

        Document doc = Jsoup.parse(html);
        Elements tables = doc.select("table");
        if (tables.isEmpty()) {
            logger.warn("Farside parsing failed at step 'table lookup': no <table> found on page");
            return EtfFlowResponse.invalid();
        }

        Elements rows = Objects.requireNonNull(tables.first()).select("tr");
        if (rows.isEmpty()) {
            logger.warn("Farside parsing failed at step 'table lookup': table has no row");
            return EtfFlowResponse.invalid();
        }

        // En-tête dynamique : dernière ligne "non-donnée" (ni ligne de synthèse Fee/Total/Average/
        // Maximum/Minimum, ni ligne de flux quotidien) rencontrée avant la première ligne de
        // donnée. Robuste au fait que la page a en réalité 2 lignes d'en-tête (logos, puis
        // tickers) : on garde la dernière, celle des tickers.
        List<Element> headerCells = null;
        List<RowData> dataRows = new ArrayList<>();

        for (Element row : rows) {
            List<Element> cells = cellsOf(row);
            if (cells.isEmpty()) {
                continue;
            }
            String firstCell = cells.getFirst().text().trim();

            if (DATE_PATTERN.matcher(firstCell).matches()) {
                if (headerCells == null) {
                    logger.warn("Farside parsing failed at step 'header lookup': data row '{}' "
                            + "encountered before any header row", firstCell);
                    return EtfFlowResponse.invalid();
                }
                RowData rowData = parseDataRow(firstCell, cells);
                if (rowData != null) {
                    dataRows.add(rowData);
                }
            } else if (!SUMMARY_ROW_LABELS.contains(firstCell.toLowerCase(Locale.ROOT))) {
                headerCells = cells;
            }
            // sinon : ligne "Fee"/"Total"/"Average"/"Maximum"/"Minimum", ignorée.
        }

        if (headerCells == null) {
            logger.warn("Farside parsing failed at step 'header lookup': no header row found before "
                    + "any data row");
            return EtfFlowResponse.invalid();
        }
        if (headerCells.size() < 3) {
            logger.warn("Farside parsing failed at step 'header lookup': header row has only {} "
                    + "column(s), expected at least date + 1 issuer + total", headerCells.size());
            return EtfFlowResponse.invalid();
        }

        List<String> tickers = new ArrayList<>();
        for (int i = 1; i < headerCells.size() - 1; i++) {
            tickers.add(headerCells.get(i).text().trim());
        }
        if (tickers.isEmpty()) {
            logger.warn("Farside parsing failed at step 'header lookup': no issuer column detected "
                    + "in header row");
            return EtfFlowResponse.invalid();
        }

        if (dataRows.isEmpty()) {
            logger.warn("Farside parsing failed at step 'data rows': no row parsed as a date "
                    + "('DD Mon YYYY')");
            return EtfFlowResponse.invalid();
        }

        // Dernière ligne avec au moins une donnée réellement publiée : exclut la ligne du jour
        // tant que le marché US n'a pas clôturé (toutes les cases valent "-" ce jour-là).
        RowData latestPublished = null;
        for (RowData rowData : dataRows) {
            if (rowData.byIssuer().isEmpty()) {
                continue;
            }
            if (latestPublished == null || rowData.date().isAfter(latestPublished.date())) {
                latestPublished = rowData;
            }
        }

        if (latestPublished == null) {
            logger.warn("Farside parsing failed at step 'data rows': every parsed row is "
                    + "unpublished ('-' on every issuer)");
            return EtfFlowResponse.invalid();
        }

        Map<String, Double> byIssuerNamed = new LinkedHashMap<>();
        for (Map.Entry<Integer, Double> entry : latestPublished.byIssuer().entrySet()) {
            int idx = entry.getKey();
            if (idx < tickers.size()) {
                byIssuerNamed.put(tickers.get(idx), entry.getValue());
            }
        }

        return EtfFlowResponse.builder()
                .valid(true)
                .date(latestPublished.date())
                .byIssuer(byIssuerNamed)
                .total(latestPublished.total())
                .build();
    }

    private static List<Element> cellsOf(Element row) {
        List<Element> cells = new ArrayList<>();
        for (Element child : row.children()) {
            String tag = child.tagName();
            if (tag.equalsIgnoreCase("td") || tag.equalsIgnoreCase("th")) {
                cells.add(child);
            }
        }
        return cells;
    }

    private static RowData parseDataRow(String dateText, List<Element> cells) {
        LocalDate date;
        try {
            date = LocalDate.parse(dateText, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            logger.warn("Farside parsing failed at step 'date format' on '{}': {}", dateText, e.getMessage());
            return null;
        }

        if (cells.size() < 3) {
            logger.warn("Farside parsing failed at step 'row shape' on {}: only {} column(s), "
                    + "expected at least date + 1 issuer + total", date, cells.size());
            return null;
        }

        Map<Integer, Double> byIssuer = new LinkedHashMap<>();
        for (int i = 1; i < cells.size() - 1; i++) {
            Double value = parseFlowNumber(cells.get(i).text());
            if (value != null) {
                byIssuer.put(i - 1, value);
            }
        }

        Double total = parseFlowNumber(cells.getLast().text());

        return new RowData(date, byIssuer, total);
    }

    /**
     * Notation comptable pour les négatifs ({@code (172.0)} -> {@code -172.0}) ; virgule comme
     * séparateur de milliers, observée uniquement sur les lignes de synthèse mais tolérée partout
     * par robustesse ({@code 60,258}) ; {@code -} = absence de valeur (jour non publié), distinct
     * de {@code 0.0} qui est un flux nul réel et doit rester {@code 0.0}.
     */
    static Double parseFlowNumber(String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty() || text.equals("-")) {
            return null;
        }

        boolean negative = text.startsWith("(") && text.endsWith(")");
        if (negative) {
            text = text.substring(1, text.length() - 1);
        }
        text = text.replace(",", "").trim();

        try {
            double value = Double.parseDouble(text);
            return negative ? -value : value;
        } catch (NumberFormatException e) {
            logger.warn("Farside parsing failed at step 'number format' on cell '{}': {}", rawText, e.getMessage());
            return null;
        }
    }

    private record RowData(LocalDate date, Map<Integer, Double> byIssuer, Double total) {}
}
