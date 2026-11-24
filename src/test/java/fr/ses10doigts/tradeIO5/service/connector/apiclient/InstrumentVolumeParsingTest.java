package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentListClient.Listed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Volume 24 h (monnaie de cotation) lu par un appel « toutes les paires » par exchange ; illisible ou absent => volume inconnu, jamais d'exception. */
@DisplayName("Clients de liste : volume 24 h en cotation")
class InstrumentVolumeParsingTest {

    private static void assertVolume(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    @DisplayName("OKX : volCcy24h de /market/tickers, associé par BASE-QUOTE")
    void okx() {
        Map<String, BigDecimal> volumes = OkxInstrumentListClient.parseVolumes("""
                {"code":"0","data":[{"instId":"BTC-USDC","volCcy24h":"12345678.9","vol24h":"200"},{"instId":"PAXG-USDT","volCcy24h":"2500000"},{"instId":"X-USDT"}]}""");
        List<Listed> listed = OkxInstrumentListClient.parse("""
                {"code":"0","data":[
                  {"instId":"BTC-USDC","baseCcy":"BTC","quoteCcy":"USDC","state":"live","minSz":"0.00001","lotSz":"0.00000001","tickSz":"0.1"},
                  {"instId":"SOL-USDT","baseCcy":"SOL","quoteCcy":"USDT","state":"live","minSz":"0.01","lotSz":"0.01","tickSz":"0.01"}]}""", volumes);

        assertVolume("12345678.9", listed.getFirst().volume24hQuote());
        assertNull(listed.get(1).volume24hQuote());
        assertEquals(2, volumes.size());
    }

    @Test
    @DisplayName("Kraken : volume base 24 h (v[1]) × VWAP 24 h (p[1]) ; clé = nom de paire AssetPairs, repli sur l'altname")
    void kraken() {
        Map<String, BigDecimal> volumes = KrakenInstrumentListClient.parseVolumes("""
                {"error":[],"result":{
                  "XXBTZUSD":{"v":["10","100"],"p":["60000","50000"]},
                  "PAXGUSD":{"v":["1","20"],"p":["3000","2500"]},
                  "BROKEN":{"v":["1"]}}}""");
        List<Listed> listed = KrakenInstrumentListClient.parse("""
                {"error":[],"result":{
                  "XXBTZUSD":{"altname":"XBTUSD","base":"XXBT","quote":"ZUSD","lot_decimals":8,"pair_decimals":1,"ordermin":"0.0001","status":"online"},
                  "PAXGUSD":{"altname":"PAXGUSD","base":"PAXG","quote":"ZUSD","lot_decimals":8,"pair_decimals":2,"ordermin":"0.003","status":"online"},
                  "NEWUSD":{"altname":"NEWUSD","base":"NEW","quote":"ZUSD","lot_decimals":4,"pair_decimals":2,"ordermin":"1","status":"online"}}}""", volumes);

        assertVolume("5000000", listed.getFirst().volume24hQuote());
        assertVolume("50000", listed.get(1).volume24hQuote());
        assertNull(listed.get(2).volume24hQuote());
    }

    @Test
    @DisplayName("Kraken : Ticker en erreur ou illisible => aucun volume, catalogue intact")
    void krakenTickerFailure() {
        assertTrue(KrakenInstrumentListClient.parseVolumes("{\"error\":[\"EGeneral:Too many requests\"],\"result\":{}}").isEmpty());
        assertTrue(KrakenInstrumentListClient.parseVolumes("<html>").isEmpty());
        assertTrue(KrakenInstrumentListClient.parseVolumes(null).isEmpty());
    }

    @Test
    @DisplayName("Gate EU : quote_volume de /spot/tickers par identifiant Gate (BTC_EUR)")
    void gateEu() {
        Map<String, BigDecimal> volumes = GateEuInstrumentListClient.parseVolumes("""
                [{"currency_pair":"BTC_USDT","quote_volume":"987654.32","base_volume":"10"},{"currency_pair":"ETH_USDT"}]""");
        List<Listed> listed = GateEuInstrumentListClient.parse("""
                [{"id":"BTC_USDT","base":"BTC","quote":"USDT","trade_status":"tradable","amount_precision":4,"precision":2},
                 {"id":"ETH_USDT","base":"ETH","quote":"USDT","trade_status":"tradable","amount_precision":4,"precision":2}]""", volumes);

        assertVolume("987654.32", listed.getFirst().volume24hQuote());
        assertNull(listed.get(1).volume24hQuote());
        assertTrue(GateEuInstrumentListClient.parseVolumes("oups").isEmpty());
    }

    @Test
    @DisplayName("Bitvavo : volumeQuote de /ticker/24h par marché (BTC-EUR)")
    void bitvavo() {
        Map<String, BigDecimal> volumes = BitvavoInstrumentListClient.parseVolumes("""
                [{"market":"BTC-EUR","volume":"3","volumeQuote":"150000.5"},{"market":"ETH-EUR","volume":"1"}]""");
        List<Listed> listed = BitvavoInstrumentListClient.parse("""
                [{"market":"BTC-EUR","status":"trading","base":"BTC","quote":"EUR","tickSize":"1","quantityDecimals":8,"minOrderInBaseAsset":"0.0001","minOrderInQuoteAsset":"5"},
                 {"market":"ETH-EUR","status":"trading","base":"ETH","quote":"EUR","tickSize":"0.01","quantityDecimals":8,"minOrderInBaseAsset":"0.001","minOrderInQuoteAsset":"5"}]""", volumes);

        assertVolume("150000.5", listed.getFirst().volume24hQuote());
        assertNull(listed.get(1).volume24hQuote());
        assertTrue(BitvavoInstrumentListClient.parseVolumes("").isEmpty());
    }

    @Test
    @DisplayName("One Trading : quote_volume de /market-ticker par code instrument (BTC_USDC) ; format inattendu => inconnu")
    void oneTrading() {
        Map<String, BigDecimal> volumes = OneTradingInstrumentListClient.parseVolumes("""
                [{"instrument_code":"BTC_USDC","quote_volume":"777000","base_volume":"12"}]""");
        List<Listed> listed = OneTradingInstrumentListClient.parse("""
                {"instruments":[{"id":"BTC_USDC","type":"SPOT","state":"ACTIVE","base":{"code":"BTC"},"quote":{"code":"USDC"},"amount_precision":5,"market_precision":2,"min_size":"10"}]}""", volumes);

        assertVolume("777000", listed.getFirst().volume24hQuote());
        assertTrue(OneTradingInstrumentListClient.parseVolumes("{\"unexpected\":true}").isEmpty());
    }

    @Test
    @DisplayName("Revolut X : aucun volume exposé publiquement => volume nul")
    void revolutX() {
        List<Listed> listed = RevolutXInstrumentListClient.parse("""
                {"data":{"BTC/USDC":{"base":"BTC","quote":"USDC","status":"active","min_order_size":"0.0001","base_step":"0.00000001","quote_step":"0.01","min_order_size_quote":"1"}}}""");

        assertNull(listed.getFirst().volume24hQuote());
    }
}
