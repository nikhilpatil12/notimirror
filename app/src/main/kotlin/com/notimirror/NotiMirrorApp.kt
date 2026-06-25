package com.notimirror

import android.app.Application
import com.notimirror.di.AppContainer

class NotiMirrorApp : Application() {
    val container by lazy { AppContainer(this) }
}
