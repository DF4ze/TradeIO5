package fr.ses10doigts.tradeIO5.repository.market;

import fr.ses10doigts.tradeIO5.model.entity.market.ExchangeInstrument;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExchangeInstrumentRepository extends JpaRepository<ExchangeInstrument, Long> {

    List<ExchangeInstrument> findByProvider(WebProviderCode provider);

    Optional<ExchangeInstrument> findByProviderAndInstId(WebProviderCode provider, String instId);

    Optional<ExchangeInstrument> findTopByProviderOrderByFetchedAtDesc(WebProviderCode provider);

    void deleteByProviderAndInstIdNotIn(WebProviderCode provider, Collection<String> instIds);
}
