package io.github.xiaomibutton.remapper.backscreen.notifications

import android.content.Context
import io.github.xiaomibutton.remapper.R

/** The made-up message that the Notifications tab's preview and Try on back screen show. */
object SampleNotification {
    /** Not a real app: it has an icon of its own ([R.drawable.rear_ic_sample_chat]). */
    const val PACKAGE = "io.github.xiaomibutton.remapper.backscreen.sample"

    fun make(context: Context) = RearNotification(
        key = "sample",
        packageName = PACKAGE,
        appName = context.getString(R.string.rear_sample_app),
        postedAt = System.currentTimeMillis(),
        content = NoteContent(context.getString(R.string.rear_sample_title), context.getString(R.string.rear_sample_text)),
    )
}
