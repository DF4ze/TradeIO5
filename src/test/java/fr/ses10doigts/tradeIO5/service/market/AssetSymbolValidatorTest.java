package fr.ses10doigts.tradeIO5.service.market;

import fr.ses10doigts.tradeIO5.model.entity.currency.Asset;
import fr.ses10doigts.tradeIO5.repository.AssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AssetSymbolValidator")
@ExtendWith(MockitoExtension.class)
class AssetSymbolValidatorTest {

    @Mock
    private AssetRepository assetRepository;

    private AssetSymbolValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AssetSymbolValidator(assetRepository);
    }

    @Test
    @DisplayName("Symbole connu (ex: BTC) → aucune exception")
    void knownSymbol_doesNotThrow() {
        when(assetRepository.findBySymbol("BTC")).thenReturn(Optional.of(
                Asset.builder().symbol("BTC").name("Bitcoin").decimals(8).build()));

        assertDoesNotThrow(() -> validator.requireKnownAsset("BTC"));
    }

    @Test
    @DisplayName("Symbole inconnu → IllegalArgumentException listant les symboles connus")
    void unknownSymbol_throwsWithKnownSymbolsListed() {
        when(assetRepository.findBySymbol("DOGE")).thenReturn(Optional.empty());
        when(assetRepository.findAll()).thenReturn(List.of(
                Asset.builder().symbol("BTC").name("Bitcoin").decimals(8).build(),
                Asset.builder().symbol("ETH").name("Ethereum").decimals(18).build()
        ));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.requireKnownAsset("DOGE"));

        assertTrue(ex.getMessage().contains("DOGE"));
        assertTrue(ex.getMessage().contains("BTC"));
        assertTrue(ex.getMessage().contains("ETH"));
    }

    @Test
    @DisplayName("Paire d'exchange (ex: BTCUSDT) rejetée comme un symbole inconnu, message explicite")
    void exchangeNativePair_isRejectedWithClearMessage() {
        when(assetRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        when(assetRepository.findAll()).thenReturn(List.of(
                Asset.builder().symbol("BTC").name("Bitcoin").decimals(8).build()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.requireKnownAsset("BTCUSDT"));

        assertTrue(ex.getMessage().toLowerCase().contains("exchange"));
    }

    @Test
    @DisplayName("Symbole null ou vide → IllegalArgumentException, sans toucher le repository")
    void blankSymbol_throwsWithoutRepositoryCall() {
        assertThrows(IllegalArgumentException.class, () -> validator.requireKnownAsset(null));
        assertThrows(IllegalArgumentException.class, () -> validator.requireKnownAsset("  "));
        verifyNoInteractions(assetRepository);
    }
}
