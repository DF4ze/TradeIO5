package fr.ses10doigts.tradeIO5.service.tree.macro;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.macro.MacroEvent;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventImpact;
import fr.ses10doigts.tradeIO5.service.tree.macro.finnhub.FinnhubEconomicCalendarClient;
import fr.ses10doigts.tradeIO5.service.tree.macro.forexfactory.ForexFactoryCalendarClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agrège les deux sources de calendrier macro (étude "indicateurs-macro-externes" §14 item G) :
 * Finnhub (source principale) et ForexFactory (complément, sans clé). Service en <b>lecture
 * seule</b> — volontairement <b>non branché</b> dans {@code DecisionEngine}/{@code Scenario} à ce
 * stade (décision explicitement reportée, cf. prompt d'implémentation, item G, "Décision
 * explicitement hors scope de ce lot").
 */
@Service
public class MacroEventCalendarService {

    private static final Logger logger = LoggerFactory.getLogger(MacroEventCalendarService.class);

    /**
     * Fenêtre de rapprochement pour {@link #isSameEvent} : deux événements rapportés par les deux
     * sources pour le même FOMC (ou autre annonce) peuvent différer de quelques minutes selon
     * l'horodatage exact retenu par chaque fournisseur.
     */
    static final Duration DEDUP_TIME_WINDOW = Duration.ofMinutes(10);

    private final MacroCredentialResolver credentialResolver;
    private final FinnhubEconomicCalendarClient finnhubClient;
    private final ForexFactoryCalendarClient forexFactoryClient;

    public MacroEventCalendarService(
            MacroCredentialResolver credentialResolver,
            FinnhubEconomicCalendarClient finnhubClient,
            ForexFactoryCalendarClient forexFactoryClient) {
        this.credentialResolver = credentialResolver;
        this.finnhubClient = finnhubClient;
        this.forexFactoryClient = forexFactoryClient;
    }

    /**
     * @return les événements macro dans {@code [from, to]}, dédoublonnés entre Finnhub et
     * ForexFactory (voir {@link #dedupe}), triés chronologiquement.
     */
    public List<MacroEvent> getEvents(Instant from, Instant to) {
        List<MacroEvent> finnhubEvents = fetchFrom(WebProviderCode.FINNHUB, finnhubClient, from, to);
        List<MacroEvent> forexFactoryEvents = fetchFrom(WebProviderCode.FOREXFACTORY, forexFactoryClient, from, to);

        return dedupe(finnhubEvents, forexFactoryEvents);
    }

    /**
     * @return {@code true} si au moins un événement d'impact &gt;= {@code minImpact} tombe dans
     * {@code [now - window, now + window]}.
     */
    public boolean isWithinRiskWindow(Instant now, Duration window, MacroEventImpact minImpact) {
        List<MacroEvent> events = getEvents(now.minus(window), now.plus(window));
        return hasRiskEvent(events, minImpact);
    }

    /**
     * Logique pure derrière {@link #isWithinRiskWindow}, isolée de la résolution réseau pour être
     * testable en unitaire sur une liste d'événements construite à la main.
     */
    static boolean hasRiskEvent(List<MacroEvent> events, MacroEventImpact minImpact) {
        return events.stream().anyMatch(e -> impactRank(e.getImpact()) >= impactRank(minImpact));
    }

    private List<MacroEvent> fetchFrom(WebProviderCode provider, MacroEventProvider client, Instant from, Instant to) {
        ApiCredentialDTO credential = credentialResolver.resolve(provider);
        if (credential == null) {
            logger.debug("{} : pas de credential, source ignorée pour cette requête.", provider);
            return List.of();
        }
        return client.fetchEvents(credential, from, to);
    }

    /**
     * Garde tous les événements Finnhub, n'ajoute un événement ForexFactory que s'il ne recoupe
     * aucun événement Finnhub déjà présent (Finnhub gagne en cas de doublon car il porte
     * potentiellement {@code actual} en plus de {@code forecast}/{@code previous}, cf. prompt
     * d'implémentation item G point 4). Isolé pour être testable en unitaire sans réseau.
     */
    static List<MacroEvent> dedupe(List<MacroEvent> finnhubEvents, List<MacroEvent> forexFactoryEvents) {
        List<MacroEvent> merged = new ArrayList<>(finnhubEvents);
        for (MacroEvent ffEvent : forexFactoryEvents) {
            boolean isDuplicate = finnhubEvents.stream().anyMatch(fhEvent -> isSameEvent(fhEvent, ffEvent));
            if (!isDuplicate) {
                merged.add(ffEvent);
            }
        }
        return merged.stream()
                .sorted(Comparator.comparing(MacroEvent::getDateTime))
                .toList();
    }

    static boolean isSameEvent(MacroEvent a, MacroEvent b) {
        if (a == null || b == null) {
            return false;
        }
        if (!Objects.equals(a.getCountry(), b.getCountry())) {
            return false;
        }
        if (a.getImpact() != b.getImpact()) {
            return false;
        }
        if (a.getDateTime() == null || b.getDateTime() == null) {
            return false;
        }
        Duration delta = Duration.between(a.getDateTime(), b.getDateTime()).abs();
        if (delta.compareTo(DEDUP_TIME_WINDOW) > 0) {
            return false;
        }
        return titleApproxEquals(a.getTitle(), b.getTitle());
    }

    /**
     * Compare deux titres au niveau des mots plutôt qu'en sous-chaîne brute : les sources
     * omettent parfois un mot ("FOMC Interest Rate Decision" côté Finnhub vs "FOMC Rate
     * Decision" côté ForexFactory pour le même événement). Considère les titres comme le même
     * événement si l'ensemble des mots de l'un est entièrement inclus dans l'ensemble des mots
     * de l'autre.
     */
    private static boolean titleApproxEquals(String t1, String t2) {
        if (t1 == null || t2 == null) {
            return false;
        }
        Set<String> w1 = titleWords(t1);
        Set<String> w2 = titleWords(t2);
        if (w1.isEmpty() || w2.isEmpty()) {
            return false;
        }
        return w1.containsAll(w2) || w2.containsAll(w1);
    }

    private static Set<String> titleWords(String title) {
        return Arrays.stream(title.toLowerCase().split("[^a-z0-9]+"))
                .filter(word -> !word.isEmpty())
                .collect(Collectors.toSet());
    }

    private static int impactRank(MacroEventImpact impact) {
        return switch (impact) {
            case HOLIDAY -> 0;
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
        };
    }
}
