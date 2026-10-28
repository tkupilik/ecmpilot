package cz.ecmpilot

import android.app.Application
import cz.ecmpilot.data.PilotRepository

class PilotApplication : Application() {
    val repository by lazy { PilotRepository(this) }
}
