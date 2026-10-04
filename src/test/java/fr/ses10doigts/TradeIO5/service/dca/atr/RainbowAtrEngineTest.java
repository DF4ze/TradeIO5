package fr.ses10doigts.tradeIO5.service.dca.atr;

import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RainbowAtrEngine — fidélité au pine rainbow_dca_v4_atr_moon.pine")
class RainbowAtrEngineTest {

    private static RainbowAtrDataset dataset(double[] close, double[] high, double[] low) {
        long[] t = new long[close.length];
        for (int i = 0; i < t.length; i++) { t[i] = i; }
        return new RainbowAtrDataset(t, high, low, close);
    }

    @Test
    @DisplayName("ATR = RMA Pine : TR[0]=high-low, amorçage SMA des period premiers TR, NaN avant")
    void atrSeedsLikePineRma() {
        double[] close = {10, 10, 10, 10};
        double[] high = {11, 12, 11, 11};
        double[] low = {9, 9, 9, 8};
        RainbowAtrDataset ds = dataset(close, high, low);
        double[] atr = ds.atr(2);
        assertTrue(Double.isNaN(atr[0]));
        assertEquals(2.5, atr[1], 1e-12);          // (2+3)/2
        assertEquals(2.25, atr[2], 1e-12);         // (2.5*1+2)/2
        assertEquals(2.625, atr[3], 1e-12);        // (2.25*1+3)/2
        double[] sma = ds.sma(2);
        assertTrue(Double.isNaN(sma[0]));
        assertEquals(10.0, sma[1], 1e-12);
    }

    @Test
    @DisplayName("To the moon : entrée sur 1re clôture > ATH précédent, pas de test de sortie à l'entrée, sortie au trailing depuis le pic")
    void moonFlags() {
        double[] c = {10, 11, 12, 13, 12, 11, 10};
        RainbowAtrDataset ds = dataset(c, c, c);
        RainbowAtrDataset.MoonFlags f = ds.moon(true, 15);
        assertArrayEquals(new boolean[]{false, true, false, false, false, false, false}, f.entry());
        assertArrayEquals(new boolean[]{false, false, false, false, false, true, false}, f.exit());
        assertArrayEquals(new boolean[]{false, true, true, true, true, false, false}, f.mode());
        RainbowAtrDataset.MoonFlags off = ds.moon(false, 15);
        assertFalse(off.mode()[1]);
        assertFalse(off.entry()[1]);
    }

    @Test
    @DisplayName("Zone X1 : un achat x1 par bougie rejouée, ATH/moon désactivés")
    void flatPriceBuysEveryBarInZoneX1() {
        int n = 10;
        double[] c = new double[n]; double[] h = new double[n]; double[] l = new double[n];
        for (int i = 0; i < n; i++) { c[i] = 100; h[i] = 101; l[i] = 99; }
        RainbowAtrDataset ds = dataset(c, h, l);
        RainbowAtrTuning t = new RainbowAtrTuning(3, 3, 2.0, 1.0, 1.0, 2.0, 3.0,
                ReentryMode.TRAILING_STOP, ReentryMode.TRAILING_STOP, 3, 5, 10, 15, 0.2, false, true, true);
        RainbowAtrGlobals g = RainbowAtrGlobals.pineDefault().toBuilder().set("athOn", false).set("moonOn", false).build();
        int start = RainbowAtrDataset.warmup(3, 3);
        RainbowAtrResult r = RainbowAtrEngine.simulate(ds, g, t, start, n - 1);
        assertEquals(8, r.bars());
        assertEquals(8.0, r.invested(), 1e-12);
        assertEquals(8, r.zoneBuys());
        assertEquals(RainbowAtrEngine.X1, r.lastZone());
        assertEquals(0.08, r.position(), 1e-12);
        assertEquals(r.fixedInvested(), r.invested(), 1e-12);
    }

    /**
     * Golden : séries synthétiques déterministes, résultats calculés par le port Python indépendant du pine
     * ({@code target/rainbow-atr/pine_port.py}). Chaque cas combine ATH, moon (réserve, cliquet, stop),
     * verrou DOWN2, cooldown, vente pendant cooldown, modes de réentrée. Colonnes :
     * invested, saleProceeds, currentValue, realized, costBasis, position, zoneBuys, triggeredBuys, sells, moonStops.
     */
    @Test
    @DisplayName("Parité golden Python/Java sur 6 jeux de paramètres (moon, ATH, verrou, cooldown, modes)")
    void goldenParityWithPythonPort() {
        int n = 1500;
        double[] c = new double[n]; double[] h = new double[n]; double[] l = new double[n];
        for (int i = 0; i < n; i++) {
            c[i] = 100 * Math.exp(0.6 * Math.sin(i / 53.0) + 0.25 * Math.sin(i / 9.0) + 0.0015 * i);
            h[i] = c[i] * (1.01 + 0.005 * Math.abs(Math.sin(i * 1.7)));
            l[i] = c[i] * (0.99 - 0.003 * Math.abs(Math.cos(i * 0.9)));
        }
        RainbowAtrDataset ds = dataset(c, h, l);
        String[][] cases = {
                {"10,21,3,1,0.5,0.5,3,F,I,3,2,12,10,0.05,True,False,True,False,30,45,1,3,1,1,True,25,True,30,50,284,784",
                        "140.000000,115.988041,233.013524,74.386583,98.398542,0.539343,77,7,7,2"},
                {"50,14,4,0.5,2,2,2,F,I,10,10,0,5,0.1,False,True,True,False,30,45,1,3,2,0.25,True,0,False,30,50,222,522",
                        "71.000000,31.165203,89.551992,19.248683,59.083480,0.408783,21,14,5,0"},
                {"14,14,1,1,0.5,2,3,F,I,5,10,1,3,0.2,True,False,True,True,30,30,0.5,3,1,0.25,True,25,True,30,50,143,443",
                        "300.000000,441.662976,392.453597,305.808143,164.145167,1.482179,19,35,5,1"},
                {"50,28,2,2,0,1,3,I,T,2,10,12,3,0.05,True,False,True,False,60,45,1,3,1,1,False,25,True,10,100,204,504",
                        "14.000000,8.144592,15.383349,5.022502,10.877910,0.086573,7,3,8,0"},
                {"26,21,2,2,0.5,3,3,T,F,5,2,1,10,0.1,True,True,False,True,30,30,1,1,3,0.25,True,75,True,15,100,280,580",
                        "36.500000,28.978705,19.369034,11.364117,18.885412,0.115084,30,5,10,2"},
                {"20,7,3,0.5,1,3,6,F,T,10,5,12,10,1.0,False,True,True,True,30,30,1,1,1,0.5,True,75,True,15,50,586,1086",
                        "81.000000,148.425255,38.445096,78.582237,11.156982,0.032601,34,14,6,3"},
        };
        for (String[] cs : cases) {
            String[] p = cs[0].split(",");
            RainbowAtrTuning tu = new RainbowAtrTuning(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                    Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]), Double.parseDouble(p[5]), Double.parseDouble(p[6]),
                    mode(p[7]), mode(p[8]), Double.parseDouble(p[9]), Double.parseDouble(p[10]),
                    Integer.parseInt(p[11]), Integer.parseInt(p[12]), Double.parseDouble(p[13]),
                    bool(p[14]), bool(p[15]), bool(p[16]));
            RainbowAtrGlobals g = new RainbowAtrGlobals(bool(p[17]), Double.parseDouble(p[18]), Double.parseDouble(p[19]),
                    Double.parseDouble(p[20]), Double.parseDouble(p[21]), Double.parseDouble(p[22]), Double.parseDouble(p[23]),
                    bool(p[24]), Double.parseDouble(p[25]), bool(p[26]), Double.parseDouble(p[27]), Double.parseDouble(p[28]),
                    2.0, 1.0, 0.5, 3.0, 1.0);
            RainbowAtrResult r = RainbowAtrEngine.simulate(ds, g, tu, Integer.parseInt(p[29]), Integer.parseInt(p[30]));
            String[] e = cs[1].split(",");
            String msg = cs[0];
            assertEquals(Double.parseDouble(e[0]), r.invested(), 1e-4, msg);
            assertEquals(Double.parseDouble(e[1]), r.saleProceeds(), 1e-4, msg);
            assertEquals(Double.parseDouble(e[2]), r.currentValue(), 1e-4, msg);
            assertEquals(Double.parseDouble(e[3]), r.realizedGain(), 1e-4, msg);
            assertEquals(Double.parseDouble(e[4]), r.costBasis(), 1e-4, msg);
            assertEquals(Double.parseDouble(e[5]), r.position(), 1e-5, msg);
            assertEquals(Integer.parseInt(e[6]), r.zoneBuys(), msg);
            assertEquals(Integer.parseInt(e[7]), r.triggeredBuys(), msg);
            assertEquals(Integer.parseInt(e[8]), r.sells(), msg);
            assertEquals(Integer.parseInt(e[9]), r.moonStops(), msg);
        }
    }

    @Test
    @DisplayName("Régime : l'état de la machine traverse les changements de tuning, un seul tuning == mode classique")
    void singleTuningEqualsRegimeArrayWithConstantRegime() {
        int n = 400;
        double[] c = new double[n]; double[] h = new double[n]; double[] l = new double[n];
        for (int i = 0; i < n; i++) {
            c[i] = 100 * Math.exp(0.5 * Math.sin(i / 40.0) + 0.002 * i);
            h[i] = c[i] * 1.01; l[i] = c[i] * 0.99;
        }
        RainbowAtrDataset ds = dataset(c, h, l);
        RainbowAtrTuning t = RainbowAtrTuning.pineFinalCandidate();
        RainbowAtrGlobals g = RainbowAtrGlobals.pineDefault();
        int[] regimes = new int[n];
        RainbowAtrResult a = RainbowAtrEngine.simulate(ds, g, t, 60, 399);
        RainbowAtrResult b = RainbowAtrEngine.simulate(ds, g, new RainbowAtrTuning[]{t, t, t}, regimes, 60, 399, false);
        assertEquals(a.invested(), b.invested(), 1e-12);
        assertEquals(a.realizedGain(), b.realizedGain(), 1e-12);
        assertEquals(a.position(), b.position(), 1e-12);
    }

    private static boolean bool(String s) { return "True".equals(s); }

    private static ReentryMode mode(String s) {
        return switch (s) { case "T" -> ReentryMode.TRAILING_STOP; case "I" -> ReentryMode.IMMEDIATE; default -> ReentryMode.FIXED_DELAY; };
    }
}
