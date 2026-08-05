package com.ventoid.app

import android.app.AlertDialog
import android.app.Dialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ventoid.app.formatter.FormatClusterSize
import com.ventoid.app.formatter.FormatFileSystem
import com.ventoid.app.formatter.FormatPartitionTable
import com.ventoid.app.formatter.UsbFormatter
import com.ventoid.app.install.InstallMessage
import com.ventoid.app.install.InstallProgress
import com.ventoid.app.install.InstallStage
import com.ventoid.app.install.InstallerAssets
import com.ventoid.app.install.PartitionScheme
import com.ventoid.app.install.VentoyInstallCoordinator
import com.ventoid.app.usb.UsbDeviceItem
import com.ventoid.app.usb.UsbMassStorageHelper
import com.ventoid.app.util.VentoidFileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "android.hardware.usb.action.USB_PERMISSION"
        private const val MAX_LOG_LINES = 120
    }

    // Tabs
    private lateinit var tabButtonVentoy: TextView
    private lateinit var tabButtonFormat: TextView
    private lateinit var containerVentoyTab: LinearLayout
    private lateinit var containerFormatTab: LinearLayout

    // Ventoy Tab Views
    private lateinit var spinnerUsb: Spinner
    private lateinit var spinnerPartitionScheme: Spinner
    private lateinit var buttonRefresh: Button
    private lateinit var buttonInstall: Button
    private lateinit var textStageTitle: TextView
    private lateinit var textHeroStatus: TextView
    private lateinit var textDeviceSummary: TextView
    private lateinit var textSecureBootStatus: TextView
    private lateinit var progressInstall: ProgressBar
    private lateinit var chipMbr: TextView
    private lateinit var chipCore: TextView
    private lateinit var chipPart1: TextView
    private lateinit var chipVentoy: TextView
    private lateinit var textUpdateStatus: TextView
    private lateinit var buttonUpdate: Button

    // Format Tab Views
    private lateinit var textFormatDeviceStatus: TextView
    private lateinit var spinnerFormatFileSystem: Spinner
    private lateinit var spinnerFormatClusterSize: Spinner
    private lateinit var spinnerFormatPartitionTable: Spinner
    private lateinit var editFormatVolumeLabel: EditText
    private lateinit var buttonFormatAction: Button

    // Shared Log Views
    private lateinit var textLog: TextView
    private lateinit var scrollLog: androidx.core.widget.NestedScrollView
    private lateinit var textLogPath: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var permissionReceiver: BroadcastReceiver? = null
    private var usbReceiver: BroadcastReceiver? = null
    private var installJob: Job? = null
    private var formatJob: Job? = null
    private var updateJob: Job? = null
    private var detectJob: Job? = null
    private var deviceList: List<UsbDeviceItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        UsbMassStorageHelper.ensureLibusbRegistered()

        // Bind Tabs
        tabButtonVentoy = findViewById(R.id.tab_button_ventoy)
        tabButtonFormat = findViewById(R.id.tab_button_format)
        containerVentoyTab = findViewById(R.id.container_ventoy_tab)
        containerFormatTab = findViewById(R.id.container_format_tab)

        // Bind Ventoy Tab Views
        spinnerUsb = findViewById(R.id.spinner_usb)
        spinnerPartitionScheme = findViewById(R.id.spinner_partition_scheme)
        buttonRefresh = findViewById(R.id.button_refresh)
        buttonInstall = findViewById(R.id.button_install)
        textStageTitle = findViewById(R.id.text_stage_title)
        textHeroStatus = findViewById(R.id.text_hero_status)
        textDeviceSummary = findViewById(R.id.text_device_summary)
        textSecureBootStatus = findViewById(R.id.text_secure_boot_status)
        progressInstall = findViewById(R.id.progress_install)
        chipMbr = findViewById(R.id.chip_mbr)
        chipCore = findViewById(R.id.chip_core)
        chipPart1 = findViewById(R.id.chip_part1)
        chipVentoy = findViewById(R.id.chip_ventoy)
        textUpdateStatus = findViewById(R.id.text_update_status)
        buttonUpdate = findViewById(R.id.button_update)

        // Bind Format Tab Views
        textFormatDeviceStatus = findViewById(R.id.text_format_device_status)
        spinnerFormatFileSystem = findViewById(R.id.spinner_format_file_system)
        spinnerFormatClusterSize = findViewById(R.id.spinner_format_cluster_size)
        spinnerFormatPartitionTable = findViewById(R.id.spinner_format_partition_table)
        editFormatVolumeLabel = findViewById(R.id.edit_format_volume_label)
        buttonFormatAction = findViewById(R.id.button_format_action)

        // Bind Shared Log Views
        textLog = findViewById(R.id.text_log)
        scrollLog = findViewById(R.id.scroll_log)
        textLogPath = findViewById(R.id.text_log_path)

        textLogPath.text = getString(R.string.log_path, VentoidFileLogger.getLogPath(this))
        textLogPath.visibility = TextView.VISIBLE

        setupTabNavigation()
        setupPartitionSchemeSpinner()
        setupFormatSpinners()
        refreshSecureBootStatus()
        renderInstallStage(InstallStage.UNKNOWN, 0)
        registerUsbReceiver()

        buttonRefresh.setOnClickListener { refreshDeviceList() }
        buttonInstall.setOnClickListener { onInstallClicked() }
        buttonUpdate.setOnClickListener { onUpdateClicked() }
        buttonFormatAction.setOnClickListener { onFormatClicked() }
        findViewById<TextView>(R.id.button_about).setOnClickListener {
            showAboutDialog()
        }

        spinnerUsb.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateSelectedDeviceSummary()
                detectExistingVentoy()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateSelectedDeviceSummary()
            }
        }

        refreshDeviceList()
    }

    private fun setupTabNavigation() {
        tabButtonVentoy.setOnClickListener { selectTab(isVentoy = true) }
        tabButtonFormat.setOnClickListener { selectTab(isVentoy = false) }
    }

    private fun selectTab(isVentoy: Boolean) {
        if (isVentoy) {
            containerVentoyTab.visibility = View.VISIBLE
            containerFormatTab.visibility = View.GONE
            tabButtonVentoy.setBackgroundResource(R.drawable.tab_active)
            tabButtonVentoy.setTextColor(ContextCompat.getColor(this, R.color.ventoid_primary_dark))
            tabButtonFormat.setBackgroundResource(R.drawable.tab_inactive)
            tabButtonFormat.setTextColor(ContextCompat.getColor(this, R.color.ventoid_text_secondary))
        } else {
            containerVentoyTab.visibility = View.GONE
            containerFormatTab.visibility = View.VISIBLE
            tabButtonFormat.setBackgroundResource(R.drawable.tab_active)
            tabButtonFormat.setTextColor(ContextCompat.getColor(this, R.color.ventoid_primary_dark))
            tabButtonVentoy.setBackgroundResource(R.drawable.tab_inactive)
            tabButtonVentoy.setTextColor(ContextCompat.getColor(this, R.color.ventoid_text_secondary))
        }
    }

    private fun setupPartitionSchemeSpinner() {
        spinnerPartitionScheme.adapter = createSpinnerAdapter(
            listOf(
                getString(R.string.partition_scheme_mbr),
                getString(R.string.partition_scheme_gpt),
            )
        )
        spinnerPartitionScheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePartitionSchemeUi()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updatePartitionSchemeUi()
    }

    private fun setupFormatSpinners() {
        spinnerFormatFileSystem.adapter = createSpinnerAdapter(
            FormatFileSystem.values().map { it.displayName }
        )
        // Default to FAT32
        spinnerFormatFileSystem.setSelection(1)

        spinnerFormatClusterSize.adapter = createSpinnerAdapter(
            FormatClusterSize.OPTIONS
        )
        spinnerFormatClusterSize.setSelection(0)

        spinnerFormatPartitionTable.adapter = createSpinnerAdapter(
            FormatPartitionTable.values().map { it.displayName }
        )
        spinnerFormatPartitionTable.setSelection(0)
    }

    private fun refreshSecureBootStatus() {
        textSecureBootStatus.text = getString(R.string.secure_boot_checking)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { InstallerAssets.inspectSecureBootSupport(assets) }
            }.onSuccess { support ->
                if (!isDestroyed) {
                    if (support.supported) {
                        textSecureBootStatus.text = getString(
                            R.string.secure_boot_verified,
                            support.verifiedMarkers.joinToString()
                        )
                        textSecureBootStatus.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.ventoid_success)
                        )
                    } else {
                        textSecureBootStatus.text = getString(
                            R.string.secure_boot_missing,
                            support.missingMarkers.joinToString()
                        )
                        textSecureBootStatus.setTextColor(
                            ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_light)
                        )
                    }
                }
            }.onFailure { error ->
                if (!isDestroyed) {
                    textSecureBootStatus.text = getString(
                        R.string.secure_boot_check_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                    textSecureBootStatus.setTextColor(
                        ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_light)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        installJob?.cancel()
        formatJob?.cancel()
        updateJob?.cancel()
        detectJob?.cancel()
        unregisterPermissionReceiver()
        unregisterUsbReceiver()
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshDeviceList() {
        detectJob?.cancel()
        deviceList = UsbMassStorageHelper.getMassStorageDevices(this)
        val displayNames = deviceList.map { it.displayName }
        spinnerUsb.adapter = createSpinnerAdapter(displayNames)
        buttonInstall.isEnabled = deviceList.isNotEmpty()
        buttonFormatAction.isEnabled = deviceList.isNotEmpty()
        buttonUpdate.isEnabled = false
        textUpdateStatus.text = getString(R.string.update_status_hint)
        textUpdateStatus.setTextColor(ContextCompat.getColor(this, R.color.ventoid_text_secondary))
        updateSelectedDeviceSummary()
        if (deviceList.isEmpty()) {
            textStageTitle.text = getString(R.string.usb_device_none)
            textHeroStatus.text = getString(R.string.hero_status)
            textFormatDeviceStatus.text = getString(R.string.no_device_connected)
            textFormatDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.ventoid_text_primary))
        } else {
            if (installJob?.isActive != true) {
                textStageTitle.text = getString(R.string.progress_idle)
            }
            textHeroStatus.text = getString(R.string.usb_device_count, deviceList.size)
        }
        log(getString(R.string.usb_device_count, deviceList.size))
    }

    private fun updateSelectedDeviceSummary() {
        val selected = deviceList.getOrNull(spinnerUsb.selectedItemPosition)
        if (selected == null) {
            textDeviceSummary.text = getString(R.string.device_summary_empty)
            textDeviceSummary.setTextColor(ContextCompat.getColor(this, R.color.ventoid_text_secondary))
            textFormatDeviceStatus.text = getString(R.string.no_device_connected)
            textFormatDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.ventoid_text_primary))
            return
        }

        textDeviceSummary.text = getString(R.string.device_summary_selected, selected.displayName)
        textDeviceSummary.setTextColor(ContextCompat.getColor(this, R.color.ventoid_success))

        textFormatDeviceStatus.text = selected.displayName
        textFormatDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.ventoid_success))
    }

    private fun detectExistingVentoy() {
        detectJob?.cancel()
        val item = deviceList.getOrNull(spinnerUsb.selectedItemPosition)
        if (item == null || installJob?.isActive == true || updateJob?.isActive == true) {
            buttonUpdate.isEnabled = false
            textUpdateStatus.text = getString(R.string.update_status_hint)
            textUpdateStatus.setTextColor(ContextCompat.getColor(this, R.color.ventoid_text_secondary))
            return
        }
        buttonUpdate.isEnabled = false
        textUpdateStatus.text = getString(R.string.update_status_checking)
        textUpdateStatus.setTextColor(ContextCompat.getColor(this, R.color.ventoid_text_secondary))
        detectJob = scope.launch {
            val detected = runCatching {
                withContext(Dispatchers.IO) {
                    VentoyInstallCoordinator(applicationContext).detectExistingInstall(item)
                }
            }.getOrNull()
            if (!isDestroyed) {
                val existing = detected != null
                buttonUpdate.isEnabled = existing
                textUpdateStatus.text = getString(
                    if (existing) {
                        R.string.update_status_detected
                    } else {
                        R.string.update_status_not_ventoy
                    }
                )
                textUpdateStatus.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (existing) R.color.ventoid_success else R.color.ventoid_text_secondary
                    )
                )
            }
        }
    }

    private fun registerUsbReceiver() {        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action
                if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED || action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    refreshDeviceList()
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            usbFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun unregisterUsbReceiver() {
        val receiver = usbReceiver ?: return
        usbReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    private fun onInstallClicked() {
        val item = selectedUsbDevice() ?: return
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        if (usbManager.hasPermission(item.usbDevice)) {
            startInstall(item)
            return
        }

        requestUsbPermission(usbManager, item) { startInstall(item) }
    }

    private fun onUpdateClicked() {
        val item = selectedUsbDevice() ?: return
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        if (usbManager.hasPermission(item.usbDevice)) {
            showUpdateConfirm(item)
            return
        }

        requestUsbPermission(usbManager, item) { showUpdateConfirm(item) }
    }

    private fun showUpdateConfirm(item: UsbDeviceItem) {
        val dialog = Dialog(this, R.style.Theme_Ventoid_Dialog)
        dialog.setContentView(R.layout.dialog_confirm_format)
        dialog.setCanceledOnTouchOutside(true)

        val textTarget = dialog.findViewById<TextView>(R.id.text_confirm_target)
        val textDetails = dialog.findViewById<TextView>(R.id.text_confirm_details)
        val btnConfirm = dialog.findViewById<TextView>(R.id.btn_confirm_format)
        val btnCancel = dialog.findViewById<TextView>(R.id.btn_confirm_cancel)

        textTarget.text = getString(R.string.device_summary_selected, item.displayName)
        textDetails.text = getString(R.string.update_confirm_body)
        btnConfirm.text = getString(R.string.update_confirm_btn)
        val warning = dialog.findViewById<TextView>(R.id.text_confirm_warning)
        warning.text = getString(R.string.update_confirm_warning)
        warning.setTextColor(ContextCompat.getColor(this, R.color.ventoid_success))

        btnConfirm.setOnClickListener {
            dialog.dismiss()
            startUpdate(item)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun startUpdate(item: UsbDeviceItem) {
        updateJob?.cancel()
        textLog.text = ""
        renderInstallStage(InstallStage.UNKNOWN, 0)
        updateJob = scope.launch {
            buttonInstall.isEnabled = false
            buttonUpdate.isEnabled = false
            buttonFormatAction.isEnabled = false
            try {
                safeLog(getString(R.string.update_started))
                withContext(Dispatchers.IO) {
                    VentoyInstallCoordinator(applicationContext).update(
                        device = item,
                        onProgress = ::handleInstallProgress,
                    )
                }
                safeToast(getString(R.string.update_success))
            } catch (e: SecurityException) {
                VentoidFileLogger.log(e)
                safeLog(getString(R.string.permission_denied))
                safeToast(getString(R.string.permission_denied))
            } catch (e: IOException) {
                VentoidFileLogger.log(e)
                showError(getString(R.string.update_failed_with_reason, e.message ?: e.javaClass.simpleName))
            } catch (e: Exception) {
                VentoidFileLogger.log(e)
                showError(getString(R.string.unexpected_error_with_reason, e.message ?: e.javaClass.simpleName))
            } finally {
                if (!isDestroyed) {
                    buttonInstall.isEnabled = deviceList.isNotEmpty()
                    buttonFormatAction.isEnabled = deviceList.isNotEmpty()
                    detectExistingVentoy()
                }
            }
        }
    }

    private fun onFormatClicked() {
        val item = selectedUsbDevice() ?: return
        val fsName = FormatFileSystem.values()[spinnerFormatFileSystem.selectedItemPosition].displayName
        val ptName = FormatPartitionTable.values()[spinnerFormatPartitionTable.selectedItemPosition].displayName
        val dialog = Dialog(this, R.style.Theme_Ventoid_Dialog)
        dialog.setContentView(R.layout.dialog_confirm_format)
        dialog.setCanceledOnTouchOutside(true)

        val textTarget = dialog.findViewById<TextView>(R.id.text_confirm_target)
        val textDetails = dialog.findViewById<TextView>(R.id.text_confirm_details)
        val btnFormat = dialog.findViewById<TextView>(R.id.btn_confirm_format)
        val btnCancel = dialog.findViewById<TextView>(R.id.btn_confirm_cancel)

        textTarget.text = getString(R.string.device_summary_selected, item.displayName)
        textDetails.text = getString(R.string.format_confirm_details_fmt, fsName, ptName)

        btnFormat.setOnClickListener {
            dialog.dismiss()
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            if (usbManager.hasPermission(item.usbDevice)) {
                startFormat(item)
            } else {
                requestUsbPermission(usbManager, item) { startFormat(item) }
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun selectedUsbDevice(): UsbDeviceItem? {
        val index = spinnerUsb.selectedItemPosition
        if (deviceList.isEmpty() || index !in deviceList.indices) {
            toast(R.string.no_usb)
            return null
        }
        return deviceList[index]
    }

    private fun requestUsbPermission(usbManager: UsbManager, item: UsbDeviceItem, onGranted: () -> Unit) {
        unregisterPermissionReceiver()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) {
                    return
                }

                unregisterPermissionReceiver()

                if (usbManager.hasPermission(item.usbDevice)) {
                    onGranted()
                } else {
                    log(getString(R.string.permission_denied))
                    toast(R.string.permission_denied)
                }
            }
        }

        permissionReceiver = receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(item.usbDevice, pendingIntent)
    }

    private fun unregisterPermissionReceiver() {
        val receiver = permissionReceiver ?: return
        permissionReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    private fun startInstall(item: UsbDeviceItem) {
        installJob?.cancel()
        textLog.text = ""
        renderInstallStage(InstallStage.UNKNOWN, 0)
        val partitionScheme = selectedPartitionScheme()
        installJob = scope.launch {
            buttonInstall.isEnabled = false
            buttonUpdate.isEnabled = false
            buttonFormatAction.isEnabled = false
            try {
                safeLog(getString(R.string.partition_scheme_log, partitionScheme.toDisplayLabel()))
                withContext(Dispatchers.IO) {
                    VentoyInstallCoordinator(applicationContext).install(
                        device = item,
                        partitionScheme = partitionScheme,
                        onProgress = ::handleInstallProgress,
                    )
                }
                safeToast(getString(R.string.install_success))
            } catch (e: SecurityException) {
                VentoidFileLogger.log(e)
                safeLog(getString(R.string.permission_denied))
                safeToast(getString(R.string.permission_denied))
            } catch (e: IOException) {
                VentoidFileLogger.log(e)
                showError(getString(R.string.install_failed_with_reason, e.message ?: e.javaClass.simpleName))
            } catch (e: Exception) {
                VentoidFileLogger.log(e)
                showError(getString(R.string.unexpected_error_with_reason, e.message ?: e.javaClass.simpleName))
            } finally {
                if (!isDestroyed) {
                    buttonInstall.isEnabled = deviceList.isNotEmpty()
                    buttonFormatAction.isEnabled = deviceList.isNotEmpty()
                    detectExistingVentoy()
                }
            }
        }
    }

    private fun startFormat(item: UsbDeviceItem) {
        formatJob?.cancel()
        textLog.text = ""

        val fs = FormatFileSystem.values()[spinnerFormatFileSystem.selectedItemPosition]
        val pt = FormatPartitionTable.values()[spinnerFormatPartitionTable.selectedItemPosition]
        val clusterSizeChoice = FormatClusterSize.OPTIONS[spinnerFormatClusterSize.selectedItemPosition]
        val volumeLabel = editFormatVolumeLabel.text.toString()

        formatJob = scope.launch {
            buttonInstall.isEnabled = false
            buttonUpdate.isEnabled = false
            buttonFormatAction.isEnabled = false
            try {
                safeLog(getString(R.string.format_started, fs.displayName, pt.displayName))
                withContext(Dispatchers.IO) {
                    val session = UsbMassStorageHelper.openBlockDevice(applicationContext, item)
                    try {
                        UsbFormatter(session.blockDevice).format(
                            fileSystem = fs,
                            partitionTable = pt,
                            clusterSizeChoice = clusterSizeChoice,
                            volumeLabel = volumeLabel,
                            onProgress = { msg -> safeLog(msg) }
                        )
                        session.syncBeforeClose()
                    } finally {
                        session.close()
                    }
                }
                safeLog(getString(R.string.format_success))
                safeToast(getString(R.string.format_success))
            } catch (e: SecurityException) {
                VentoidFileLogger.log(e)
                safeLog(getString(R.string.permission_denied))
                safeToast(getString(R.string.permission_denied))
            } catch (e: IOException) {
                VentoidFileLogger.log(e)
                showError(getString(R.string.format_failed, e.message ?: e.javaClass.simpleName))
            } catch (e: Exception) {
                VentoidFileLogger.log(e)
                showError(getString(R.string.unexpected_error_with_reason, e.message ?: e.javaClass.simpleName))
            } finally {
                if (!isDestroyed) {
                    buttonInstall.isEnabled = deviceList.isNotEmpty()
                    buttonFormatAction.isEnabled = deviceList.isNotEmpty()
                    detectExistingVentoy()
                }
            }
        }
    }

    private fun handleInstallProgress(progress: InstallProgress) {
        when (progress) {
            is InstallProgress.Log -> {
                safeLog(progress.message.toDisplayText())
                if (progress.message == InstallMessage.Starting) {
                    runOnUiThread {
                        textStageTitle.text = getString(R.string.install_started)
                        progressInstall.progress = 2
                        renderInstallStage(InstallStage.MBR, 2)
                    }
                }
                if (progress.message == InstallMessage.Success) {
                    runOnUiThread {
                        textStageTitle.text = getString(R.string.install_success)
                        progressInstall.progress = 100
                        renderInstallStage(InstallStage.VENTOY, 100)
                    }
                }
            }
            is InstallProgress.Step -> {
                val percent = if (progress.total > 0) ((progress.current * 100) / progress.total).toInt() else 0
                val overallPercent = progress.stage.toOverallPercent(percent)
                runOnUiThread {
                    if (!isDestroyed) {
                        textStageTitle.text =
                            getString(R.string.progress_message, progress.stage.toDisplayLabel(), percent)
                        progressInstall.progress = overallPercent
                        renderInstallStage(progress.stage, overallPercent)
                    }
                }
                safeLog(getString(R.string.progress_message, progress.stage.toDisplayLabel(), percent))
            }
            is InstallProgress.Failure -> VentoidFileLogger.log(progress.error)
        }
    }

    private fun InstallMessage.toDisplayText(): String {
        return when (this) {
            InstallMessage.Starting -> getString(R.string.install_started)
            InstallMessage.Success -> getString(R.string.install_success)
            InstallMessage.WriteProtectTip -> getString(R.string.write_protect_tip)
            InstallMessage.SecureBootVerified -> getString(R.string.secure_boot_log)
            InstallMessage.UpdateStarting -> getString(R.string.update_started)
            InstallMessage.UpdateDetected -> getString(R.string.update_detected_log)
            InstallMessage.UpdateSuccess -> getString(R.string.update_success)
        }
    }

    private fun InstallStage.toDisplayLabel(): String {
        return when (this) {
            InstallStage.MBR -> getString(
                if (selectedPartitionScheme() == PartitionScheme.GPT) {
                    R.string.progress_gpt
                } else {
                    R.string.progress_mbr
                }
            )
            InstallStage.CORE -> getString(R.string.progress_core)
            InstallStage.PARTITION_1 -> getString(R.string.progress_part1)
            InstallStage.VENTOY -> getString(R.string.progress_ventoy)
            InstallStage.UNKNOWN -> getString(R.string.progress_unknown)
        }
    }

    private fun InstallStage.toOverallPercent(stagePercent: Int): Int {
        val normalized = stagePercent.coerceIn(0, 100)
        return when (this) {
            InstallStage.MBR -> normalized / 4
            InstallStage.CORE -> 25 + normalized / 4
            InstallStage.PARTITION_1 -> 50 + normalized / 4
            InstallStage.VENTOY -> 75 + normalized / 4
            InstallStage.UNKNOWN -> normalized
        }
    }

    private fun renderInstallStage(activeStage: InstallStage, overallPercent: Int) {
        chipMbr.text = getString(
            if (selectedPartitionScheme() == PartitionScheme.GPT) {
                R.string.progress_gpt_short
            } else {
                R.string.progress_mbr_short
            }
        )
        renderChip(chipMbr, InstallStage.MBR, activeStage)
        renderChip(chipCore, InstallStage.CORE, activeStage)
        renderChip(chipPart1, InstallStage.PARTITION_1, activeStage)
        renderChip(chipVentoy, InstallStage.VENTOY, activeStage)
        if (overallPercent == 0) {
            textStageTitle.text = getString(R.string.progress_idle)
        }
    }

    private fun renderChip(chip: TextView, chipStage: InstallStage, activeStage: InstallStage) {
        val backgroundRes = when {
            activeStage == InstallStage.UNKNOWN -> R.drawable.chip_pending
            chipStage.ordinal < activeStage.ordinal -> R.drawable.chip_complete
            chipStage == activeStage -> R.drawable.chip_active
            else -> R.drawable.chip_pending
        }
        chip.setBackgroundResource(backgroundRes)
        val textColorRes = if (backgroundRes == R.drawable.chip_complete) {
            android.R.color.black
        } else {
            R.color.ventoid_text_primary
        }
        chip.setTextColor(ContextCompat.getColor(this, textColorRes))
    }

    private fun showError(message: String) {
        safeLog(message)
        safeToast(message)
    }

    private fun safeLog(message: String) {
        runOnUiThread {
            if (!isDestroyed) {
                log(message)
            }
        }
    }

    private fun safeToast(message: String) {
        runOnUiThread {
            if (!isDestroyed) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toast(messageResId: Int) {
        Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show()
    }

    private fun log(message: String) {
        val updatedLines = buildList {
            val current = textLog.text.toString()
            if (current.isNotBlank()) {
                addAll(current.lineSequence().filter { it.isNotBlank() }.toList())
            }
            add(message)
        }.takeLast(MAX_LOG_LINES)
        textLog.text = updatedLines.joinToString("\n")
        scrollLog.post {
            if (!isDestroyed) {
                scrollLog.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun selectedPartitionScheme(): PartitionScheme {
        return PartitionScheme.fromSpinnerPosition(spinnerPartitionScheme.selectedItemPosition)
    }

    private fun updatePartitionSchemeUi() {
        renderInstallStage(InstallStage.UNKNOWN, progressInstall.progress)
    }

    private fun createSpinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(
            this,
            R.layout.item_spinner_selected,
            items,
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
    }

    private fun PartitionScheme.toDisplayLabel(): String {
        return getString(
            if (this == PartitionScheme.GPT) {
                R.string.partition_scheme_gpt_short
            } else {
                R.string.partition_scheme_mbr_short
            }
        )
    }

    private fun showAboutDialog() {
        val dialog = Dialog(this, R.style.Theme_Ventoid_Dialog)
        dialog.setContentView(R.layout.dialog_about)
        dialog.setCanceledOnTouchOutside(true)

        val textDeveloper = dialog.findViewById<TextView>(R.id.text_developer)
        val devText = getString(R.string.about_developer_text)
        val keyword = getString(R.string.about_dev_link_keyword)
        val spannableString = SpannableString(devText)
        val startIndex = devText.indexOf(keyword)
        if (startIndex != -1) {
            val endIndex = startIndex + keyword.length
            spannableString.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openUrl("https://t.me/+0Y46o__6Ktk5MTU0")
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = ContextCompat.getColor(this@MainActivity, R.color.ventoid_primary)
                    ds.isUnderlineText = true
                    ds.isFakeBoldText = true
                }
            }, startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textDeveloper.text = spannableString
        textDeveloper.movementMethod = LinkMovementMethod.getInstance()

        val btnGithub = dialog.findViewById<TextView>(R.id.btn_github)
        btnGithub.setOnClickListener {
            openUrl("https://github.com/Th3-R3p4ck3r/Ventoid")
        }

        dialog.show()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }
}
