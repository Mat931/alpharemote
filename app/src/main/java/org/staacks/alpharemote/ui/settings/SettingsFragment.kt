package org.staacks.alpharemote.ui.settings

import android.Manifest
import android.animation.LayoutTransition
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG
import androidx.recyclerview.widget.RecyclerView
import org.staacks.alpharemote.R
import org.staacks.alpharemote.camera.CameraAction
import org.staacks.alpharemote.databinding.FragmentSettingsBinding
import org.staacks.alpharemote.service.AlphaRemoteService
import org.staacks.alpharemote.ui.help.HelpDialogFragment
import org.staacks.alpharemote.ui.settings.CompanionDeviceHelper.pairCompanionDevice
import org.staacks.alpharemote.ui.settings.CompanionDeviceHelper.startObservingDevicePresence
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

interface CustomButtonListEventReceiver {
    fun startDragging(viewHolder: RecyclerView.ViewHolder)
    fun itemTouched(index: Int, oldCameraAction: CameraAction)
}

class SettingsFragment : Fragment(), CustomButtonListEventReceiver, CameraActionPickerListener {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    lateinit var adapter: CustomButtonRecyclerViewAdapter

    private var itemTouchHelper: ItemTouchHelper? = null

    val onDeviceFoundLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult  ->
        Log.d("companion", "Activity Result: $activityResult")
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val scanResult: ScanResult? = activityResult.data?.getParcelableExtra(
                CompanionDeviceManager.EXTRA_DEVICE) as? ScanResult
            scanResult?.let { result ->
                if (startObservingDevicePresence(requireContext(), result.device)) {
                    if (!checkNotificationPermissionState())
                        requestNotificationPermission(false)
                }
                checkAssociations()
            }
        }
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        val settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        _binding = DataBindingUtil.inflate(inflater, R.layout.fragment_settings, container, false)
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = settingsViewModel
        binding.fragment = this

        viewLifecycleOwner.lifecycleScope.launch {
            settingsViewModel.uiAction.collect{ action ->
                when (action) {
                    SettingsViewModel.SettingsUIAction.PAIR -> pair()
                    SettingsViewModel.SettingsUIAction.UNPAIR -> unpair()
                    SettingsViewModel.SettingsUIAction.REQUEST_BLUETOOTH_PERMISSION -> requestBluetoothPermission(bluetoothRequestPermissionLauncher, true)
                    SettingsViewModel.SettingsUIAction.REQUEST_NOTIFICATION_PERMISSION -> requestNotificationPermission(true)
                    SettingsViewModel.SettingsUIAction.ADD_CUSTOM_BUTTON -> addCustomButton()
                    SettingsViewModel.SettingsUIAction.HELP_CONNECTION ->
                        HelpDialogFragment().setContent(
                            R.string.help_settings_connection_troubleshooting_title,
                            R.string.help_settings_connection_troubleshooting_text
                        ).show(childFragmentManager, null)
                    SettingsViewModel.SettingsUIAction.HELP_CUSTOM_BUTTONS ->
                        HelpDialogFragment().setContent(
                            R.string.help_settings_custom_buttons_title,
                            R.string.help_settings_custom_buttons_text
                        ).show(childFragmentManager, null)
                }
            }
        }

        setupCustomButtonList(settingsViewModel.customButtonListFlow)

        binding.linearLayout.layoutTransition.enableTransitionType(LayoutTransition.CHANGING)

        ViewCompat.setOnApplyWindowInsetsListener(binding.linearLayout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }

        context?.registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))

        checkBluetoothPermissionState()
        checkNotificationPermissionState()
        checkAssociations()

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        context?.unregisterReceiver(bondStateReceiver)
        _binding = null
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("companion", "Received ACTION_BOND_STATE_CHANGED.")
            checkAssociations()
        }
    }

    private fun pair() {
        if (checkBluetoothPermissionState())
            executePair()
        else
            requestBluetoothPermission(pairAfterBluetoothRequestPermissionLauncher, false)
    }

    private fun executePair() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_camera_add)
            .setMessage(R.string.settings_camera_add_info)
            .setCancelable(true)
            .setPositiveButton(R.string.settings_camera_add_confirm) { dialog, which ->
                pairCompanionDevice(requireContext(), object : CompanionDeviceManager.Callback() {
                    override fun onDeviceFound(chooserLauncher: IntentSender) {
                        Log.d("companion", "onDeviceFound")
                        onDeviceFoundLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build(), null)
                    }

                    override fun onFailure(error: CharSequence?) {
                        Log.d("companion", "onFailure")
                        binding.viewModel?.reportErrorState(error.toString())
                    }
                })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun unpair() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_camera_remove)
            .setMessage(R.string.settings_camera_remove_question)
            .setCancelable(true)
            .setPositiveButton(R.string.settings_camera_remove_confirm) { dialog, which ->
                CompanionDeviceHelper.unpairCompanionDevice(requireContext())
                AlphaRemoteService.disconnect()
                checkAssociations()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun checkBluetoothPermissionState(): Boolean {
        val bluetoothGranted = (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        binding.viewModel?.updateBluetoothPermissionState(bluetoothGranted)
        return bluetoothGranted
    }

    private fun checkNotificationPermissionState(): Boolean {
        val notificationsGranted = (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
        binding.viewModel?.updateNotificationPermissionState(notificationsGranted)
        return notificationsGranted
    }

    private fun requestBluetoothPermission(launcher: ActivityResultLauncher<String>, skipRationale: Boolean) {
        if (!skipRationale && shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.permission_bluetooth_rationale)
                .setPositiveButton("OK") { _, _ -> launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
                .setNegativeButton("Cancel", null)
                .create()
                .show()
        } else
            launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun requestNotificationPermission(skipRationale: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            return
        if (skipRationale)
            notificationsRequestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.permission_notification_rationale)
                .setPositiveButton("OK") { _, _ ->
                    notificationsRequestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                .setNegativeButton("Cancel", null)
                .create()
                .show()
        }
    }

    private val bluetoothRequestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d("Permissions", "Bluetooth permission granted.")
            checkBluetoothPermissionState()
        } else {
            Log.w("Permissions", "Bluetooth permission denied.")
        }
    }

    private val pairAfterBluetoothRequestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d("Permissions", "Bluetooth permission granted.")
            checkBluetoothPermissionState()
            executePair()
        } else {
            Log.w("Permissions", "Bluetooth permission denied.")
        }
    }

    private val notificationsRequestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d("Permissions", "Notifications permission granted.")
            checkNotificationPermissionState()
        } else {
            Log.w("Permissions", "Notifications permission denied.")
        }
    }

    private fun checkAssociations() {

        val address = CompanionDeviceHelper.getAssociation(requireContext()).firstOrNull()
        val isAssociated = address != null
        val isBonded = isAssociated && try {
            BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            false
        }

        binding.viewModel?.updateAssociationState(address, isAssociated, isBonded)
    }

    private fun setupCustomButtonList(customButtonListFlow: MutableStateFlow<List<CameraAction>?>) {
        val customButtonsList = binding.customButtonsList

        adapter = CustomButtonRecyclerViewAdapter(customButtonListFlow, this, this)
        customButtonsList.adapter = adapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
            ItemTouchHelper.START or ItemTouchHelper.END
        ) {
            override fun onMove(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                adapter.removeItem(viewHolder.adapterPosition)
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.5f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
            }
        }

        itemTouchHelper = ItemTouchHelper(callback).apply {
            attachToRecyclerView(customButtonsList)
        }
    }

    override fun startDragging(viewHolder: RecyclerView.ViewHolder) {
        itemTouchHelper?.startDrag(viewHolder)
    }

    override fun itemTouched(index: Int, oldCameraAction: CameraAction) {
        val cameraActionPicker = CameraActionPicker.newInstance(index, oldCameraAction)
        cameraActionPicker.show(childFragmentManager, null)
    }

    private fun addCustomButton() {
        val cameraActionPicker = CameraActionPicker()
        cameraActionPicker.show(childFragmentManager, null)
    }

    override fun onConfirmCameraActionPicker(index: Int, cameraAction: CameraAction) {
        adapter.updateItem(index, cameraAction)
    }

    override fun onCancelCameraActionPicker() {
    }

    fun openURL(target: String) {
        val uri = Uri.parse(target)
        val intent = Intent(Intent.ACTION_VIEW, uri)
        startActivity(intent)
    }
}