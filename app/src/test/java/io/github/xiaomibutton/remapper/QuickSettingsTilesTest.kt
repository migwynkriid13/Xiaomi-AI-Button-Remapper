package io.github.xiaomibutton.remapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QuickSettingsTilesTest {
    @Test fun canonicalComponentsProduceSystemUiSpecs() {
        val short = QuickSettingsTiles.parse("com.example/.QuickTile")
        assertEquals("com.example", short.packageName)
        assertEquals("com.example.QuickTile", short.className)
        assertEquals("custom(com.example/.QuickTile)", short.systemSpec)

        val external = QuickSettingsTiles.parse("com.example/com.vendor.tiles.PowerTile")
        assertEquals("com.vendor.tiles.PowerTile", external.className)
        assertEquals("custom(com.example/com.vendor.tiles.PowerTile)", external.systemSpec)
    }

    @Test fun shellSyntaxAndNonCanonicalComponentsAreRejected() {
        for (value in listOf("", "com.example", "com.example/.Tile extra", "com.example/.Tile;id",
            "com.example/com.example.Tile", "-p/.Tile", "com.example//.Tile", "com.example/")) {
            assertThrows(value, IllegalArgumentException::class.java) { QuickSettingsTiles.parse(value) }
        }
    }
}
