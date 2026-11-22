package io.github.xiaomibutton.remapper

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Optional explicit enrollment; the policy XML requests force-lock only. */
class DeviceAdminLock : DeviceAdminReceiver() {
    companion object {
        private fun component(context: Context) = ComponentName(context, DeviceAdminLock::class.java)
        fun available(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(component(context)) == true

        fun enroll(context: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.lock_admin_description))

        fun request(context: Context) { context.startActivity(enroll(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

        fun apply(context: Context): String {
            check(available(context)) { "Enable Device admin (lock only) for this mapping" }
            checkNotNull(context.getSystemService(DevicePolicyManager::class.java)).lockNow()
            return "Android screen lock requested"
        }
    }
}
