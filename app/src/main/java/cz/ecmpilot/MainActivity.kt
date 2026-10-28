package cz.ecmpilot

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import cz.ecmpilot.ui.PilotApp

class MainActivity : ComponentActivity() {
    private val repository get() = (application as PilotApplication).repository
    private val localPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        repository.setPermission(it)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updatePermission()
        setContent { PilotApp(repository, onRequestPermission = {
            if (Build.VERSION.SDK_INT >= 37) localPermission.launch("android.permission.ACCESS_LOCAL_NETWORK")
        }) }
    }
    private fun updatePermission() {
        repository.setPermission(Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(this, "android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED)
    }
    override fun onStart() { super.onStart(); updatePermission(); repository.setVisible(true) }
    override fun onStop() { repository.setVisible(false); super.onStop() }
}
