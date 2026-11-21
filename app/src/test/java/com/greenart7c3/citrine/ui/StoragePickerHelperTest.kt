package com.greenart7c3.citrine.ui

import android.Manifest
import android.os.Build
import com.anggrayudi.storage.contract.StoragePermissionDeniedException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StoragePickerHelperTest {
    @Test
    fun api27WithoutPermissionRequestsReadAndWriteThenOpensWhenGranted() {
        var granted = false
        var requested: Array<String>? = null
        var folderPickerCalls = 0
        var deniedCalls = 0

        val helper = StoragePickerHelper(
            sdkInt = Build.VERSION_CODES.O_MR1,
            hasLegacyStoragePermissions = { granted },
            requestLegacyStoragePermissions = { requested = it },
            launchFolderPicker = { folderPickerCalls++ },
            launchFilePicker = {},
            onPermissionDenied = { deniedCalls++ },
            setFolderSelectedCallback = {},
            setFileSelectedCallback = {},
        )

        helper.openFolderPicker()

        assertArrayEquals(
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ),
            requested,
        )
        assertEquals(0, folderPickerCalls)
        assertEquals(0, deniedCalls)

        granted = true
        helper.onPermissionsResult(
            mapOf(
                Manifest.permission.READ_EXTERNAL_STORAGE to true,
                Manifest.permission.WRITE_EXTERNAL_STORAGE to true,
            ),
        )

        assertEquals(1, folderPickerCalls)
        assertEquals(0, deniedCalls)
    }

    @Test
    fun api28WithoutPermissionRequestsBeforeOpening() {
        var requested: Array<String>? = null
        var folderPickerCalls = 0

        val helper = StoragePickerHelper(
            sdkInt = Build.VERSION_CODES.P,
            hasLegacyStoragePermissions = { false },
            requestLegacyStoragePermissions = { requested = it },
            launchFolderPicker = { folderPickerCalls++ },
            launchFilePicker = {},
            onPermissionDenied = {},
            setFolderSelectedCallback = {},
            setFileSelectedCallback = {},
        )

        helper.openFolderPicker()

        assertArrayEquals(StoragePickerHelper.LEGACY_STORAGE_PERMISSIONS, requested)
        assertEquals(0, folderPickerCalls)
    }

    @Test
    fun api27WhenPermissionDeniedNotifiesAndClearsPendingRequest() {
        var granted = false
        var folderPickerCalls = 0
        var deniedCalls = 0

        val helper = StoragePickerHelper(
            sdkInt = Build.VERSION_CODES.O_MR1,
            hasLegacyStoragePermissions = { granted },
            requestLegacyStoragePermissions = {},
            launchFolderPicker = { folderPickerCalls++ },
            launchFilePicker = {},
            onPermissionDenied = { deniedCalls++ },
            setFolderSelectedCallback = {},
            setFileSelectedCallback = {},
        )

        helper.openFolderPicker()
        helper.onPermissionsResult(
            mapOf(
                Manifest.permission.READ_EXTERNAL_STORAGE to true,
                Manifest.permission.WRITE_EXTERNAL_STORAGE to false,
            ),
        )

        assertEquals(0, folderPickerCalls)
        assertEquals(1, deniedCalls)

        // A later unrelated permission result must not reopen the picker.
        granted = true
        helper.onPermissionsResult(
            mapOf(
                Manifest.permission.READ_EXTERNAL_STORAGE to true,
                Manifest.permission.WRITE_EXTERNAL_STORAGE to true,
            ),
        )

        assertEquals(0, folderPickerCalls)
        assertEquals(1, deniedCalls)
    }

    @Test
    fun api27WhenPermissionAlreadyGrantedOpensDirectly() {
        var requested: Array<String>? = null
        var folderPickerCalls = 0

        val helper = StoragePickerHelper(
            sdkInt = Build.VERSION_CODES.O_MR1,
            hasLegacyStoragePermissions = { true },
            requestLegacyStoragePermissions = { requested = it },
            launchFolderPicker = { folderPickerCalls++ },
            launchFilePicker = {},
            onPermissionDenied = {},
            setFolderSelectedCallback = {},
            setFileSelectedCallback = {},
        )

        helper.openFolderPicker()

        assertNull(requested)
        assertEquals(1, folderPickerCalls)
    }

    @Test
    fun api29PlusOpensDirectlyWithoutRequestingLegacyPermissions() {
        var requested: Array<String>? = null
        var folderPickerCalls = 0

        val helper = StoragePickerHelper(
            sdkInt = Build.VERSION_CODES.Q,
            hasLegacyStoragePermissions = { false },
            requestLegacyStoragePermissions = { requested = it },
            launchFolderPicker = { folderPickerCalls++ },
            launchFilePicker = {},
            onPermissionDenied = {},
            setFolderSelectedCallback = {},
            setFileSelectedCallback = {},
        )

        helper.openFolderPicker()

        assertNull(requested)
        assertEquals(1, folderPickerCalls)
    }

    @Test
    fun catchesStoragePermissionDeniedExceptionFromPicker() {
        var deniedCalls = 0

        val helper = StoragePickerHelper(
            sdkInt = Build.VERSION_CODES.O_MR1,
            hasLegacyStoragePermissions = { true },
            requestLegacyStoragePermissions = {},
            launchFolderPicker = { throw StoragePermissionDeniedException() },
            launchFilePicker = {},
            onPermissionDenied = { deniedCalls++ },
            setFolderSelectedCallback = {},
            setFileSelectedCallback = {},
        )

        helper.openFolderPicker()

        assertEquals(1, deniedCalls)
    }

    @Test
    fun delegatesFilePickerAndCallbacks() {
        var filePickerCalls = 0
        var forwardedFolderCallback: ((Int, androidx.documentfile.provider.DocumentFile) -> Unit)? = null
        var forwardedFileCallback: ((Int, List<androidx.documentfile.provider.DocumentFile>) -> Unit)? = null

        val helper = StoragePickerHelper(
            sdkInt = Build.VERSION_CODES.O_MR1,
            hasLegacyStoragePermissions = { false },
            requestLegacyStoragePermissions = {},
            launchFolderPicker = {},
            launchFilePicker = { filePickerCalls++ },
            onPermissionDenied = {},
            setFolderSelectedCallback = { forwardedFolderCallback = it },
            setFileSelectedCallback = { forwardedFileCallback = it },
        )

        val folderCb: (Int, androidx.documentfile.provider.DocumentFile) -> Unit = { _, _ -> }
        val fileCb: (Int, List<androidx.documentfile.provider.DocumentFile>) -> Unit = { _, _ -> }
        helper.onFolderSelected = folderCb
        helper.onFileSelected = fileCb
        helper.openFilePicker()

        assertSame(folderCb, forwardedFolderCallback)
        assertSame(fileCb, forwardedFileCallback)
        assertEquals(1, filePickerCalls)
    }
}
