package com.dropflow.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.dropflow.android.storage.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            userPreferencesRepository.userPreferencesFlow.collectLatest { prefs ->
                _uiState.value = SettingsUiState(
                    deviceName = prefs.deviceName,
                    requireConfirmation = prefs.requireConfirmation,
                    autoAcceptTrustedDevices = prefs.autoAcceptTrustedDevices,
                    askBeforeOverwrite = prefs.askBeforeOverwrite,
                    autoOpenCompleted = prefs.autoOpenCompleted,
                    accentColor = prefs.accentColor
                )
            }
        }
    }

    fun updateDeviceName(name: String) {
        viewModelScope.launch {
            userPreferencesRepository.updateDeviceName(name)
        }
    }

    fun updateRequireConfirmation(value: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.updateRequireConfirmation(value)
        }
    }

    fun updateAutoAcceptTrusted(value: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.updateAutoAcceptTrusted(value)
        }
    }

    fun updateAskBeforeOverwrite(value: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.updateAskBeforeOverwrite(value)
        }
    }

    fun updateAutoOpenCompleted(value: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.updateAutoOpenCompleted(value)
        }
    }

    fun updateAccentColor(color: String) {
        viewModelScope.launch {
            userPreferencesRepository.updateAccentColor(color)
        }
    }

    companion object {
        /** Factory so the instance survives configuration changes via ViewModelProvider. */
        fun factory(userPreferencesRepository: UserPreferencesRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    SettingsViewModel(userPreferencesRepository)
                }
            }
    }
}
