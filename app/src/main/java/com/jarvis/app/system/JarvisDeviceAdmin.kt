package com.jarvis.app.system

import android.app.admin.DeviceAdminReceiver

/** Exists so `dpm set-device-owner` can target this package. It is not active on a normal phone. */
class JarvisDeviceAdmin : DeviceAdminReceiver()
