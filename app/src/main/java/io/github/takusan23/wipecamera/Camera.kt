package io.github.takusan23.wipecamera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.view.Surface
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Camera(
    context: Context,
    private val cameraId: String,
    private val outputSurface: Surface
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraDevice: CameraDevice? = null

    suspend fun setupCamera() {
        val cameraDevice = waitOpenCamera(cameraId)
        this.cameraDevice = cameraDevice

        val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(outputSurface)
        }.build()
        val outputList = listOf(OutputConfiguration(outputSurface))

        SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputList, cameraExecutor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                captureSession.setRepeatingRequest(captureRequest, null, null)
            }

            override fun onConfigureFailed(p0: CameraCaptureSession) {

            }
        }).apply { cameraDevice.createCaptureSession(this) }
    }

    /** 終了処理 */
    fun destroy() {
        cameraDevice?.close()
    }

    /** カメラを開く */
    @SuppressLint("MissingPermission")
    private suspend fun waitOpenCamera(cameraId: String) = suspendCoroutine { continuation ->
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                continuation.resume(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {

            }

            override fun onError(camera: CameraDevice, p1: Int) {

            }
        }, null)
    }

}