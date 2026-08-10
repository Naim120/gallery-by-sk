package com.sk.gallery.ui.vault

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.sk.gallery.R
import com.sk.gallery.data.local.AppPreferences
import com.sk.gallery.databinding.ActivityVaultSecurityBinding

class VaultSecurityActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVaultSecurityBinding
    private lateinit var preferences: AppPreferences

    private var currentPin = ""
    private var isSetupMode = false
    private var setupStep = 0 // 0: enter new pin, 1: confirm pin
    private var tempPin = ""
    private var isLaunchingVault = false
    
    private var isChangeMode = false
    private var changeStep = 0 // 0: enter old pin, 1: enter new pin, 2: confirm new pin
    
    companion object {
        const val EXTRA_CHANGE_PIN = "extra_change_pin"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setTheme(R.style.Theme_GalleryBySK_PrivateSafe)
        binding = ActivityVaultSecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = AppPreferences(this)

        isSetupMode = preferences.getVaultPin() == null
        isChangeMode = intent.getBooleanExtra(EXTRA_CHANGE_PIN, false)

        if (isChangeMode) {
            binding.tvTitle.text = "Change PIN"
            binding.tvSubtitle.text = "Enter current PIN"
            binding.btnPinFingerprint.visibility = View.INVISIBLE
        } else if (isSetupMode) {
            binding.tvTitle.text = "Setup Private Safe"
            binding.tvSubtitle.text = "Create a 4-digit PIN"
            binding.btnPinFingerprint.visibility = View.INVISIBLE
        } else {
            binding.tvTitle.text = "Private Safe"
            binding.tvSubtitle.text = "Enter your PIN"
            checkAndEnableBiometrics()
        }

        setupNumpad()
    }

    private fun checkAndEnableBiometrics() {
        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS) {
            
            if (!preferences.isVaultBiometricsEnabled()) {
                // If they haven't explicitly enabled it, we could ask, but for simplicity we will just show the button and let them use it, and save the preference
                preferences.setVaultBiometricsEnabled(true)
            }

            binding.btnPinFingerprint.visibility = View.VISIBLE
            binding.btnPinFingerprint.setOnClickListener {
                showBiometricPrompt()
            }
            
            // Auto show prompt on open
            showBiometricPrompt()
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    unlockSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // If they cancel, they can just use PIN
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Private Safe")
            .setSubtitle("Use your fingerprint or face to unlock")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun setupNumpad() {
        val buttons = listOf(
            binding.btnPin0, binding.btnPin1, binding.btnPin2, binding.btnPin3,
            binding.btnPin4, binding.btnPin5, binding.btnPin6, binding.btnPin7,
            binding.btnPin8, binding.btnPin9
        )

        for (i in 0..9) {
            buttons[i].setOnClickListener {
                appendPin(i.toString())
            }
        }

        binding.btnPinDelete.setOnClickListener {
            if (currentPin.isNotEmpty()) {
                currentPin = currentPin.dropLast(1)
                updatePinUI()
            }
        }
    }

    private fun appendPin(digit: String) {
        if (currentPin.length < 4) {
            currentPin += digit
            updatePinUI()

            if (currentPin.length == 4) {
                handlePinComplete()
            }
        }
    }

    private fun updatePinUI() {
        binding.tvError.visibility = View.INVISIBLE
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4)
        for (i in 0..3) {
            if (i < currentPin.length) {
                dots[i].setBackgroundResource(R.drawable.bg_pin_dot_filled)
            } else {
                dots[i].setBackgroundResource(R.drawable.bg_pin_dot_empty)
            }
        }
    }

    private fun resetPinUI() {
        currentPin = ""
        updatePinUI()
    }

    private fun handlePinComplete() {
        if (isChangeMode) {
            if (changeStep == 0) {
                val savedPin = preferences.getVaultPin()
                if (currentPin == savedPin) {
                    changeStep = 1
                    binding.tvSubtitle.text = "Enter new PIN"
                    binding.tvError.visibility = View.INVISIBLE
                    resetPinUI()
                } else {
                    binding.tvError.text = "Incorrect current PIN"
                    binding.tvError.visibility = View.VISIBLE
                    resetPinUI()
                }
            } else if (changeStep == 1) {
                tempPin = currentPin
                changeStep = 2
                binding.tvSubtitle.text = "Confirm new PIN"
                binding.tvError.visibility = View.INVISIBLE
                resetPinUI()
            } else if (changeStep == 2) {
                if (currentPin == tempPin) {
                    preferences.setVaultPin(currentPin)
                    Toast.makeText(this, "PIN successfully changed", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    binding.tvError.text = "PINs do not match"
                    binding.tvError.visibility = View.VISIBLE
                    changeStep = 1
                    binding.tvSubtitle.text = "Enter new PIN"
                    resetPinUI()
                }
            }
        } else if (isSetupMode) {
            if (setupStep == 0) {
                tempPin = currentPin
                setupStep = 1
                binding.tvSubtitle.text = "Confirm your PIN"
                resetPinUI()
            } else {
                if (currentPin == tempPin) {
                    // Save PIN
                    preferences.setVaultPin(currentPin)
                    Toast.makeText(this, "PIN setup complete", Toast.LENGTH_SHORT).show()
                    unlockSuccess()
                } else {
                    binding.tvError.text = "PINs do not match"
                    binding.tvError.visibility = View.VISIBLE
                    setupStep = 0
                    binding.tvSubtitle.text = "Create a 4-digit PIN"
                    resetPinUI()
                }
            }
        } else {
            val savedPin = preferences.getVaultPin()
            if (currentPin == savedPin) {
                unlockSuccess()
            } else {
                binding.tvError.text = "Incorrect PIN"
                binding.tvError.visibility = View.VISIBLE
                resetPinUI()
            }
        }
    }

    private fun unlockSuccess() {
        isLaunchingVault = true
        startActivity(Intent(this, PrivateSafeActivity::class.java))
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isLaunchingVault && !isFinishing) {
            finishAffinity()
        }
    }
}
