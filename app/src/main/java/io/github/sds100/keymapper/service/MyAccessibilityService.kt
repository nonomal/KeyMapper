package io.github.sds100.keymapper.service

import android.accessibilityservice.AccessibilityService
import android.bluetooth.BluetoothDevice
import android.content.*
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.MainThread
import androidx.lifecycle.*
import io.github.sds100.keymapper.Constants.PACKAGE_NAME
import io.github.sds100.keymapper.MyApplication
import io.github.sds100.keymapper.R
import io.github.sds100.keymapper.WidgetsManager
import io.github.sds100.keymapper.WidgetsManager.EVENT_SERVICE_START
import io.github.sds100.keymapper.WidgetsManager.EVENT_SERVICE_STOPPED
import io.github.sds100.keymapper.data.AppPreferences
import io.github.sds100.keymapper.data.model.Action
import io.github.sds100.keymapper.util.*
import io.github.sds100.keymapper.util.delegate.ActionPerformerDelegate
import io.github.sds100.keymapper.util.delegate.KeymapDetectionDelegate
import io.github.sds100.keymapper.util.delegate.KeymapDetectionPreferences
import io.github.sds100.keymapper.util.result.getBriefMessage
import io.github.sds100.keymapper.util.result.isSuccess
import io.github.sds100.keymapper.util.result.onFailure
import io.github.sds100.keymapper.util.result.onSuccess
import kotlinx.coroutines.delay
import splitties.systemservices.vibrator
import splitties.toast.toast
import timber.log.Timber

/**
 * Created by sds100 on 05/04/2020.
 */
class MyAccessibilityService : AccessibilityService(),
    LifecycleOwner,
    SharedPreferences.OnSharedPreferenceChangeListener,
    IClock,
    IPerformAccessibilityAction,
    IActionError,
    IConstraintState {

    companion object {

        const val ACTION_PAUSE_REMAPPINGS = "$PACKAGE_NAME.PAUSE_REMAPPINGS"
        const val ACTION_RESUME_REMAPPINGS = "$PACKAGE_NAME.RESUME_REMAPPINGS"
        const val ACTION_START = "$PACKAGE_NAME.START_ACCESSIBILITY_SERVICE"
        const val ACTION_STOP = "$PACKAGE_NAME.STOP_ACCESSIBILITY_SERVICE"
        const val ACTION_SHOW_KEYBOARD = "$PACKAGE_NAME.SHOW_KEYBOARD"
        const val ACTION_UPDATE_NOTIFICATION = "$PACKAGE_NAME.UPDATE_NOTIFICATION"

        const val EVENT_RECORD_TRIGGER = "record_trigger"
        const val EVENT_RECORD_TRIGGER_KEY = "record_trigger_key"
        const val EVENT_RECORD_TRIGGER_TIMER_INCREMENTED = "record_trigger_timer_incremented"
        const val EVENT_STOP_RECORDING_TRIGGER = "stop_recording_trigger"
        const val EVENT_TEST_ACTION = "test_action"
        const val EVENT_ON_SERVICE_STOPPED = "accessibility_service_stopped"
        const val EVENT_ON_SERVICE_STARTED = "accessibility_service_started"

        private lateinit var BUS: MutableLiveData<Event<Pair<String, Any?>>>

        @MainThread
        fun provideBus(): MutableLiveData<Event<Pair<String, Any?>>> {
            BUS = if (::BUS.isInitialized) BUS else MutableLiveData()

            return BUS
        }

        /**
         * How long should the accessibility service record a trigger in seconds.
         */
        private const val RECORD_TRIGGER_TIMER_LENGTH = 5
    }

    /**
     * Broadcast receiver for all intents sent from within the app.
     */
    private val mBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {

                ACTION_PAUSE_REMAPPINGS -> {
                    mKeymapDetectionDelegate.reset()
                    mPaused = true
                    WidgetsManager.onEvent(this@MyAccessibilityService, WidgetsManager.EVENT_PAUSE_REMAPS)
                }

                ACTION_RESUME_REMAPPINGS -> {
                    mKeymapDetectionDelegate.reset()
                    mPaused = false
                    WidgetsManager.onEvent(this@MyAccessibilityService, WidgetsManager.EVENT_RESUME_REMAPS)
                }

                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return

                    if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                        mConnectedBtAddresses.remove(device.address)
                    } else {
                        mConnectedBtAddresses.add(device.address)
                    }
                }

                ACTION_SHOW_KEYBOARD -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        softKeyboardController.show(baseContext)
                    }
                }

                ACTION_UPDATE_NOTIFICATION -> {
                    if (mPaused) {
                        WidgetsManager.onEvent(this@MyAccessibilityService, WidgetsManager.EVENT_PAUSE_REMAPS)
                    } else {
                        WidgetsManager.onEvent(this@MyAccessibilityService, WidgetsManager.EVENT_RESUME_REMAPS)
                    }
                }
            }
        }
    }

    private var mRecordingTrigger = false

    private var mPaused = false
    private lateinit var mLifecycleRegistry: LifecycleRegistry

    private lateinit var mKeymapDetectionDelegate: KeymapDetectionDelegate
    private lateinit var mActionPerformerDelegate: ActionPerformerDelegate

    override val currentTime: Long
        get() = SystemClock.elapsedRealtime()

    override val currentPackageName: String
        get() = rootInActiveWindow.packageName.toString()

    private val mConnectedBtAddresses = mutableSetOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()

        mLifecycleRegistry = LifecycleRegistry(this)
        mLifecycleRegistry.currentState = Lifecycle.State.STARTED

        val preferences = KeymapDetectionPreferences(
            AppPreferences.longPressDelay,
            AppPreferences.doublePressDelay,
            AppPreferences.holdDownDelay,
            AppPreferences.repeatDelay,
            AppPreferences.sequenceTriggerTimeout,
            AppPreferences.vibrateDuration,
            AppPreferences.forceVibrate
        )

        mKeymapDetectionDelegate = KeymapDetectionDelegate(
            lifecycleScope,
            preferences,
            iClock = this,
            iConstraintState = this,
            iActionError = this)

        mActionPerformerDelegate = ActionPerformerDelegate(
            context = this,
            iPerformAccessibilityAction = this,
            lifecycle = lifecycle)

        IntentFilter().apply {
            addAction(ACTION_PAUSE_REMAPPINGS)
            addAction(ACTION_RESUME_REMAPPINGS)
            addAction(ACTION_UPDATE_NOTIFICATION)
            addAction(ACTION_SHOW_KEYBOARD)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)

            registerReceiver(mBroadcastReceiver, this)
        }

        (application as MyApplication).keymapRepository.keymapList.observe(this) {
            mKeymapDetectionDelegate.keyMapListCache = it
        }

        defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)

        WidgetsManager.onEvent(this, EVENT_SERVICE_START)
        provideBus().value = Event(EVENT_ON_SERVICE_STARTED to null)

        mKeymapDetectionDelegate.imitateButtonPress.observe(this, EventObserver {
            Timber.d("imitate button press")
            when (it.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> AudioUtils.adjustVolume(this, AudioManager.ADJUST_RAISE,
                    showVolumeUi = true)

                KeyEvent.KEYCODE_VOLUME_DOWN -> AudioUtils.adjustVolume(this, AudioManager.ADJUST_LOWER,
                    showVolumeUi = true)

                KeyEvent.KEYCODE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                KeyEvent.KEYCODE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                KeyEvent.KEYCODE_APP_SWITCH -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                KeyEvent.KEYCODE_MENU -> mActionPerformerDelegate.performSystemAction(SystemAction.OPEN_MENU)

                else -> KeyboardUtils.sendDownUpFromImeService(
                    keyCode = it.keyCode,
                    metaState = it.metaState
                )
            }
        })

        mKeymapDetectionDelegate.performAction.observe(this, EventObserver { model ->
            Timber.d("perform action ${model.action.uniqueId}")

            model.action.canBePerformed(this).onSuccess {
                mActionPerformerDelegate.performAction(model)
            }.onFailure {
                if (AppPreferences.showToastOnActionError) {
                    toast(it.getBriefMessage(this))
                }
            }
        })

        mKeymapDetectionDelegate.vibrate.observe(this, EventObserver {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(it, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(it)
            }
        })

        provideBus().observe(this, Observer {
            it ?: return@Observer

            when (it.peekContent().first) {
                EVENT_RECORD_TRIGGER -> {
                    //don't start recording if a trigger is being recorded
                    if (!mRecordingTrigger) {
                        mRecordingTrigger = true

                        lifecycleScope.launchWhenCreated {
                            recordTrigger()
                        }
                    }

                    it.handled()
                }

                EVENT_TEST_ACTION -> {
                    val action = it.getContentIfNotHandled()?.second as Action
                    mActionPerformerDelegate.performAction(action)
                }

                Intent.ACTION_SCREEN_ON -> {
                    mKeymapDetectionDelegate.reset()
                    it.handled()
                }
            }
        })
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()

        mLifecycleRegistry.currentState = Lifecycle.State.DESTROYED

        WidgetsManager.onEvent(this, EVENT_SERVICE_STOPPED)
        provideBus().value = Event(EVENT_ON_SERVICE_STOPPED to null)
        defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        unregisterReceiver(mBroadcastReceiver)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return super.onKeyEvent(event)

        if (mRecordingTrigger) {
            if (event.action == KeyEvent.ACTION_DOWN) {

                //tell the UI that a key has been pressed
                provideBus().value = Event(EVENT_RECORD_TRIGGER_KEY to event)
            }

            return true
        }

        if (!mPaused) {
            try {
                Timber.d(event.toString())
                return mKeymapDetectionDelegate.onKeyEvent(
                    event.keyCode,
                    event.action,
                    event.device.descriptor,
                    event.device.isExternalCompat,
                    event.metaState)
            } catch (e: Exception) {
                Timber.e(e)
            }
        }

        return super.onKeyEvent(event)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            str(R.string.key_pref_long_press_delay) -> {
                mKeymapDetectionDelegate.preferences.defaultLongPressDelay = AppPreferences.longPressDelay
            }

            str(R.string.key_pref_double_press_delay) -> {
                mKeymapDetectionDelegate.preferences.defaultDoublePressDelay = AppPreferences.doublePressDelay
            }

            str(R.string.key_pref_hold_down_delay) -> {
                mKeymapDetectionDelegate.preferences.defaultHoldDownDelay = AppPreferences.holdDownDelay
            }

            str(R.string.key_pref_repeat_delay) -> {
                mKeymapDetectionDelegate.preferences.defaultRepeatDelay = AppPreferences.repeatDelay
            }

            str(R.string.key_pref_sequence_trigger_timeout) -> {
                mKeymapDetectionDelegate.preferences.defaultSequenceTriggerTimeout =
                    AppPreferences.sequenceTriggerTimeout
            }

            str(R.string.key_pref_vibrate_duration) -> {
                mKeymapDetectionDelegate.preferences.defaultVibrateDuration = AppPreferences.vibrateDuration
            }

            str(R.string.key_pref_force_vibrate) -> {
                mKeymapDetectionDelegate.preferences.forceVibrate = AppPreferences.forceVibrate
            }
        }
    }

    override fun isBluetoothDeviceConnected(address: String) = mConnectedBtAddresses.contains(address)

    override fun canActionBePerformed(action: Action) = action.canBePerformed(this).isSuccess

    private suspend fun recordTrigger() {
        repeat(RECORD_TRIGGER_TIMER_LENGTH) { iteration ->
            val timeLeft = RECORD_TRIGGER_TIMER_LENGTH - iteration

            provideBus().value = Event(EVENT_RECORD_TRIGGER_TIMER_INCREMENTED to timeLeft)

            delay(1000)
        }

        provideBus().value = Event(EVENT_STOP_RECORDING_TRIGGER to null)

        mRecordingTrigger = false
    }

    override fun getLifecycle() = mLifecycleRegistry

    override val keyboardController: SoftKeyboardController?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            softKeyboardController
        } else {
            null
        }

    override val rootNode: AccessibilityNodeInfo?
        get() = rootInActiveWindow
}