package io.github.sds100.keymapper.constraints

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sds100.keymapper.R
import io.github.sds100.keymapper.system.camera.CameraLens
import io.github.sds100.keymapper.system.display.Orientation
import io.github.sds100.keymapper.util.State
import io.github.sds100.keymapper.util.getFullMessage
import io.github.sds100.keymapper.util.ui.DefaultSimpleListItem
import io.github.sds100.keymapper.util.ui.NavDestination
import io.github.sds100.keymapper.util.ui.NavigationViewModel
import io.github.sds100.keymapper.util.ui.NavigationViewModelImpl
import io.github.sds100.keymapper.util.ui.PopupUi
import io.github.sds100.keymapper.util.ui.PopupViewModel
import io.github.sds100.keymapper.util.ui.PopupViewModelImpl
import io.github.sds100.keymapper.util.ui.ResourceProvider
import io.github.sds100.keymapper.util.ui.SimpleListItem
import io.github.sds100.keymapper.util.ui.TintType
import io.github.sds100.keymapper.util.ui.navigate
import io.github.sds100.keymapper.util.ui.showPopup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Created by sds100 on 21/03/2020.
 */

class ChooseConstraintViewModel(
    private val useCase: CreateConstraintUseCase,
    resourceProvider: ResourceProvider,
) : ViewModel(),
    ResourceProvider by resourceProvider,
    PopupViewModel by PopupViewModelImpl(),
    NavigationViewModel by NavigationViewModelImpl() {

    companion object {
        private val ALL_CONSTRAINTS_ORDERED: Array<ConstraintId> = arrayOf(
            ConstraintId.APP_IN_FOREGROUND,
            ConstraintId.APP_NOT_IN_FOREGROUND,
            ConstraintId.APP_PLAYING_MEDIA,
            ConstraintId.APP_NOT_PLAYING_MEDIA,
            ConstraintId.MEDIA_PLAYING,
            ConstraintId.MEDIA_NOT_PLAYING,

            ConstraintId.BT_DEVICE_CONNECTED,
            ConstraintId.BT_DEVICE_DISCONNECTED,

            ConstraintId.SCREEN_ON,
            ConstraintId.SCREEN_OFF,

            ConstraintId.ORIENTATION_PORTRAIT,
            ConstraintId.ORIENTATION_LANDSCAPE,
            ConstraintId.ORIENTATION_0,
            ConstraintId.ORIENTATION_90,
            ConstraintId.ORIENTATION_180,
            ConstraintId.ORIENTATION_270,

            ConstraintId.FLASHLIGHT_ON,
            ConstraintId.FLASHLIGHT_OFF,

            ConstraintId.WIFI_ON,
            ConstraintId.WIFI_OFF,
            ConstraintId.WIFI_CONNECTED,
            ConstraintId.WIFI_DISCONNECTED,

            ConstraintId.IME_CHOSEN,
            ConstraintId.IME_NOT_CHOSEN,

            ConstraintId.DEVICE_IS_LOCKED,
            ConstraintId.DEVICE_IS_UNLOCKED,

            ConstraintId.IN_PHONE_CALL,
            ConstraintId.NOT_IN_PHONE_CALL,
            ConstraintId.PHONE_RINGING,

            ConstraintId.CHARGING,
            ConstraintId.DISCHARGING,
        )
    }

    private val _listItems = MutableStateFlow<State<List<SimpleListItem>>>(State.Loading)
    val listItems = _listItems.asStateFlow()

    private val _returnResult = MutableSharedFlow<Constraint>()
    val returnResult = _returnResult.asSharedFlow()

    private var supportedConstraints = MutableStateFlow<Array<ConstraintId>>(emptyArray())

    init {
        viewModelScope.launch(Dispatchers.Default) {
            supportedConstraints.collectLatest {
                _listItems.value = State.Data(buildListItems())
            }
        }
    }

    fun setSupportedConstraints(supportedConstraints: Array<ConstraintId>) {
        this.supportedConstraints.value = supportedConstraints
    }

    fun onListItemClick(id: String) {
        viewModelScope.launch {
            when (val constraintType = ConstraintId.valueOf(id)) {
                ConstraintId.APP_IN_FOREGROUND,
                ConstraintId.APP_NOT_IN_FOREGROUND,
                ConstraintId.APP_PLAYING_MEDIA,
                ConstraintId.APP_NOT_PLAYING_MEDIA,
                -> onSelectAppConstraint(constraintType)

                ConstraintId.MEDIA_PLAYING -> _returnResult.emit(Constraint.MediaPlaying)
                ConstraintId.MEDIA_NOT_PLAYING -> _returnResult.emit(Constraint.NoMediaPlaying)

                ConstraintId.BT_DEVICE_CONNECTED,
                ConstraintId.BT_DEVICE_DISCONNECTED,
                -> onSelectBluetoothConstraint(
                    constraintType,
                )

                ConstraintId.SCREEN_ON -> onSelectScreenOnConstraint()
                ConstraintId.SCREEN_OFF -> onSelectScreenOffConstraint()

                ConstraintId.ORIENTATION_PORTRAIT ->
                    _returnResult.emit(Constraint.OrientationPortrait)

                ConstraintId.ORIENTATION_LANDSCAPE ->
                    _returnResult.emit(Constraint.OrientationLandscape)

                ConstraintId.ORIENTATION_0 ->
                    _returnResult.emit(Constraint.OrientationCustom(Orientation.ORIENTATION_0))

                ConstraintId.ORIENTATION_90 ->
                    _returnResult.emit(Constraint.OrientationCustom(Orientation.ORIENTATION_90))

                ConstraintId.ORIENTATION_180 ->
                    _returnResult.emit(Constraint.OrientationCustom(Orientation.ORIENTATION_180))

                ConstraintId.ORIENTATION_270 ->
                    _returnResult.emit(Constraint.OrientationCustom(Orientation.ORIENTATION_270))

                ConstraintId.FLASHLIGHT_ON -> {
                    val lens = chooseFlashlightLens() ?: return@launch
                    _returnResult.emit(Constraint.FlashlightOn(lens))
                }

                ConstraintId.FLASHLIGHT_OFF -> {
                    val lens = chooseFlashlightLens() ?: return@launch
                    _returnResult.emit(Constraint.FlashlightOff(lens))
                }

                ConstraintId.WIFI_ON -> _returnResult.emit(Constraint.WifiOn)
                ConstraintId.WIFI_OFF -> _returnResult.emit(Constraint.WifiOff)

                ConstraintId.WIFI_CONNECTED,
                ConstraintId.WIFI_DISCONNECTED,
                -> onSelectWifiConnectedConstraint(
                    constraintType,
                )

                ConstraintId.IME_CHOSEN,
                ConstraintId.IME_NOT_CHOSEN,
                -> onSelectImeChosenConstraint(constraintType)

                ConstraintId.DEVICE_IS_LOCKED ->
                    _returnResult.emit(Constraint.DeviceIsLocked)

                ConstraintId.DEVICE_IS_UNLOCKED ->
                    _returnResult.emit(Constraint.DeviceIsUnlocked)

                ConstraintId.IN_PHONE_CALL ->
                    _returnResult.emit(Constraint.InPhoneCall)

                ConstraintId.NOT_IN_PHONE_CALL ->
                    _returnResult.emit(Constraint.NotInPhoneCall)

                ConstraintId.PHONE_RINGING ->
                    _returnResult.emit(Constraint.PhoneRinging)

                ConstraintId.CHARGING ->
                    _returnResult.emit(Constraint.Charging)

                ConstraintId.DISCHARGING ->
                    _returnResult.emit(Constraint.Discharging)
            }
        }
    }

    private suspend fun chooseFlashlightLens(): CameraLens? {
        val items = listOf(
            CameraLens.FRONT to getString(R.string.lens_front),
            CameraLens.BACK to getString(R.string.lens_back),
        )

        val dialog = PopupUi.SingleChoice(items)

        val cameraLens = showPopup("choose_flashlight_lens", dialog)

        return cameraLens
    }

    private fun buildListItems(): List<DefaultSimpleListItem> = sequence {
        ALL_CONSTRAINTS_ORDERED.forEach { type ->
            if (!supportedConstraints.value.contains(type)) return@forEach

            val title: String = when (type) {
                ConstraintId.APP_IN_FOREGROUND -> getString(R.string.constraint_choose_app_foreground)
                ConstraintId.APP_NOT_IN_FOREGROUND -> getString(R.string.constraint_choose_app_not_foreground)
                ConstraintId.APP_PLAYING_MEDIA -> getString(R.string.constraint_choose_app_playing_media)
                ConstraintId.APP_NOT_PLAYING_MEDIA -> getString(R.string.constraint_choose_app_not_playing_media)
                ConstraintId.MEDIA_NOT_PLAYING -> getString(R.string.constraint_choose_media_not_playing)
                ConstraintId.MEDIA_PLAYING -> getString(R.string.constraint_choose_media_playing)
                ConstraintId.BT_DEVICE_CONNECTED -> getString(R.string.constraint_choose_bluetooth_device_connected)
                ConstraintId.BT_DEVICE_DISCONNECTED -> getString(R.string.constraint_choose_bluetooth_device_disconnected)
                ConstraintId.SCREEN_ON -> getString(R.string.constraint_choose_screen_on_description)
                ConstraintId.SCREEN_OFF -> getString(R.string.constraint_choose_screen_off_description)
                ConstraintId.ORIENTATION_PORTRAIT -> getString(R.string.constraint_choose_orientation_portrait)
                ConstraintId.ORIENTATION_LANDSCAPE -> getString(R.string.constraint_choose_orientation_landscape)
                ConstraintId.ORIENTATION_0 -> getString(R.string.constraint_choose_orientation_0)
                ConstraintId.ORIENTATION_90 -> getString(R.string.constraint_choose_orientation_90)
                ConstraintId.ORIENTATION_180 -> getString(R.string.constraint_choose_orientation_180)
                ConstraintId.ORIENTATION_270 -> getString(R.string.constraint_choose_orientation_270)
                ConstraintId.FLASHLIGHT_ON -> getString(R.string.constraint_flashlight_on)
                ConstraintId.FLASHLIGHT_OFF -> getString(R.string.constraint_flashlight_off)
                ConstraintId.WIFI_ON -> getString(R.string.constraint_wifi_on)
                ConstraintId.WIFI_OFF -> getString(R.string.constraint_wifi_off)
                ConstraintId.WIFI_CONNECTED -> getString(R.string.constraint_wifi_connected)
                ConstraintId.WIFI_DISCONNECTED -> getString(R.string.constraint_wifi_disconnected)
                ConstraintId.IME_CHOSEN -> getString(R.string.constraint_ime_chosen)
                ConstraintId.IME_NOT_CHOSEN -> getString(R.string.constraint_ime_not_chosen)
                ConstraintId.DEVICE_IS_LOCKED -> getString(R.string.constraint_device_is_locked)
                ConstraintId.DEVICE_IS_UNLOCKED -> getString(R.string.constraint_device_is_unlocked)
                ConstraintId.IN_PHONE_CALL -> getString(R.string.constraint_in_phone_call)
                ConstraintId.NOT_IN_PHONE_CALL -> getString(R.string.constraint_not_in_phone_call)
                ConstraintId.PHONE_RINGING -> getString(R.string.constraint_phone_ringing)
                ConstraintId.CHARGING -> getString(R.string.constraint_charging)
                ConstraintId.DISCHARGING -> getString(R.string.constraint_discharging)
            }

            val error = useCase.isSupported(type)

            val listItem = DefaultSimpleListItem(
                id = type.toString(),
                title = title,
                isEnabled = error == null,
                subtitle = error?.getFullMessage(this@ChooseConstraintViewModel),
                subtitleTint = TintType.Error,
                icon = null,
            )

            yield(listItem)
        }
    }.toList()

    private suspend fun onSelectWifiConnectedConstraint(type: ConstraintId) {
        val knownSSIDs = useCase.getKnownWiFiSSIDs()

        val chosenSSID: String?

        if (knownSSIDs == null) {
            val savedWifiSSIDs = useCase.getSavedWifiSSIDs().first()

            val dialog = PopupUi.Text(
                hint = getString(R.string.hint_wifi_ssid),
                allowEmpty = true,
                message = getString(R.string.constraint_wifi_message_cant_list_networks),
                autoCompleteEntries = savedWifiSSIDs,
            )

            val ssidText = showPopup("type_ssid", dialog) ?: return

            if (ssidText.isBlank()) {
                chosenSSID = null
            } else {
                chosenSSID = ssidText

                useCase.saveWifiSSID(chosenSSID)
            }
        } else {
            val anySSIDItem =
                "any" to getString(R.string.constraint_wifi_pick_network_any)

            val ssidItems = knownSSIDs.map { "ssid_$it" to it }

            val items = listOf(anySSIDItem).plus(ssidItems)

            val chosenItem =
                showPopup("choose_ssid", PopupUi.SingleChoice(items)) ?: return

            if (chosenItem == anySSIDItem.first) {
                chosenSSID = null
            } else {
                chosenSSID = items.single { it.first == chosenItem }.second
            }
        }

        when (type) {
            ConstraintId.WIFI_CONNECTED ->
                _returnResult.emit(Constraint.WifiConnected(chosenSSID))

            ConstraintId.WIFI_DISCONNECTED ->
                _returnResult.emit(Constraint.WifiDisconnected(chosenSSID))

            else -> Unit
        }
    }

    private suspend fun onSelectImeChosenConstraint(type: ConstraintId) {
        val inputMethods = useCase.getEnabledInputMethods()
        val items = inputMethods.map { it.id to it.label }
        val dialog = PopupUi.SingleChoice(items = items)

        val result = showPopup("choose_input_method", dialog) ?: return

        val imeInfo = inputMethods.single { it.id == result }

        when (type) {
            ConstraintId.IME_CHOSEN ->
                _returnResult.emit(Constraint.ImeChosen(imeInfo.id, imeInfo.label))

            ConstraintId.IME_NOT_CHOSEN ->
                _returnResult.emit(Constraint.ImeNotChosen(imeInfo.id, imeInfo.label))

            else -> Unit
        }
    }

    private suspend fun onSelectScreenOnConstraint() {
        val response = showPopup(
            "screen_on_constraint_limitation",
            PopupUi.Ok(getString(R.string.dialog_message_screen_constraints_limitation)),
        )

        response ?: return

        _returnResult.emit(Constraint.ScreenOn)
    }

    private suspend fun onSelectScreenOffConstraint() {
        val response = showPopup(
            "screen_on_constraint_limitation",
            PopupUi.Ok(getString(R.string.dialog_message_screen_constraints_limitation)),
        )

        response ?: return

        _returnResult.emit(Constraint.ScreenOff)
    }

    private suspend fun onSelectBluetoothConstraint(type: ConstraintId) {
        val response = showPopup(
            "bluetooth_device_constraint_limitation",
            PopupUi.Ok(getString(R.string.dialog_message_bt_constraint_limitation)),
        )

        response ?: return

        val device = navigate(
            "choose_bluetooth_device_for_constraint",
            NavDestination.ChooseBluetoothDevice,
        ) ?: return

        val constraint = when (type) {
            ConstraintId.BT_DEVICE_CONNECTED -> Constraint.BtDeviceConnected(
                device.address,
                device.name,
            )

            ConstraintId.BT_DEVICE_DISCONNECTED -> Constraint.BtDeviceDisconnected(
                device.address,
                device.name,
            )

            else -> throw IllegalArgumentException("Don't know how to create $type constraint after choosing app")
        }

        _returnResult.emit(constraint)
    }

    private suspend fun onSelectAppConstraint(type: ConstraintId) {
        val packageName =
            navigate(
                "choose_package_for_constraint",
                NavDestination.ChooseApp(allowHiddenApps = true),
            )
                ?: return

        val constraint = when (type) {
            ConstraintId.APP_IN_FOREGROUND -> Constraint.AppInForeground(
                packageName,
            )

            ConstraintId.APP_NOT_IN_FOREGROUND -> Constraint.AppNotInForeground(
                packageName,
            )

            ConstraintId.APP_PLAYING_MEDIA -> Constraint.AppPlayingMedia(
                packageName,
            )

            ConstraintId.APP_NOT_PLAYING_MEDIA -> Constraint.AppNotPlayingMedia(
                packageName,
            )

            else -> throw IllegalArgumentException("Don't know how to create $type constraint after choosing app")
        }

        _returnResult.emit(constraint)
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val isSupported: CreateConstraintUseCase,
        private val resourceProvider: ResourceProvider,
    ) : ViewModelProvider.NewInstanceFactory() {

        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChooseConstraintViewModel(isSupported, resourceProvider) as T
    }
}
