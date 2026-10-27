package app.mise.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScalingTest {
    private fun t(qty: Double?, unit: Measure, base: Int, serv: Int) = Scaling.scaledText(qty, unit, base, serv)

    @Test fun riceForSix() = assertEquals("750 g", t(500.0, Measure.G, 4, 6))
    @Test fun riceBackToFour() = assertEquals("500 g", t(500.0, Measure.G, 4, 4))
    @Test fun gramsBecomeKilos() = assertEquals("1.5 kg", t(1000.0, Measure.G, 4, 6))
    @Test fun kilosStayKilos() = assertEquals("1.5 kg", t(1.0, Measure.KG, 4, 6))
    @Test fun kilosBecomeGrams() = assertEquals("500 g", t(1.0, Measure.KG, 4, 2))
    @Test fun prawnsForSix() = assertEquals("900 g", t(600.0, Measure.G, 4, 6))
    @Test fun spoonsAsFractions() = assertEquals("1½ tbsp", t(1.0, Measure.TBSP, 4, 6))
    @Test fun piecesToHalves() = assertEquals("4½ pc", t(3.0, Measure.PC, 4, 6))
    @Test fun pinchNeverZero() = assertEquals("1 pinch", t(1.0, Measure.PINCH, 4, 1))
    @Test fun toTaste() = assertEquals("to taste", t(null, Measure.TO_TASTE, 4, 6))
    @Test fun millilitresToLitres() = assertEquals("1.2 l", t(800.0, Measure.ML, 4, 6))

    @Test fun parsesDecimalsAndFractions() {
        assertEquals(1.5, Scaling.parse("1.5")!!, 1e-9)
        assertEquals(1.5, Scaling.parse("1,5")!!, 1e-9)
        assertEquals(0.5, Scaling.parse("1/2")!!, 1e-9)
        assertEquals(1.5, Scaling.parse("1 1/2")!!, 1e-9)
        assertEquals(0.5, Scaling.parse("½")!!, 1e-9)
        assertNull(Scaling.parse(""))
        assertNull(Scaling.parse("abc"))
    }
}
