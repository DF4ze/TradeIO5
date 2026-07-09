package fr.ses10doigts.tradeIO5.service.tree.macro;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.macro.MacroEvent;

import java.time.Instant;
import java.util.List;

/**
 * Source de {@link MacroEvent}, étude "indicateurs-macro-externes" §14 item G. Contrairement à
 * {@code Indicator} (qui mesure un état "maintenant"), ce contrat lit une liste d'événements datés
 * sur une fenêtre — nature différente, volontairement pas un {@code IndicatorType}/{@code Indicator}
 * classique (voir prompt d'implémentation, item G, "Nature différente des autres items").
 */
public interface MacroEventProvider {

    /**
     * @return les événements disponibles dans {@code [from, to]}, potentiellement une liste
     * partielle si la source ne permet pas de borner précisément la requête (ex. ForexFactory,
     * qui ne renvoie que "cette semaine" — voir {@code ForexFactoryCalendarClient}, le filtrage
     * exact sur la fenêtre est alors fait par l'appelant). Liste vide (jamais {@code null}) en cas
     * de panne/timeout/erreur HTTP — jamais d'exception.
     */
    List<MacroEvent> fetchEvents(ApiCredentialDTO credential, Instant from, Instant to);
}
