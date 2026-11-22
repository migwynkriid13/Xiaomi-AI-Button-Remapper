package io.github.xiaomibutton.remapper

import io.github.xiaomibutton.remapper.runtime.UiActions
import org.junit.Assert.assertThrows
import org.junit.Test

class InteractionActionsTest {
    @Test fun selectorsAllowOneExactTargetAndAnExplicitOperation() {
        UiActions.validateElement("""{"package":"com.example.app","id":"com.example.app:id/next","action":"click"}""")
        UiActions.validateElement("""{"package":"com.example.app","text":"Next step","action":"focus"}""")
        UiActions.validateElement("""{"package":"com.example.app","id":"android:id/button1","action":"select"}""")
        for (invalid in listOf(
            """{"package":"com.example.app","id":"com.other:id/next","action":"click"}""",
            """{"package":"com.example.app","id":"com.example.app:id/*","action":"click"}""",
            """{"package":"com.example.app","action":"click"}""",
            """{"package":"com.example.app","text":"Next","id":"com.example.app:id/next","action":"click"}""",
            """{"package":"com.example.app","text":"","action":"click"}""",
            """{"package":"com.example.app","text":"Next","action":"unrecognized"}""",
            """{"package":"com.example.app","text":"Next","action":"click"}{}"""
        )) assertThrows(RuntimeException::class.java) { UiActions.validateElement(invalid) }
    }

    @Test fun gestureCoordinatesAndDurationHaveFiniteBounds() {
        InteractionActions.validate("input.tap", "0,100")
        InteractionActions.validate("input.swipe", "0,0,100,100,2000")
        InteractionActions.validate("input.pinch", "50,50,5,10,30")
        for ((id, args) in listOf(
            "input.tap" to "NaN,2", "input.tap" to "0,101", "input.tap" to "0,0,0",
            "input.swipe" to "5,5", "input.pinch" to "50,50,20,20", "input.pinch" to "50,50,0,20"
        )) assertThrows(IllegalArgumentException::class.java) { InteractionActions.validate(id, args) }
    }
}
