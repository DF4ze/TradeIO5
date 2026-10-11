package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminInstrumentDto;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Liste des paires du catalogue d'un exchange pour le testeur d'ordre (lecture du catalogue en base, filtrable). */
@Service
@RequiredArgsConstructor
public class AdminCatalogService {

    private final InstrumentCatalog catalog;
    private final ProviderRepository providers;

    /**
     * @param query sous-chaîne de {@code instId} (insensible à la casse), facultative
     * @throws IllegalArgumentException exchange inconnu
     */
    public List<AdminInstrumentDto> instruments(WebProviderCode provider, String query) {
        if (provider == null) {
            throw new IllegalArgumentException("provider est requis");
        }
        var webProvider = providers.findByCode(provider).orElseThrow(() -> new IllegalArgumentException("Exchange inconnu : " + provider));
        String needle = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
        return catalog.allInstruments(webProvider).stream()
                .filter(i -> needle.isEmpty() || i.getInstId().contains(needle))
                .sorted(Comparator.comparing(i -> i.getInstId()))
                .limit(ExecutionDefaults.ADMIN_CATALOG_MAX_RESULTS)
                .map(i -> new AdminInstrumentDto(i.getInstId(), i.getState(), i.getMinSz(), i.getCostMin(), i.getLotSz(), i.getTickSz(),
                        i.getFeeCurrency()))
                .toList();
    }
}
