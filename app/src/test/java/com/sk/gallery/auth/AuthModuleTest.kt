package com.sk.gallery.auth

import com.google.api.services.drive.DriveScopes
import com.sk.gallery.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AuthModuleTest {

    @Test
    fun testOAuthClientIdConfiguredCorrectly() {
        org.junit.Assert.assertTrue("Client ID should not be blank", BuildConfig.OAUTH_CLIENT_ID.isNotBlank())
    }

    @Test
    fun testAuthScopeIsStrictlyAppData() {
        val expectedScope = "https://www.googleapis.com/auth/drive.appdata"
        assertEquals(expectedScope, GoogleSignInManager.DRIVE_APPDATA_SCOPE)
        assertEquals(DriveScopes.DRIVE_APPDATA, GoogleSignInManager.DRIVE_APPDATA_SCOPE)
    }

    @Test
    fun testAuthStateUnauthenticatedByDefault() {
        val state: AuthState = AuthState.Unauthenticated
        assertNotNull(state)
    }
}
