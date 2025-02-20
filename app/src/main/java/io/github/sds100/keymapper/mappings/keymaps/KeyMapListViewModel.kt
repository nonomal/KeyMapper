package io.github.sds100.keymapper.mappings.keymaps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sds100.keymapper.mappings.keymaps.trigger.SetupGuiKeyboardState
import io.github.sds100.keymapper.mappings.keymaps.trigger.SetupGuiKeyboardUseCase
import io.github.sds100.keymapper.sorting.SortKeyMapsUseCase
import io.github.sds100.keymapper.system.permissions.Permission
import io.github.sds100.keymapper.util.Error
import io.github.sds100.keymapper.util.State
import io.github.sds100.keymapper.util.mapData
import io.github.sds100.keymapper.util.ui.ChipUi
import io.github.sds100.keymapper.util.ui.MultiSelectProvider
import io.github.sds100.keymapper.util.ui.NavDestination
import io.github.sds100.keymapper.util.ui.NavigationViewModel
import io.github.sds100.keymapper.util.ui.NavigationViewModelImpl
import io.github.sds100.keymapper.util.ui.PopupViewModel
import io.github.sds100.keymapper.util.ui.PopupViewModelImpl
import io.github.sds100.keymapper.util.ui.ResourceProvider
import io.github.sds100.keymapper.util.ui.SelectionState
import io.github.sds100.keymapper.util.ui.ViewModelHelper
import io.github.sds100.keymapper.util.ui.navigate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

open class KeyMapListViewModel(
    private val coroutineScope: CoroutineScope,
    private val listKeyMaps: ListKeyMapsUseCase,
    resourceProvider: ResourceProvider,
    private val multiSelectProvider: MultiSelectProvider<String>,
    private val setupGuiKeyboard: SetupGuiKeyboardUseCase,
    private val sortKeyMaps: SortKeyMapsUseCase,
) : PopupViewModel by PopupViewModelImpl(),
    ResourceProvider by resourceProvider,
    NavigationViewModel by NavigationViewModelImpl() {

    private val listItemCreator = KeyMapListItemCreator(listKeyMaps, resourceProvider)

    private val _state = MutableStateFlow<State<List<KeyMapListItem>>>(State.Loading)
    val state = _state.asStateFlow()

    val setupGuiKeyboardState: StateFlow<SetupGuiKeyboardState> = combine(
        setupGuiKeyboard.isInstalled,
        setupGuiKeyboard.isEnabled,
        setupGuiKeyboard.isChosen,
    ) { isInstalled, isEnabled, isChosen ->
        SetupGuiKeyboardState(
            isInstalled,
            isEnabled,
            isChosen,
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, SetupGuiKeyboardState.DEFAULT)
    var showDpadTriggerSetupBottomSheet: Boolean by mutableStateOf(false)

    init {
        val keyMapStateListFlow =
            MutableStateFlow<State<List<KeyMapListItem.KeyMapUiState>>>(State.Loading)

        val rebuildUiState = MutableSharedFlow<State<List<KeyMap>>>(replay = 1)

        combine(
            listKeyMaps.keyMapList,
            sortKeyMaps.observeKeyMapsSorter(),
        ) { keyMapList, sorter ->
            keyMapList
                .mapData { list -> list.sortedWith(sorter) }
                .also { rebuildUiState.emit(it) }
        }.flowOn(Dispatchers.Default).launchIn(coroutineScope)

        combine(
            rebuildUiState,
            listKeyMaps.showDeviceDescriptors,
        ) { keyMapListState, showDeviceDescriptors ->
            keyMapStateListFlow.value = State.Loading

            keyMapStateListFlow.value = keyMapListState.mapData { keyMapList ->
                keyMapList.map { keyMap ->
                    listItemCreator.create(keyMap, showDeviceDescriptors)
                }
            }
        }.flowOn(Dispatchers.Default).launchIn(coroutineScope)

        coroutineScope.launch {
            listKeyMaps.invalidateActionErrors.drop(1).collectLatest {
                /*
                Don't get the key maps from the repository because there can be a race condition
                when restoring key maps. This happens because when the activity is resumed the
                key maps in the repository are being updated and this flow is collected
                at the same time.
                 */
                rebuildUiState.emit(rebuildUiState.first())
            }
        }

        coroutineScope.launch {
            listKeyMaps.invalidateTriggerErrors.drop(1).collectLatest {
                /*
                Don't get the key maps from the repository because there can be a race condition
                when restoring key maps. This happens because when the activity is resumed the
                key maps in the repository are being updated and this flow is collected
                at the same time.
                 */
                rebuildUiState.emit(rebuildUiState.first())
            }
        }

        coroutineScope.launch {
            listKeyMaps.invalidateConstraintErrors.drop(1).collectLatest {
                /*
                Don't get the key maps from the repository because there can be a race condition
                when restoring key maps. This happens because when the activity is resumed the
                key maps in the repository are being updated and this flow is collected
                at the same time.
                 */
                rebuildUiState.emit(rebuildUiState.first())
            }
        }

        coroutineScope.launch(Dispatchers.Default) {
            combine(
                keyMapStateListFlow,
                multiSelectProvider.state,
            ) { keymapListState, selectionState ->
                Pair(keymapListState, selectionState)
            }.collectLatest { pair ->
                val (keyMapUiListState, selectionState) = pair

                _state.value = keyMapUiListState.mapData { keyMapUiList ->
                    val isSelectable = selectionState is SelectionState.Selecting<*>

                    keyMapUiList.map { keymapUiState ->
                        val isSelected = if (selectionState is SelectionState.Selecting<*>) {
                            selectionState.selectedIds.contains(keymapUiState.uid)
                        } else {
                            false
                        }

                        KeyMapListItem(
                            keymapUiState,
                            KeyMapListItem.SelectionUiState(isSelected, isSelectable),
                        )
                    }
                }
            }
        }
    }

    fun onKeymapCardClick(uid: String) {
        if (multiSelectProvider.state.value is SelectionState.Selecting<*>) {
            multiSelectProvider.toggleSelection(uid)
        } else {
            coroutineScope.launch {
                navigate("config_key_map", NavDestination.ConfigKeyMap(uid))
            }
        }
    }

    fun onKeymapCardLongClick(uid: String) {
        if (multiSelectProvider.state.value is SelectionState.NotSelecting) {
            multiSelectProvider.startSelecting()
            multiSelectProvider.select(uid)
        }
    }

    fun selectAll() {
        coroutineScope.launch {
            state.value.apply {
                if (this is State.Data) {
                    multiSelectProvider.select(
                        *this.data.map { it.keyMapUiState.uid }
                            .toTypedArray(),
                    )
                }
            }
        }
    }

    fun onTriggerErrorChipClick(chipModel: ChipUi) {
        if (chipModel is ChipUi.Error) {
            onFixError(chipModel.error)
        }
    }

    fun onActionChipClick(chipModel: ChipUi) {
        if (chipModel is ChipUi.Error) {
            onFixError(chipModel.error)
        }
    }

    fun onConstraintsChipClick(chipModel: ChipUi) {
        if (chipModel is ChipUi.Error) {
            onFixError(chipModel.error)
        }
    }

    fun onEnableGuiKeyboardClick() {
        setupGuiKeyboard.enableInputMethod()
    }

    fun onChooseGuiKeyboardClick() {
        setupGuiKeyboard.chooseInputMethod()
    }

    fun onNeverShowSetupDpadClick() {
        listKeyMaps.neverShowDpadImeSetupError()
    }

    private fun onFixError(error: Error) {
        coroutineScope.launch {
            when (error) {
                Error.PermissionDenied(Permission.ACCESS_NOTIFICATION_POLICY) -> {
                    coroutineScope.launch {
                        ViewModelHelper.showDialogExplainingDndAccessBeingUnavailable(
                            resourceProvider = this@KeyMapListViewModel,
                            popupViewModel = this@KeyMapListViewModel,
                            neverShowDndTriggerErrorAgain = { listKeyMaps.neverShowDndTriggerError() },
                            fixError = { listKeyMaps.fixError(it) },
                        )
                    }
                }

                Error.DpadTriggerImeNotSelected -> {
                    showDpadTriggerSetupBottomSheet = true
                }

                else -> {
                    ViewModelHelper.showFixErrorDialog(
                        resourceProvider = this@KeyMapListViewModel,
                        popupViewModel = this@KeyMapListViewModel,
                        error,
                    ) {
                        listKeyMaps.fixError(error)
                    }
                }
            }
        }
    }
}
