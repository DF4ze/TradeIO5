package fr.ses10doigts.tradeIO5.service.dca.atr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZigZagTest {

    @Test
    void pivotsAlternesEtDernierExtremeNonConfirme() {
        double[] c = {100, 150, 100, 160, 100, 105};
        List<int[]> p = ZigZag.pivots(c, 0, c.length - 1, 0.20);
        assertEquals(4, p.size());
        assertArrayEquals(new int[]{0, ZigZag.LOW}, p.get(0));
        assertArrayEquals(new int[]{1, ZigZag.HIGH}, p.get(1));
        assertArrayEquals(new int[]{2, ZigZag.LOW}, p.get(2));
        assertArrayEquals(new int[]{3, ZigZag.HIGH}, p.get(3));
    }

    @Test
    void seuilPlusGrandIgnoreLesPetitsRetournements() {
        double[] c = {100, 150, 100, 160, 100};
        assertTrue(ZigZag.pivots(c, 0, c.length - 1, 0.80).isEmpty());
    }
}
