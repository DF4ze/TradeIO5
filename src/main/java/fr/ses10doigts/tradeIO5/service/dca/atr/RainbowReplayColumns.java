package fr.ses10doigts.tradeIO5.service.dca.atr;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay.DayRow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Colonnes d'un rejeu jour par jour ({@link DayRow}), dans l'ordre et sous les noms attendus par le gabarit
 * {@code tools/rainbow-replay/replay-template.html} : source unique du rejeu en ligne de commande
 * ({@code RainbowAtrReplayMain}) et de la simulation de l'Espace Expert (le graphique n'a ainsi rien à retraduire).
 */
public final class RainbowReplayColumns {

    /** Colonnes exportées (nom → extracteur), dans l'ordre du CSV. */
    public static final Map<String, Function<DayRow, Object>> COLS;

    static {
        Map<String, Function<DayRow, Object>> c = new LinkedHashMap<>();
        c.put("o", DayRow::open);
        c.put("h", DayRow::high);
        c.put("l", DayRow::low);
        c.put("c", DayRow::close);
        c.put("set", DayRow::set);
        c.put("sma", r -> r.band().sma());
        c.put("atr", r -> r.band().atr());
        c.put("down2", r -> r.band().down2());
        c.put("down1", r -> r.band().down1());
        c.put("up1", r -> r.band().up1());
        c.put("up2", r -> r.band().up2());
        c.put("up3", r -> r.band().up3());
        c.put("zone", r -> r.band().zone());
        c.put("ath", DayRow::ath);
        c.put("athDist", DayRow::athDistance);
        c.put("buyFac", DayRow::buyAthFactor);
        c.put("sellFac", DayRow::sellAthFactor);
        c.put("moon", r -> r.moonMode() ? 1 : 0);
        c.put("moonEntry", r -> r.moonEntry() ? 1 : 0);
        c.put("buyKind", r -> r.buyKind().ordinal());
        c.put("multZone", DayRow::multZone);
        c.put("buyAmt", DayRow::buyAmount);
        c.put("buyQty", DayRow::buyQuantity);
        c.put("sellKind", r -> r.sellKind().ordinal());
        c.put("sellFrac", DayRow::sellFraction);
        c.put("sellQty", DayRow::sellQuantity);
        c.put("sellProc", DayRow::sellProceeds);
        c.put("buyArmNow", r -> r.buyArmedNow() ? 1 : 0);
        c.put("sellArmNow", r -> r.sellArmedNow() ? 1 : 0);
        c.put("buyArmed", r -> r.buyArmed() ? 1 : 0);
        c.put("sellArmed", r -> r.sellArmed() ? 1 : 0);
        c.put("locked", r -> r.buyLocked() ? 1 : 0);
        c.put("cooldown", DayRow::cooldown);
        c.put("reserve", DayRow::reserveQty);
        c.put("pos", DayRow::position);
        c.put("cost", DayRow::costBasis);
        c.put("invested", DayRow::invested);
        c.put("proceeds", DayRow::saleProceeds);
        c.put("realized", DayRow::realizedGain);
        c.put("potential", DayRow::potentialGain);
        c.put("total", DayRow::totalGain);
        c.put("fixedGain", DayRow::fixedGain);
        COLS = Collections.unmodifiableMap(c);
    }

    private RainbowReplayColumns() {
    }
}
