package io.github.sds100.keymapper.ui.fragment

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.navigation.navGraphViewModels
import com.google.gson.Gson
import io.github.sds100.keymapper.R
import io.github.sds100.keymapper.data.model.Action
import io.github.sds100.keymapper.data.model.options.ActionShortcutOptions
import io.github.sds100.keymapper.data.viewmodel.CreateActionShortcutViewModel
import io.github.sds100.keymapper.databinding.FragmentCreateActionShortcutBinding
import io.github.sds100.keymapper.service.MyAccessibilityService
import io.github.sds100.keymapper.ui.activity.LaunchActionShortcutActivity
import io.github.sds100.keymapper.ui.fragment.keymap.KeymapActionOptionsFragment
import io.github.sds100.keymapper.util.*
import io.github.sds100.keymapper.util.delegate.RecoverFailureDelegate
import io.github.sds100.keymapper.util.result.valueOrNull
import splitties.alertdialog.appcompat.alertDialog
import splitties.alertdialog.appcompat.cancelButton
import splitties.alertdialog.appcompat.messageResource
import splitties.alertdialog.appcompat.positiveButton
import java.util.*

/**
 * Created by sds100 on 08/09/20.
 */

class CreateActionShortcutFragment : Fragment() {
    companion object {
        private const val CHOOSE_ACTION_REQUEST_KEY = "request_choose_action"
    }

    private val mViewModel by navGraphViewModels<CreateActionShortcutViewModel>(R.id.nav_action_shortcut) {
        InjectorUtils.provideCreateActionShortcutViewModel(requireContext())
    }

    private lateinit var mRecoverFailureDelegate: RecoverFailureDelegate

    /**
     * Scoped to the lifecycle of the fragment's view (between onCreateView and onDestroyView)
     */
    private var _binding: FragmentCreateActionShortcutBinding? = null
    val binding: FragmentCreateActionShortcutBinding
        get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mRecoverFailureDelegate = RecoverFailureDelegate(
            "CreateActionShortcutFragment",
            requireActivity().activityResultRegistry,
            this) {

            mViewModel.actionListViewModel.rebuildModels()
        }

        setFragmentResultListener(CHOOSE_ACTION_REQUEST_KEY) { _, result ->
            result.getParcelable<Action>(ChooseActionFragment.EXTRA_ACTION)?.let {
                mViewModel.actionListViewModel.addAction(it)
            }
        }

        setFragmentResultListener(KeymapActionOptionsFragment.REQUEST_KEY) { _, result ->
            result.getParcelable<ActionShortcutOptions>(BaseOptionsDialogFragment.EXTRA_OPTIONS)
                ?.let {
                    mViewModel.actionListViewModel.setOptions(it)
                }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        FragmentCreateActionShortcutBinding.inflate(inflater).apply {
            lifecycleOwner = viewLifecycleOwner
            viewModel = mViewModel
            _binding = this

            return this.root
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.apply {
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
                showOnBackPressedWarning()
            }

            appBar.setNavigationOnClickListener {
                showOnBackPressedWarning()
            }

            appBar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_done -> {
                        viewLifecycleScope.launchWhenStarted {
                            onDoneClick()
                        }

                        true
                    }

                    else -> false
                }
            }

            mViewModel.eventStream.observe(viewLifecycleOwner, { event ->
                when (event) {
                    is FixFailure -> coordinatorLayout.showFixActionSnackBar(
                        event.failure,
                        requireActivity(),
                        mRecoverFailureDelegate
                    )

                    is EnableAccessibilityServicePrompt -> coordinatorLayout.showEnableAccessibilityServiceSnackBar()
                }
            })
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private suspend fun onDoneClick() {
        ShortcutInfoCompat.Builder(requireContext(), UUID.randomUUID().toString()).apply {
            val icon = createShortcutIcon()
            val shortcutLabel = createShortcutLabel()

            setIcon(icon)
            setShortLabel(shortcutLabel)

            Intent(requireContext(), LaunchActionShortcutActivity::class.java).apply {
                action = MyAccessibilityService.ACTION_PERFORM_ACTIONS

                putExtra(MyAccessibilityService.EXTRA_ACTION_LIST,
                    Gson().toJson(mViewModel.actionListViewModel.actionList.value))

                setIntent(this)
            }

            ShortcutManagerCompat.createShortcutResultIntent(requireContext(), this.build()).apply {
                requireActivity().setResult(Activity.RESULT_OK, this)
                requireActivity().finish()
            }
        }
    }

    private fun createShortcutIcon(): IconCompat {
        if (mViewModel.actionListViewModel.actionList.value?.size == 1) {
            val action = mViewModel.actionListViewModel.actionList.value!![0]

            action.getIcon(requireContext()).valueOrNull()?.let {
                val bitmap = it.toBitmap()

                return IconCompat.createWithBitmap(bitmap)
            }
        }

        return IconCompat.createWithResource(
            requireContext(),
            R.mipmap.ic_launcher_round)
    }

    private suspend fun createShortcutLabel(): String {
        if (mViewModel.actionListViewModel.actionList.value?.size == 1) {
            val action = mViewModel.actionListViewModel.actionList.value!![0]

            action.getTitle(requireContext(), mViewModel.actionListViewModel.getDeviceInfoList()).valueOrNull()?.let {
                return it
            }
        }

        return requireActivity().editTextStringAlertDialog(
            str(R.string.hint_shortcut_name),
            allowEmpty = false
        )
    }

    private fun showOnBackPressedWarning() {
        requireContext().alertDialog {
            messageResource = R.string.dialog_message_are_you_sure_want_to_leave_without_saving

            positiveButton(R.string.pos_yes) {
                requireActivity().finish()
            }

            cancelButton()
            show()
        }
    }
}