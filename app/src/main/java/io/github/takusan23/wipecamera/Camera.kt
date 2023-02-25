package io.github.takusan23.wipecamera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** カメラを使ってプレビュー、録画を担当する */
class Camera(
    context: Context,
    private val cameraId: String,
    private val previewSurface: Surface,
    private val recorderSurface: Surface
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraDevice: CameraDevice? = null

    /** カメラを準備する */
    suspend fun waitOpenCamera() {
        val cameraDevice = waitOpenCamera(cameraId)
        this.cameraDevice = cameraDevice
    }

    /** カメラをプレビューモードにする。[recorderSurface]には何も映らなくなる */
    fun startPreview() {
        val cameraDevice = cameraDevice ?: return
        val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
        }.build()
        val outputList = buildList {
            add(OutputConfiguration(previewSurface))
        }

        SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputList, cameraExecutor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                captureSession.setRepeatingRequest(captureRequest, null, null)
            }

            override fun onConfigureFailed(p0: CameraCaptureSession) {

            }
        }).apply { cameraDevice.createCaptureSession(this) }
    }

    /** カメラを録画モードにする */
    suspend fun startRecord() = suspendCoroutine { continuation ->
        val cameraDevice = cameraDevice!!
        val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(recorderSurface)
        }.build()
        val outputList = buildList {
            add(OutputConfiguration(previewSurface))
            add(OutputConfiguration(recorderSurface))
        }

        SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputList, cameraExecutor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                captureSession.setRepeatingRequest(captureRequest, null, null)
                continuation.resume(Unit)
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
    private suspend fun waitOpenCamera(cameraId: String) = withContext(Dispatchers.Main) {
        suspendCoroutine { continuation ->
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    continuation.resume(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    // do nothing
                }

                override fun onError(camera: CameraDevice, p1: Int) {
                    // do nothing
                }
            }, null)
        }
    }

}