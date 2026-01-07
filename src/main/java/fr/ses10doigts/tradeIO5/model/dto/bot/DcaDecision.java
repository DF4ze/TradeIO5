package fr.ses10doigts.tradeIO5.model.dto.bot;

import fr.ses10doigts.tradeIO5.model.enumerate.bot.Action;
import fr.ses10doigts.tradeIO5.model.enumerate.bot.QuantityMode;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DcaDecision {

    private DcaSettings settings;

    private Action action;
    private QuantityMode quantityMode;
    private BigDecimal quantity;     // valeur ou % selon quantityMode
    private BigDecimal coefficient;  // utile si UNIT_COEFF
    private String source;           // optionnel : ex. "spot", "futures", "margin"

}
