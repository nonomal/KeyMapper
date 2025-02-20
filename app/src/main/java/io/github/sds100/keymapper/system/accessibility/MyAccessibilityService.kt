package io.github.sds100.keymapper.system.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.FingerprintGestureController
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.sds100.keymapper.actions.pinchscreen.PinchScreenType
import io.github.sds100.keymapper.api.IKeyEventRelayServiceCallback
import io.github.sds100.keymapper.api.KeyEventRelayService
import io.github.sds100.keymapper.api.KeyEventRelayServiceWrapperImpl
import io.github.sds100.keymapper.mappings.fingerprintmaps.FingerprintMapId
import io.github.sds100.keymapper.mappings.keymaps.trigger.KeyEventDetectionSource
import io.github.sds100.keymapper.system.devices.InputDeviceUtils
import io.github.sds100.keymapper.system.inputevents.MyKeyEvent
import io.github.sds100.keymapper.system.inputevents.MyMotionEvent
import io.github.sds100.keymapper.util.Error
import io.github.sds100.keymapper.util.Inject
import io.github.sds100.keymapper.util.InputEventType
import io.github.sds100.keymapper.util.MathUtils
import io.github.sds100.keymapper.util.Result
import io.github.sds100.keymapper.util.Success
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber

/**
 * Created by sds100 on 05/04/2020.
 */

class MyAccessibilityService :
    AccessibilityService(),
    LifecycleOwner,
    IAccessibilityService {

    // virtual distance between fingers on multitouch gestures
    private val fingerGestureDistance = 10L

    private lateinit var lifecycleRegistry: LifecycleRegistry

    private var fingerprintGestureCallback: FingerprintGestureController.FingerprintGestureCallback? =
        null

    override val rootNode: AccessibilityNodeModel?
        get() {
            return rootInActiveWindow?.toModel()
        }

    override val isFingerprintGestureDetectionAvailable: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            fingerprintGestureController.isGestureDetectionAvailable
        } else {
            false
        }

    private val _isKeyboardHidden by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MutableStateFlow(softKeyboardController.showMode == SHOW_MODE_HIDDEN)
        } else {
            MutableStateFlow(false)
        }
    }

    override val isKeyboardHidden: Flow<Boolean>
        get() = _isKeyboardHidden

    override var serviceFlags: Int?
        get() = serviceInfo?.flags
        set(value) {
            if (serviceInfo != null && value != null) {
                serviceInfo = serviceInfo.apply {
                    flags = value
                }
            }
        }

    override var serviceFeedbackType: Int?
        get() = serviceInfo?.feedbackType
        set(value) {
            if (serviceInfo != null && value != null) {
                serviceInfo = serviceInfo.apply {
                    feedbackType = value
                }
            }
        }

    override var serviceEventTypes: Int?
        get() = serviceInfo?.eventTypes
        set(value) {
            if (serviceInfo != null && value != null) {
                serviceInfo = serviceInfo.apply {
                    eventTypes = value
                }
            }
        }

    private val relayServiceCallback: IKeyEventRelayServiceCallback =
        object : IKeyEventRelayServiceCallback.Stub() {
            override fun onKeyEvent(event: KeyEvent?): Boolean {
                event ?: return false

                val device = event.device?.let { InputDeviceUtils.createInputDeviceInfo(it) }

                if (controller != null) {
                    return controller!!.onKeyEventFromIme(
                        MyKeyEvent(
                            keyCode = event.keyCode,
                            action = event.action,
                            metaState = event.metaState,
                            scanCode = event.scanCode,
                            device = device,
                            repeatCount = event.repeatCount,
                        ),
                    )
                }

                return false
            }

            override fun onMotionEvent(event: MotionEvent?): Boolean {
                event ?: return false

                if (controller != null) {
                    return controller!!.onMotionEventFromIme(MyMotionEvent.fromMotionEvent(event))
                }

                return false
            }
        }

    private val keyEventRelayServiceWrapper: KeyEventRelayServiceWrapperImpl by lazy {
        KeyEventRelayServiceWrapperImpl(
            ctx = this,
            id = KeyEventRelayService.CALLBACK_ID_ACCESSIBILITY_SERVICE,
            callback = relayServiceCallback,
        )
    }

    private var controller: AccessibilityServiceController? = null

    override fun onCreate() {
        super.onCreate()
        Timber.i("Accessibility service: onCreate")

        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            softKeyboardController.addOnShowModeChangedListener { _, showMode ->
                when (showMode) {
                    SHOW_MODE_AUTO -> _isKeyboardHidden.value = false
                    SHOW_MODE_HIDDEN -> _isKeyboardHidden.value = true
                }
            }
        }

        keyEventRelayServiceWrapper.onCreate()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        Timber.i("Accessibility service: onServiceConnected")

        /*
        I would put this in onCreate but for some reason on some devices getting the application
        context would return null
         */
        if (controller == null) {
            controller = Inject.accessibilityServiceController(this, keyEventRelayServiceWrapper)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            fingerprintGestureCallback =
                object : FingerprintGestureController.FingerprintGestureCallback() {
                    override fun onGestureDetected(gesture: Int) {
                        super.onGestureDetected(gesture)

                        val id: FingerprintMapId = when (gesture) {
                            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_DOWN ->
                                FingerprintMapId.SWIPE_DOWN

                            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_UP ->
                                FingerprintMapId.SWIPE_UP

                            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_LEFT ->
                                FingerprintMapId.SWIPE_LEFT

                            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_RIGHT ->
                                FingerprintMapId.SWIPE_RIGHT

                            else -> return
                        }
                        controller?.onFingerprintGesture(id)
                    }
                }

            fingerprintGestureCallback?.let {
                fingerprintGestureController.registerFingerprintGestureCallback(it, null)
            }
        }

        controller?.onServiceConnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Timber.i("Accessibility service: onUnbind")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        controller = null

        if (::lifecycleRegistry.isInitialized) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            fingerprintGestureController
                .unregisterFingerprintGestureCallback(fingerprintGestureCallback)
        }

        keyEventRelayServiceWrapper.onDestroy()

        Timber.i("Accessibility service: onDestroy")

        super.onDestroy()
    }

    override fun onLowMemory() {
        val memoryInfo = ActivityManager.MemoryInfo()
        getSystemService<ActivityManager>()?.getMemoryInfo(memoryInfo)

        Timber.i("Accessibility service: onLowMemory, total: ${memoryInfo.totalMem}, available: ${memoryInfo.availMem}, is low memory: ${memoryInfo.lowMemory}, threshold: ${memoryInfo.threshold}")

        super.onLowMemory()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        controller?.onAccessibilityEvent(event?.toModel())
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return super.onKeyEvent(event)

        val device = if (event.device == null) {
            null
        } else {
            InputDeviceUtils.createInputDeviceInfo(event.device)
        }

        if (controller != null) {
            return controller!!.onKeyEvent(
                MyKeyEvent(
                    keyCode = event.keyCode,
                    action = event.action,
                    metaState = event.metaState,
                    scanCode = event.scanCode,
                    device = device,
                    repeatCount = event.repeatCount,
                ),
                KeyEventDetectionSource.ACCESSIBILITY_SERVICE,
            )
        }

        return false
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun hideKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            softKeyboardController.showMode = SHOW_MODE_HIDDEN
        }
    }

    override fun showKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            softKeyboardController.showMode = SHOW_MODE_AUTO
        }
    }

    override fun switchIme(imeId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            softKeyboardController.switchToInputMethod(imeId)
        }
    }

    override fun performActionOnNode(
        findNode: (node: AccessibilityNodeModel) -> Boolean,
        performAction: (node: AccessibilityNodeModel) -> AccessibilityNodeAction?,
    ): Result<*> {
        val node = rootInActiveWindow.findNodeRecursively {
            findNode(it.toModel())
        }

        if (node == null) {
            return Error.FailedToFindAccessibilityNode
        }

        val (action, extras) = performAction(node.toModel()) ?: return Success(Unit)

        node.performAction(action, bundleOf(*extras.toList().toTypedArray()))
        node.recycle()

        return Success(Unit)
    }

    override fun doGlobalAction(action: Int): Result<*> {
        val success = performGlobalAction(action)

        if (success) {
            return Success(Unit)
        } else {
            return Error.FailedToPerformAccessibilityGlobalAction(action)
        }
    }

    override fun tapScreen(x: Int, y: Int, inputEventType: InputEventType): Result<*> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val duration = 1L // ms

            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }

            val strokeDescription =
                when {
                    inputEventType == InputEventType.DOWN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                        StrokeDescription(
                            path,
                            0,
                            duration,
                            true,
                        )

                    inputEventType == InputEventType.UP && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                        StrokeDescription(
                            path,
                            59999,
                            duration,
                            false,
                        )

                    else -> StrokeDescription(path, 0, duration)
                }

            strokeDescription.let {
                val gestureDescription = GestureDescription.Builder().apply {
                    addStroke(it)
                }.build()

                val success = dispatchGesture(gestureDescription, null, null)

                return if (success) {
                    Success(Unit)
                } else {
                    Error.FailedToDispatchGesture
                }
            }
        }

        return Error.SdkVersionTooLow(Build.VERSION_CODES.N)
    }

    override fun swipeScreen(
        xStart: Int,
        yStart: Int,
        xEnd: Int,
        yEnd: Int,
        fingerCount: Int,
        duration: Int,
        inputEventType: InputEventType,
    ): Result<*> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (fingerCount >= GestureDescription.getMaxStrokeCount()) {
                return Error.GestureStrokeCountTooHigh
            }
            if (duration >= GestureDescription.getMaxGestureDuration()) {
                return Error.GestureDurationTooHigh
            }

            val pStart = Point(xStart, yStart)
            val pEnd = Point(xEnd, yEnd)

            val gestureBuilder = GestureDescription.Builder()

            if (fingerCount == 1) {
                val p = Path()
                p.moveTo(pStart.x.toFloat(), pStart.y.toFloat())
                p.lineTo(pEnd.x.toFloat(), pEnd.y.toFloat())
                gestureBuilder.addStroke(StrokeDescription(p, 0, duration.toLong()))
            } else {
                // segments between fingers
                val segmentCount = fingerCount - 1
                // the line of the perpendicular line which will be created to place the virtual fingers on it
                val perpendicularLineLength = (fingerGestureDistance * fingerCount).toInt()

                // the length of each segment between fingers
                val segmentLength = perpendicularLineLength / segmentCount
                // perpendicular line of the start swipe point
                val perpendicularLineStart = MathUtils.getPerpendicularOfLine(
                    pStart,
                    pEnd,
                    perpendicularLineLength,
                )
                // perpendicular line of the end swipe point
                val perpendicularLineEnd = MathUtils.getPerpendicularOfLine(
                    pEnd,
                    pStart,
                    perpendicularLineLength,
                    true,
                )

                // this is the angle between start and end point to rotate all virtual fingers on the perpendicular lines in the same direction
                val angle =
                    MathUtils.angleBetweenPoints(Point(xStart, yStart), Point(xEnd, yEnd)) - 90

                // create the virtual fingers
                for (index in 0..segmentCount) {
                    // offset of each finger
                    val fingerOffsetLength = index * segmentLength * 2
                    // move the coordinates of the current virtual finger on the perpendicular line for the start coordinates
                    val startFingerCoordinateWithOffset =
                        MathUtils.movePointByDistanceAndAngle(
                            perpendicularLineStart.start,
                            fingerOffsetLength,
                            angle,
                        )
                    // move the coordinates of the current virtual finger on the perpendicular line for the end coordinates
                    val endFingerCoordinateWithOffset =
                        MathUtils.movePointByDistanceAndAngle(
                            perpendicularLineEnd.start,
                            fingerOffsetLength,
                            angle,
                        )

                    // create a path for each finger, move the the coordinates on the perpendicular line and draw it to the end coordinates of the perpendicular line of the end swipe point
                    val p = Path()
                    p.moveTo(
                        startFingerCoordinateWithOffset.x.toFloat(),
                        startFingerCoordinateWithOffset.y.toFloat(),
                    )
                    p.lineTo(
                        endFingerCoordinateWithOffset.x.toFloat(),
                        endFingerCoordinateWithOffset.y.toFloat(),
                    )

                    gestureBuilder.addStroke(StrokeDescription(p, 0, duration.toLong()))
                }
            }

            val success = dispatchGesture(gestureBuilder.build(), null, null)

            return if (success) {
                Success(Unit)
            } else {
                Error.FailedToDispatchGesture
            }
        }

        return Error.SdkVersionTooLow(Build.VERSION_CODES.N)
    }

    override fun pinchScreen(
        x: Int,
        y: Int,
        distance: Int,
        pinchType: PinchScreenType,
        fingerCount: Int,
        duration: Int,
        inputEventType: InputEventType,
    ): Result<*> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (fingerCount >= GestureDescription.getMaxStrokeCount()) {
                return Error.GestureStrokeCountTooHigh
            }
            if (duration >= GestureDescription.getMaxGestureDuration()) {
                return Error.GestureDurationTooHigh
            }

            val gestureBuilder = GestureDescription.Builder()
            val distributedPoints: List<Point> =
                MathUtils.distributePointsOnCircle(Point(x, y), distance.toFloat() / 2, fingerCount)

            for (index in distributedPoints.indices) {
                val p = Path()
                if (pinchType == PinchScreenType.PINCH_IN) {
                    p.moveTo(x.toFloat(), y.toFloat())
                    p.lineTo(
                        distributedPoints[index].x.toFloat(),
                        distributedPoints[index].y.toFloat(),
                    )
                } else {
                    p.moveTo(
                        distributedPoints[index].x.toFloat(),
                        distributedPoints[index].y.toFloat(),
                    )
                    p.lineTo(x.toFloat(), y.toFloat())
                }

                gestureBuilder.addStroke(StrokeDescription(p, 0, duration.toLong()))
            }

            val success = dispatchGesture(gestureBuilder.build(), null, null)

            return if (success) {
                Success(Unit)
            } else {
                Error.FailedToDispatchGesture
            }
        }

        return Error.SdkVersionTooLow(Build.VERSION_CODES.N)
    }

    override fun findFocussedNode(focus: Int): AccessibilityNodeModel? = findFocus(focus)?.toModel()
}
