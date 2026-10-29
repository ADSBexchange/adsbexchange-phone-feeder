package com.adsbexchange.feeder

import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.adsbexchange.feeder.data.RtlSdrDevices
import com.adsbexchange.feeder.data.UsbAttach
import com.adsbexchange.feeder.service.FeederService
import com.adsbexchange.feeder.ui.AppRoot
import com.adsbexchange.feeder.ui.MainViewModel
import com.adsbexchange.feeder.ui.theme.FeederTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark (like adsbexchange.com): light status/navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { FeederTheme { AppRoot(vm) } }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.onAppVisible()
    }

    override fun onPause() {
        vm.onAppHidden()
        super.onPause()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (BuildConfig.DEBUG) {
            // Smoke tests (tools/smoke-test.ps1) drive the app with these extras via `am start`.
            if (intent.getBooleanExtra(EXTRA_SMOKE_SKIP_ONBOARDING, false)) vm.finishOnboarding("")
            if (intent.hasExtra(FeederService.EXTRA_SMOKE_IFILE)) {
                FeederService.start(this, intent.extras)
                return
            }
        }
        // Launched because an SDR was plugged in; Android granted USB permission with the launch.
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && isSdrAttachLaunch(intent)) vm.onSdrAttached()
    }

    /**
     * The activity is exported (it's the launcher), so any app can send it this action, which would
     * undo the user's Stop. Act only on what a launch by Android guarantees: the intent names a
     * device that is attached right now, is a known RTL-SDR, and that we already have permission for.
     * Any app can list attached devices, though, and so name the real one; so also act only once per
     * physical plug-in ([UsbAttach]).
     */
    private fun isSdrAttachLaunch(intent: Intent): Boolean {
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return false
        val attached = getSystemService(UsbManager::class.java)?.deviceList?.values?.any { it.deviceName == device.deviceName } == true
        if (!attached || !RtlSdrDevices.isRtlSdr(this, device) || !RtlSdrDevices.hasPermission(this, device)) return false
        val key = UsbAttach.key(UsbAttach.bootId(this), device.deviceId)
        if (!UsbAttach.isNew(key, UsbAttach.lastActedOn(this))) return false
        UsbAttach.actedOn(this, key)
        return true
    }

    private companion object {
        const val EXTRA_SMOKE_SKIP_ONBOARDING = "smoke_skip_onboarding"
    }
}
