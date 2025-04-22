package com.kenza.kenza

import android.app.Application
import com.microsoft.identity.client.ISingleAccountPublicClientApplication

class MainApplication : Application() {
    var msalInstance: ISingleAccountPublicClientApplication? = null
}