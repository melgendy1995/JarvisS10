package com.jarvis.app.system

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.core.content.ContextCompat
import com.jarvis.core.skills.TorchController
import com.jarvis.core.skills.TorchStatus

class CameraTorchController(context: Context) : TorchController {
    private val appContext = context.applicationContext
    private val cameras = appContext.getSystemService(CameraManager::class.java)

    override fun setEnabled(on: Boolean): TorchStatus {
        val granted = ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.CAMERA)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            return TorchStatus.NEEDS_PERMISSION
        }
        val id = cameraWithFlash() ?: return TorchStatus.UNAVAILABLE
        return try {
            cameras.setTorchMode(id, on)
            if (on) TorchStatus.ON else TorchStatus.OFF
        } catch (e: CameraAccessException) {
            TorchStatus.UNAVAILABLE
        } catch (e: IllegalArgumentException) {
            TorchStatus.UNAVAILABLE
        } catch (e: SecurityException) {
            TorchStatus.NEEDS_PERMISSION
        }
    }

    private fun cameraWithFlash(): String? {
        return try {
            cameras.cameraIdList.firstOrNull { id ->
                val characteristics = cameras.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: CameraAccessException) {
            null
        }
    }
}
