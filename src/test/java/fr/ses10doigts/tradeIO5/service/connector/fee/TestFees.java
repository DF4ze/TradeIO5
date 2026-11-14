package fr.ses10doigts.tradeIO5.service.connector.fee;

import fr.ses10doigts.tradeIO5.repository.TradingFeeCacheRepository;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;

/** Service de frais de test : récupération = les mocks fournis, cache en mémoire (repository simulé vide). */
public final class TestFees {

    private TestFees() {
    }

    public static TradingFeeService service(TradingFeeProvider... fetchers) {
        return new TradingFeeService(List.of(fetchers), mock(TradingFeeCacheRepository.class), Instant::now);
    }
}
