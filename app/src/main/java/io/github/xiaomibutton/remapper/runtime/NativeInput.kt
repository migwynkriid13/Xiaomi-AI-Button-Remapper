package io.github.xiaomibutton.remapper.runtime

/** Loaded strictly inside the dedicated Shizuku UserService process. */
internal object NativeInput {
    init { System.loadLibrary("remap_input") }

    external fun run(intercept: Boolean, callback: NativeCallbacks): String
    external fun stop()
    external fun renew()
}

internal interface NativeCallbacks {
    fun onStatus(message: String)
    fun onKey(value: Int, uptimeMillis: Long)
}
