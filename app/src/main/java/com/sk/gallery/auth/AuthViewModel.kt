package com.sk.gallery.auth

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    fun checkExistingAccount(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val account = GoogleSignInManager.getLastSignedInAccount(context)
            if (account != null) {
                try {
                    val driveService = GoogleSignInManager.getDriveService(context, account)
                    _authState.value = AuthState.Authenticated(account, driveService)
                } catch (e: Exception) {
                    _authState.value = AuthState.Error(e.message ?: "Failed to build Drive service")
                }
            } else {
                _authState.value = AuthState.Unauthenticated
            }
        }
    }

    fun handleSignInResult(data: Intent?, context: Context) {
        _authState.value = AuthState.Authenticating
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    val driveService = GoogleSignInManager.getDriveService(context, account)
                    _authState.value = AuthState.Authenticated(account, driveService)
                } else {
                    _authState.value = AuthState.Error("Sign-in account was null")
                }
            } catch (e: ApiException) {
                _authState.value = AuthState.Error("Sign-in failed with code ${e.statusCode}: ${e.message}")
            } catch (e: Exception) {
                _authState.value = AuthState.Error("Authentication error: ${e.message}")
            }
        }
    }

    fun signOut(context: Context) {
        val client = GoogleSignInManager.getGoogleSignInClient(context)
        client.signOut().addOnCompleteListener {
            _authState.value = AuthState.Unauthenticated
        }
    }
}
