package fr.ses10doigts.tradeIO5.service.dca.atr;

import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Parité Java/Python du moteur {@link RainbowAtrEngine} : rejoue les cas de
 * {@code target/rainbow-atr/parity_cases.csv} (générés par {@code pine_port.py}, port Python
 * indépendant du pine) et écrit {@code parity_java.csv}. Usage : {@code java ... RainbowAtrParityMain <dir>}.
 */
public final class RainbowAtrParityMain {

    private RainbowAtrParityMain() {
    }

    static ReentryMode mode(String s) {
        return switch (s) { case "T" -> ReentryMode.TRAILING_STOP; case "I" -> ReentryMode.IMMEDIATE; default -> ReentryMode.FIXED_DELAY; };
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        List<String> rows = Files.readAllLines(dir.resolve("d1.csv"));
        int n = rows.size();
        long[] t = new long[n]; double[] h = new double[n]; double[] l = new double[n]; double[] c = new double[n];
        for (int i = 0; i < n; i++) {
            String[] p = rows.get(i).split(",");
            t[i] = Long.parseLong(p[0]); h[i] = Double.parseDouble(p[1]); l[i] = Double.parseDouble(p[2]); c[i] = Double.parseDouble(p[3]);
        }
        RainbowAtrDataset ds = new RainbowAtrDataset(t, h, l, c);
        StringBuilder out = new StringBuilder();
        for (String line : Files.readAllLines(dir.resolve("parity_cases.csv"))) {
            String[] p = line.split(",");
            RainbowAtrTuning tu = new RainbowAtrTuning(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                    Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]), Double.parseDouble(p[5]), Double.parseDouble(p[6]),
                    mode(p[7]), mode(p[8]), Double.parseDouble(p[9]), Double.parseDouble(p[10]),
                    Integer.parseInt(p[11]), Integer.parseInt(p[12]), Double.parseDouble(p[13]),
                    Boolean.parseBoolean(p[14]), Boolean.parseBoolean(p[15]), Boolean.parseBoolean(p[16]));
            RainbowAtrGlobals g = new RainbowAtrGlobals(Boolean.parseBoolean(p[17]), Double.parseDouble(p[18]), Double.parseDouble(p[19]),
                    Double.parseDouble(p[20]), Double.parseDouble(p[21]), Double.parseDouble(p[22]), Double.parseDouble(p[23]),
                    Boolean.parseBoolean(p[24]), Double.parseDouble(p[25]), Boolean.parseBoolean(p[26]), Double.parseDouble(p[27]), Double.parseDouble(p[28]),
                    2.0, 1.0, 0.5, 3.0, 1.0);
            RainbowAtrResult r = RainbowAtrEngine.simulate(ds, g, tu, Integer.parseInt(p[29]), Integer.parseInt(p[30]));
            out.append(String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%d,%d%n",
                    r.invested(), r.saleProceeds(), r.currentValue(), r.realizedGain(), r.costBasis(), r.position(),
                    r.zoneBuys(), r.triggeredBuys(), r.sells(), r.moonStops()));
        }
        Files.writeString(dir.resolve("parity_java.csv"), out.toString());
    }
}
