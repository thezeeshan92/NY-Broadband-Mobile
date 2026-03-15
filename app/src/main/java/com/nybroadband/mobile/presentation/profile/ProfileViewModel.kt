package com.nybroadband.mobile.presentation.profile

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class ProfileUiState(
    val displayName: String = "",
    val email: String = "",
    val isAnonymous: Boolean = true,
    val isSigningOut: Boolean = false
)

sealed interface ProfileUiEvent {
    data object SignOutComplete : ProfileUiEvent
    data class Error(val message: String) : ProfileUiEvent
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    // TODO: inject FirebaseAuth in a future iteration
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState

    fun signOut() {
        // TODO: call FirebaseAuth.getInstance().signOut() and navigate to onboarding
        _uiState.value = _uiState.value.copy(isSigningOut = true)
    }
}
