package fr.ses10doigts.tradeIO5.service.market.dataset.time;

import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZonedDateTime;

@Component
public class TimeFrameConverter {

    public int convertLimitToBase(
            int limit,
            TimeFrame limitTf,
            TimeFrame baseTf,
            Instant anchor
    ) {
        int factor = contextualFactorBackward(limitTf, baseTf, anchor);
        return limit * factor;
    }

    private int contextualFactorBackward(
            TimeFrame from,
            TimeFrame to,
            Instant anchor
    ) {
        ZonedDateTime end = anchor.atZone(TimeFrame.DEFAULT_ZONE);
        ZonedDateTime start = end.minus(from.getAmount(), from.getUnit());

        int count = 0;
        ZonedDateTime cursor = end;

        while (cursor.isAfter(start)) {
            cursor = cursor.minus(to.getAmount(), to.getUnit());
            if (!cursor.isBefore(start)) {
                count++;
            }
        }

        if (count == 0) {
            throw new IllegalArgumentException(
                    "Base TimeFrame is coarser than limit TimeFrame: " + from + " -> " + to
            );
        }

        return count;
    }
}
