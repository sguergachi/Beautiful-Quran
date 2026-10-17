package com.beautifulquran.data.account

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.beautifulquran.QuranApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationResponse

/** A dedicated activity keeps the hosted OAuth callback away from reader and Assistant deep links. */
class QfSignInActivity : ComponentActivity() {
    private val account get() = (application as QuranApp).qfAccount
    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        result ->
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val response = result.data?.let(AuthorizationResponse::fromIntent)
                    if (response == null) account.signInFailed() else account.completeSignIn(response)
                } catch (_: Exception) {
                    account.signInFailed()
                }
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) lifecycleScope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { account.signInIntent() }
                login.launch(intent)
            } catch (_: Exception) {
                withContext(Dispatchers.IO) { account.signInFailed() }
                finish()
            }
        }
    }
}
