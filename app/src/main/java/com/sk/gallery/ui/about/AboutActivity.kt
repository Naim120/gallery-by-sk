package com.sk.gallery.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.sk.gallery.R

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val etWallet = findViewById<TextInputEditText>(R.id.et_wallet_address)
        val btnCopy = findViewById<MaterialButton>(R.id.btn_copy_wallet)
        
        btnCopy.setOnClickListener {
            val walletAddress = etWallet.text?.toString() ?: return@setOnClickListener
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Web3 Wallet Address", walletAddress)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Wallet address copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }
}
