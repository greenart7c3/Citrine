package com.greenart7c3.citrine.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.SimpleStorageHelper
import com.anggrayudi.storage.contract.StoragePermissionDeniedException
import com.greenart7c3.citrine.R

/**
 * Wraps [SimpleStorageHelper] so folder-picker calls request legacy storage permissions on
 * Android <= 9 (API <= 28) before invoking `SimpleStorageHelper.openFolderPicker()`.
 *
 * SimpleStorage 3.1.0 checks `DocumentFileCompat.hasStoragePermission()` inside
 * `OpenFolderPickerContract.createIntent()` on API <= 28 and throws
 * [StoragePermissionDeniedException], but `SimpleStorage.openFolderPicker()` does not catch it
 * on the `ComponentActivity` path.
 */
class StoragePickerHelper internal constructor(
    private val sdkInt: Int,
    private val hasLegacyStoragePermissions: () -> Boolean,
    private val requestLegacyStoragePermissions: (Array<String>) -> Unit,
    private val launchFolderPicker: () -> Unit,
    private val launchFilePicker: () -> Unit,
    private val onPermissionDenied: () -> Unit,
    private val setFolderSelectedCallback: (((requestCode: Int, folder: DocumentFile) -> Unit)?) -> Unit,
    private val setFileSelectedCallback: (((requestCode: Int, files: List<DocumentFile>) -> Unit)?) -> Unit,
) {
    private var pendingFolderPicker = false

    var onFolderSelected: ((requestCode: Int, folder: DocumentFile) -> Unit)? = null
        set(value) {
            field = value
            setFolderSelectedCallback(value)
        }

    var onFileSelected: ((requestCode: Int, files: List<DocumentFile>) -> Unit)? = null
        set(value) {
            field = value
            setFileSelectedCallback(value)
        }

    fun openFolderPicker() {
        if (sdkInt <= Build.VERSION_CODES.P && !hasLegacyStoragePermissions()) {
            pendingFolderPicker = true
            requestLegacyStoragePermissions(LEGACY_STORAGE_PERMISSIONS)
            return
        }
        launchFolderPickerSafely()
    }

    fun openFilePicker() {
        launchFilePicker()
    }

    internal fun onPermissionsResult(results: Map<String, Boolean>) {
        if (!pendingFolderPicker) return
        pendingFolderPicker = false
        val allGranted = LEGACY_STORAGE_PERMISSIONS.all { results[it] == true } && hasLegacyStoragePermissions()
        if (allGranted) {
            launchFolderPickerSafely()
        } else {
            onPermissionDenied()
        }
    }

    private fun launchFolderPickerSafely() {
        try {
            launchFolderPicker()
        } catch (_: StoragePermissionDeniedException) {
            onPermissionDenied()
        }
    }

    companion object {
        internal val LEGACY_STORAGE_PERMISSIONS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )

        fun create(activity: ComponentActivity): StoragePickerHelper {
            @Suppress("DEPRECATION")
            val simpleStorageHelper = SimpleStorageHelper(activity)
            lateinit var helper: StoragePickerHelper
            val permissionLauncher = activity.registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { results ->
                helper.onPermissionsResult(results)
            }
            helper = StoragePickerHelper(
                sdkInt = Build.VERSION.SDK_INT,
                hasLegacyStoragePermissions = {
                    LEGACY_STORAGE_PERMISSIONS.all { permission ->
                        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
                    }
                },
                requestLegacyStoragePermissions = { permissions ->
                    permissionLauncher.launch(permissions)
                },
                launchFolderPicker = {
                    simpleStorageHelper.openFolderPicker()
                },
                launchFilePicker = {
                    simpleStorageHelper.openFilePicker()
                },
                onPermissionDenied = {
                    Toast.makeText(
                        activity,
                        R.string.storage_permission_required,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                setFolderSelectedCallback = { callback ->
                    simpleStorageHelper.onFolderSelected = callback
                },
                setFileSelectedCallback = { callback ->
                    simpleStorageHelper.onFileSelected = callback
                },
            )
            return helper
        }
    }
}
