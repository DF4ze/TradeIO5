package fr.ses10doigts.tradeIO5.model.dto.portfolio;

import java.time.LocalDate;
import java.util.List;

/**
 * Bandes Rainbow figées d'un lien : valeurs réellement vues par le moteur à chaque passe (run du preset), un point par jour joué.
 * {@code action} : BUY / SELL quand le moteur a voulu acheter / vendre ce jour-là (achat/vente virtuel, indépendant du wallet), sinon
 * {@code null} ; sur les bandes simulées, rejoué par le moteur sur toute la période affichée (machine à froid au premier jour). {@code simulated=false} : aucun point recalculé. Valeurs nulles (« — ») quand le run ne les porte pas.
 */
public record RainbowBandsDto(Long bindingId, String assetSymbol, String presetName, boolean simulated, List<Point> points) {

    public record Point(LocalDate day, Double close, Double sma, Double atr, Double boundDown2, Double boundDown1, Double boundUp1,
                        Double boundUp2, Double boundUp3, Integer zone, String activeSet, String trendRegime, String action) {
    }
}
