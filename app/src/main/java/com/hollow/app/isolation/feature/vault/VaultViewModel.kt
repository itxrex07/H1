package com.hollow.app.isolation.feature.vault

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hollow.app.isolation.data.entity.VaultFileItem
import com.hollow.app.isolation.data.repository.IsolationConfigRepository
import com.hollow.app.isolation.data.repository.SettingsRepository
import com.hollow.app.isolation.data.repository.VaultPreviewResult
import com.hollow.app.isolation.data.repository.VaultRepository
import com.hollow.app.isolation.pkg.InstalledAppInfo
import com.hollow.app.isolation.util.onFailure
import com.hollow.app.isolation.util.onSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class VaultViewModel(
    private val vaultRepository: VaultRepository,
    private val configRepository: IsolationConfigRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _previewDecryptedText = MutableStateFlow<Pair<String, String>?>(null)
    val previewDecryptedText: StateFlow<Pair<String, String>?> = _previewDecryptedText.asStateFlow()

    private val _previewDecryptedBitmap = MutableStateFlow<Pair<String, Bitmap>?>(null)
    val previewDecryptedBitmap: StateFlow<Pair<String, Bitmap>?> = _previewDecryptedBitmap.asStateFlow()

    private val _operationProgress = MutableStateFlow<Float?>(null)
    val operationProgress: StateFlow<Float?> = _operationProgress.asStateFlow()

    val vaultFiles: StateFlow<List<VaultFileItem>> = vaultRepository.vaultFiles.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val totalEncryptedBytes: StateFlow<Long> = vaultRepository.totalEncryptedBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val isolatedCount: StateFlow<Int> = configRepository.isolatedCount.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    val allInstalledApps: StateFlow<List<InstalledAppInfo>> = configRepository.allInstalledApps

    fun clearPreview() {
        _previewDecryptedText.value = null
        _previewDecryptedBitmap.value = null
    }

    fun addNote(fileName: String, content: String) {
        viewModelScope.launch {
            vaultRepository.addNote(fileName, content)
                .onSuccess { name -> settingsRepository.postStatusMessage("Saved encrypted note: $name") }
                .onFailure { err -> settingsRepository.postStatusMessage("Failed to save note: ${err.message}") }
        }
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            _operationProgress.value = 0f
            vaultRepository.importFile(uri) { progress ->
                _operationProgress.value = progress
            }
                .onSuccess { name -> settingsRepository.postStatusMessage("Encrypted $name to Safe Folder") }
                .onFailure { err -> settingsRepository.postStatusMessage("Encryption error: ${err.message}") }
            _operationProgress.value = null
        }
    }

    fun encryptLocalFileToLocker(
        file: File,
        deleteOriginal: Boolean = false
    ) {
        viewModelScope.launch {
            _operationProgress.value = 0f
            vaultRepository.encryptLocalFileToLocker(file, deleteOriginal) { progress ->
                _operationProgress.value = progress
            }
                .onSuccess { name -> settingsRepository.postStatusMessage("Locked $name in Safe Folder") }
                .onFailure { err -> settingsRepository.postStatusMessage("Failed to encrypt ${file.name}: ${err.message}") }
            _operationProgress.value = null
        }
    }

    fun exportFile(fileItem: VaultFileItem, destUri: Uri) {
        viewModelScope.launch {
            _operationProgress.value = 0f
            vaultRepository.exportFile(fileItem, destUri) { progress ->
                _operationProgress.value = progress
            }
                .onSuccess { name -> settingsRepository.postStatusMessage("Exported $name") }
                .onFailure { err -> settingsRepository.postStatusMessage("Export error: ${err.message}") }
            _operationProgress.value = null
        }
    }

    fun decryptVaultFileToWorkspace(fileItem: VaultFileItem) {
        viewModelScope.launch {
            _operationProgress.value = 0f
            vaultRepository.decryptVaultFileToWorkspace(fileItem) { progress ->
                _operationProgress.value = progress
            }
                .onSuccess { cleanName ->
                    settingsRepository.postStatusMessage("Decrypted $cleanName to Hollow Drive/Decrypted")
                }
                .onFailure { err ->
                    settingsRepository.postStatusMessage("Decrypt failed: ${err.message}")
                }
            _operationProgress.value = null
        }
    }

    fun deleteVaultFile(fileItem: VaultFileItem) {
        viewModelScope.launch {
            vaultRepository.deleteVaultFile(fileItem, secureShred = true)
                .onSuccess { name -> settingsRepository.postStatusMessage("Securely shredded $name from Safe Folder") }
                .onFailure { err -> settingsRepository.postStatusMessage("Delete failed: ${err.message}") }
        }
    }

    fun decryptAndPreviewFile(fileItem: VaultFileItem) {
        viewModelScope.launch {
            vaultRepository.decryptAndPreviewFile(fileItem)
                .onSuccess { preview ->
                    when (preview) {
                        is VaultPreviewResult.ImagePreview -> {
                            _previewDecryptedBitmap.value = preview.title to preview.bitmap
                            _previewDecryptedText.value = null
                        }
                        is VaultPreviewResult.TextPreview -> {
                            _previewDecryptedText.value = preview.title to preview.content
                            _previewDecryptedBitmap.value = null
                        }
                    }
                }
                .onFailure { err ->
                    settingsRepository.postStatusMessage("Decryption failed: ${err.message}")
                }
        }
    }
}
