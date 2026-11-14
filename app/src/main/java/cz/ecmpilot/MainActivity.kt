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
import cz.ecmpilot.data.GatewayBle
import android.Manifest

class MainActivity : ComponentActivity() {
    private val repository get() = (application as PilotApplication).repository
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        updatePermission()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updatePermission()
        setContent { PilotApp(repository, onRequestPermission = {
            val needed = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31) needed += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else needed += Manifest.permission.ACCESS_FINE_LOCATION
            if (Build.VERSION.SDK_INT >= 37) needed += "android.permission.ACCESS_LOCAL_NETWORK"
            permissions.launch(needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray())
        }) }
    }
    private fun updatePermission() {
        repository.setPermissions(Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(this, "android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED,
            GatewayBle.permitted(this))
    }
    override fun onStart() { super.onStart(); updatePermission(); repository.setVisible(true) }
    override fun onStop() { repository.setVisible(false); super.onStop() }
}
