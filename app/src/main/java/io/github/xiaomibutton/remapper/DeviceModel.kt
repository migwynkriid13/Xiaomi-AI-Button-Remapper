package io.github.xiaomibutton.remapper

import java.util.Locale

private const val PRO = "Xiaomi 18 Pro"
private const val PRO_MAX = "Xiaomi 18 Pro Max"

internal fun xiaomi18ModelLabel(model: String, device: String): String? =
    when (model.trim().uppercase(Locale.ROOT)) {
        "2609BPN61G", "M610BB", "XIAOMI 18 PRO", "18 PRO" -> PRO
        "2611FPNFAG", "2611FPNFAR", "M154FF", "XIAOMI 18 PRO MAX", "18 PRO MAX" -> PRO_MAX
        else -> when (device.trim().substringBefore('_').lowercase(Locale.ROOT)) {
            "hongkong" -> PRO
            "madrid" -> PRO_MAX
            else -> null
        }
    }
