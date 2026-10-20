package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveLiveBlock;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LiveSizing avec modulation")
class LiveSizingModulationTest {

    private static final double CLOSE = 100.0;
    private static final Map<String, Double> CLOSES = Map.of("BTC", CLOSE);
    private static final ModulationSettings NEUTRAL = ModulationSettings.neutral();

    private final RainbowModulationControl limits = RainbowModulationControl.defaults(Instant.EPOCH, null);

    private static PortfolioReading reading(double cash, double btc) {
        return new PortfolioReading(cash, Map.of("BTC", btc), Instant.EPOCH, PortfolioStatus.OK, 1L);
    }

    private RainbowLiveLiveBlock size(PortfolioReading reading, ModulationSettings settings, LiveDemand demand, double bag) {
        return size(reading, settings, demand, bag, ExposureLedger.of(reading, CLOSES));
    }

    private RainbowLiveLiveBlock size(PortfolioReading reading, ModulationSettings settings, LiveDemand demand, double bag,
                                      ExposureLedger exposure) {
        RainbowLiveLiveBlock block = new RainbowLiveLiveBlock();
        LiveSizing.of("BTC", reading, new CashLedger(reading.cash()), bag, new LiveModulation(settings, limits, exposure))
                .apply(block, demand);
        return block;
    }

    private static LiveDemand buy(double usd) {
        return LiveDemand.fixed(usd, 0, CLOSE, 0);
    }

    private static LiveDemand sell(double qty) {
        return LiveDemand.fixed(0, qty, CLOSE, 0);
    }

    @Test
    @DisplayName("Réglages neutres : exactement les montants d'avant la modulation (achat et vente)")
    void neutralSettingsChangeNothing() {
        PortfolioReading reading = reading(1000, 2.0);

        RainbowLiveLiveBlock plainBuy = new RainbowLiveLiveBlock();
        LiveSizing.of("BTC", reading, new CashLedger(1000), 100).apply(plainBuy, buy(40));
        RainbowLiveLiveBlock modBuy = size(reading, NEUTRAL, buy(40), 100);
        assertEquals(plainBuy.getActionType(), modBuy.getActionType());
        assertEquals(plainBuy.getActionAmountUsdc(), modBuy.getActionAmountUsdc());
        assertEquals(plainBuy.getActionQuantity(), modBuy.getActionQuantity());

        RainbowLiveLiveBlock plainSell = new RainbowLiveLiveBlock();
        LiveSizing.of("BTC", reading, new CashLedger(1000), 100).apply(plainSell, sell(0.5));
        RainbowLiveLiveBlock modSell = size(reading, NEUTRAL, sell(0.5), 100);
        assertEquals(plainSell.getActionType(), modSell.getActionType());
        assertEquals(plainSell.getActionQuantity(), modSell.getActionQuantity());
        assertEquals(plainSell.getActionAmountUsdc(), modSell.getActionAmountUsdc());
        assertEquals(LiveBlockReason.NONE, modSell.getBlockReason());
    }

    @Test
    @DisplayName("Sans modulation (ancien appel) : aucun journal")
    void noModulationNoJournal() {
        RainbowLiveLiveBlock block = new RainbowLiveLiveBlock();
        LiveSizing.of("BTC", reading(1000, 1), new CashLedger(1000), 100).apply(block, buy(10));
        assertNull(block.getModulation());
    }

    @Test
    @DisplayName("Puissance 1,5 : achat et vente ×1,5")
    void powerScalesBothSides() {
        ModulationSettings settings = new ModulationSettings(0, 100, 1.5, 0);
        assertEquals(60.0, size(reading(1000, 2), settings, buy(40), 100).getActionAmountUsdc(), 1e-9);
        assertEquals(0.75, size(reading(1000, 2), settings, sell(0.5), 100).getActionQuantity(), 1e-9);
    }

    @Test
    @DisplayName("Biais +0,5 : achat ×1,5, vente ×0,5 ; biais −0,5 : l'inverse")
    void biasTiltsBuyAgainstSell() {
        ModulationSettings up = new ModulationSettings(0, 100, 1, 0.5);
        assertEquals(60.0, size(reading(1000, 2), up, buy(40), 100).getActionAmountUsdc(), 1e-9);
        assertEquals(0.25, size(reading(1000, 2), up, sell(0.5), 100).getActionQuantity(), 1e-9);
        ModulationSettings down = new ModulationSettings(0, 100, 1, -0.5);
        assertEquals(20.0, size(reading(1000, 2), down, buy(40), 100).getActionAmountUsdc(), 1e-9);
        assertEquals(0.75, size(reading(1000, 2), down, sell(0.5), 100).getActionQuantity(), 1e-9);
    }

    @Test
    @DisplayName("Produit plafonné à modulationProductMax (2,5) : puissance 2 × biais 1,5 = 3 => 2,5")
    void productIsCapped() {
        ModulationSettings settings = new ModulationSettings(0, 100, 2, 0.5);
        RainbowLiveLiveBlock block = size(reading(1000, 2), settings, buy(10), 100);
        assertEquals(25.0, block.getActionAmountUsdc(), 1e-9);
        assertTrue(block.getModulation().contains("\"productCapped\":true"));
    }

    @Test
    @DisplayName("Vente toujours plafonnée à la position tradable (bagPercent), même après accentuation")
    void sellStillCappedByTradable() {
        ModulationSettings settings = new ModulationSettings(0, 100, 2, 0);
        RainbowLiveLiveBlock block = size(reading(1000, 2), settings, sell(1.0), 25);
        assertEquals(0.5, block.getActionQuantity(), 1e-9);
    }

    @Test
    @DisplayName("Près de la borne haute : achat réduit ; vente accentuée (×2 à la borne)")
    void upperMarginReducesBuyAndBoostsSell() {
        ModulationSettings band = new ModulationSettings(30, 90, 1, 0);
        PortfolioReading nearMax = reading(150, 8.5);
        RainbowLiveLiveBlock buyBlock = size(nearMax, band, buy(20), 100);
        assertTrue(buyBlock.getActionAmountUsdc() < 20.0);
        RainbowLiveLiveBlock sellBlock = size(nearMax, band, sell(0.1), 100);
        assertTrue(sellBlock.getActionQuantity() > 0.1);
        assertTrue(sellBlock.getActionQuantity() <= 0.2 + 1e-9);
    }

    @Test
    @DisplayName("À la borne haute (90 %) : achat ramené à 0 => NONE + BAND_LIMIT, journal écrit")
    void atUpperBoundBuyIsBandLimited() {
        ModulationSettings band = new ModulationSettings(30, 90, 1, 0);
        RainbowLiveLiveBlock block = size(reading(100, 9.0), band, buy(20), 100);
        assertEquals(RainbowLiveAction.NONE, block.getActionType());
        assertEquals(LiveBlockReason.BAND_LIMIT, block.getBlockReason());
        assertNotNull(block.getModulation());
        assertTrue(block.getModulation().contains("\"modulatedBuyUsd\":0.0"));
    }

    @Test
    @DisplayName("Plafond dur : un achat ne franchit jamais max·V (reste de 10 $ sous 90 %)")
    void hardCapKeepsBuyBelowMax() {
        ModulationSettings band = new ModulationSettings(30, 90, 2, 0);
        RainbowLiveLiveBlock block = size(reading(100, 8.0), band, buy(50), 100);
        assertTrue(block.getActionAmountUsdc() <= 10.0 + 1e-9);
    }

    @Test
    @DisplayName("Plafond dur de vente : jamais sous min·V (30 % de 1000 $ ⇒ au plus 500 $ vendus depuis 800 $)")
    void hardCapKeepsSellAboveMin() {
        ModulationSettings band = new ModulationSettings(30, 90, 2, 0);
        RainbowLiveLiveBlock block = size(reading(200, 8.0), band, sell(8.0), 100);
        assertTrue(block.getActionAmountUsdc() <= 500.0 + 1e-9);
    }

    @Test
    @DisplayName("Signal sans action : aucune action créée par la modulation, pas de BAND_LIMIT, pas de journal")
    void noSignalNoAction() {
        ModulationSettings settings = new ModulationSettings(30, 90, 2, 0.5);
        RainbowLiveLiveBlock block = size(reading(1000, 2), settings, LiveDemand.fixed(0, 0, CLOSE, 0), 100);
        assertEquals(RainbowLiveAction.NONE, block.getActionType());
        assertEquals(LiveBlockReason.NONE, block.getBlockReason());
        assertNull(block.getModulation());
    }

    @Test
    @DisplayName("Exposition inconnue : pas de modulation de bande, puissance et biais appliqués, journal UNKNOWN")
    void unknownExposureStillAppliesPower() {
        ModulationSettings settings = new ModulationSettings(30, 90, 1.5, 0);
        RainbowLiveLiveBlock block = size(reading(100, 9.0), settings, buy(20), 100, ExposureLedger.unknown());
        assertEquals(RainbowLiveAction.BUY, block.getActionType());
        assertEquals(30.0, block.getActionAmountUsdc(), 1e-9);
        assertTrue(block.getModulation().contains("\"exposureStatus\":\"UNKNOWN\""));
    }

    @Test
    @DisplayName("Un achat accepté fait monter l'exposition vue par le lien suivant du même wallet")
    void exposureUpdatedForNextLink() {
        ModulationSettings band = new ModulationSettings(30, 90, 1, 0);
        PortfolioReading reading = reading(500, 5.0);
        ExposureLedger exposure = ExposureLedger.of(reading, CLOSES);
        CashLedger cash = new CashLedger(reading.cash());
        RainbowLiveLiveBlock first = new RainbowLiveLiveBlock();
        LiveSizing.of("BTC", reading, cash, 100, new LiveModulation(band, limits, exposure)).apply(first, buy(100));
        assertEquals(RainbowLiveAction.BUY, first.getActionType());
        assertEquals(60.0, exposure.exposurePct(), 1e-9);
    }

    @Test
    @DisplayName("Journal JSON : termes, exposition avant/après, paramètres admin et montant final")
    void journalContent() {
        ModulationSettings settings = new ModulationSettings(30, 90, 1, 0);
        RainbowLiveLiveBlock block = size(reading(500, 5.0), settings, buy(100), 100);
        String journal = block.getModulation();
        for (String key : new String[]{"signalBuyUsd", "power", "bias", "bandMin", "bandMax", "exposureBefore", "exposureAfter",
                "bandBuy", "productMax", "marginPct", "sellBoostMax", "hardCap", "finalAmountUsd", "action"}) {
            assertTrue(journal.contains("\"" + key + "\""), key);
        }
    }
}
