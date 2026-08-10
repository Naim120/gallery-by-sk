package com.sk.gallery.auth

import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.services.drive.Drive

sealed interface AuthState {
    object Unauthenticated : AuthState
    object Authenticating : AuthState
    data class Authenticated(
        val account: GoogleSignInAccount,
        val driveService: Drive
    ) : AuthState
    data class Error(val message: String) : AuthState
}
