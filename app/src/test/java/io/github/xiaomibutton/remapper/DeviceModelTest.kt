package io.github.xiaomibutton.remapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceModelTest {
    @Test fun recognizesProModelIdsAndName() {
        for (model in listOf("2609BPN61G", "  m610bb ", "Xiaomi 18 Pro")) {
            assertEquals("Xiaomi 18 Pro", xiaomi18ModelLabel(model, ""))
        }
    }

    @Test fun recognizesProMaxModelIdsAndName() {
        for (model in listOf("2611FPNFAG", "2611FPNFAR", "M154FF", "Xiaomi 18 Pro Max")) {
            assertEquals("Xiaomi 18 Pro Max", xiaomi18ModelLabel(model, ""))
        }
    }

    @Test fun recognizesDeviceCodenamesWhenModelIsUnrecognized() {
        assertEquals("Xiaomi 18 Pro", xiaomi18ModelLabel("unknown", "hongkong"))
        assertEquals("Xiaomi 18 Pro Max", xiaomi18ModelLabel("unknown", "madrid_global"))
    }

    @Test fun leavesUnrecognizedDevicesUnlabeled() {
        assertNull(xiaomi18ModelLabel("Android SDK built for x86_64", "emu64x"))
        assertNull(xiaomi18ModelLabel("Xiaomi 18 Fold", "lhasa"))
    }

    @Test fun identifiesRearScreenOnlyModelsByModelOrCodename() {
        assertEquals("Xiaomi 17 Pro", xiaomi17ModelLabel("25098PN5AC", ""))
        assertEquals("Xiaomi 17 Pro", xiaomi17ModelLabel("unknown", "pandora"))
        assertEquals("Xiaomi 17 Pro Max", xiaomi17ModelLabel("2509FPN0BC", ""))
        assertEquals("Xiaomi 17 Pro Max", xiaomi17ModelLabel("unknown", "popsicle_global"))
        assertNull(xiaomi17ModelLabel("2609BPN61G", "hongkong"))
        assertNull(xiaomi18ModelLabel("2509FPN0BC", "popsicle"))
    }
}
