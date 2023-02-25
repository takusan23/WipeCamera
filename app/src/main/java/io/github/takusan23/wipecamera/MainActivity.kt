package io.github.takusan23.wipecamera

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.takusan23.wipecamera.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


class MainActivity : AppCompatActivity(), SurfaceTexture.OnFrameAvailableListener {

    private val viewBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    /**
     * Surface で描画している OpenGL の管理するやつ
     *
     * Index 0 = Preview
     * Index 1 = Recorder
     */
    private val surfaceInputOpenGlList = mutableListOf<SurfaceInputOpenGl>()

    /**
     * [SurfaceTexture] の配列
     *
     * Index 0 = Preview OpenGL バックカメラ
     * Index 1 = Preview OpenGL フロントカメラ
     * Index 2 = Record OpenGL バックカメラ
     * Index 3 = Record OpenGL フロントカメラ
     */
    private val surfaceTextureList = mutableListOf<SurfaceTexture>()

    /** [CameraManager] */
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    /**
     * カメラ
     *
     * Index 0 = バックカメラ
     * Index 1 = フロントカメラ
     */
    private val useCameraList = mutableListOf<Camera>()

    /** ファイルの保存先 */
    private var mediaRecorderFile: File? = null

    /** [MediaRecorder] */
    private val mediaRecorder by lazy {
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()).apply {
            setMediaRecorderParams(this)
        }
    }

    /** 権限リクエストする */
    private val permissionRequester = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.all { it.value }) {
            setupCamera()
        }
    }

    /** [SurfaceTexture.OnFrameAvailableListener]が呼び出されたら更新されます */
    private var latestUpdate = 0L

    /** 録画中は true */
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        // SystemUI Hide
        supportActionBar?.hide()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        // 権限チェック
        if (
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            setupCamera()
        } else {
            // 権限を求める
            permissionRequester.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
        }

        // 録画する
        viewBinding.recordButton.setOnClickListener {
            lifecycleScope.launch {
                if (!isRecording) {
                    Toast.makeText(this@MainActivity, "録画開始", Toast.LENGTH_SHORT).show()
                    viewBinding.recordButton.setImageResource(R.drawable.outline_stop_24)
                    startRecord()
                    isRecording = true
                } else {
                    // 終了する
                    isRecording = false
                    mediaRecorder.stop()
                    mediaRecorder.reset()
                    // MediaStore (ギャラリーアプリ) に登録する
                    MediaStoreTool.insertVideo(this@MainActivity, mediaRecorderFile!!)
                    mediaRecorderFile?.delete()
                    // 次の録画用に
                    setMediaRecorderParams(mediaRecorder)
                    Toast.makeText(this@MainActivity, "終了", Toast.LENGTH_SHORT).show()
                    viewBinding.recordButton.setImageResource(R.drawable.outline_videocam_24)
                }
            }
        }
    }

    /**
     * SurfaceTexture (カメラのフレーム) が更新されたら呼ばれる
     * OpenGL で加工して Surface に流す
     */
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        // ここは UIスレッド なので重たい処理はしないこと
        // レンダリングは別スレッドに移動させてあります
        latestUpdate = System.currentTimeMillis()
    }

    override fun onDestroy() {
        super.onDestroy()
        // リソース開放
        surfaceInputOpenGlList.forEach { it.release() }
        surfaceTextureList.forEach { it.release() }
        useCameraList.forEach { it.destroy() }
        // 使わなかったファイルを削除（録画のためにファイルを作ったが、実際は録画しなかった）
        if (mediaRecorderFile?.length() == 0L) {
            mediaRecorderFile?.delete()
        }
    }

    /** カメラの初期化をする */
    private fun setupCamera() {
        lifecycleScope.launch(Dispatchers.IO) {
            // プレビュー用の Surface
            val previewSurface = waitSurface().surface
            // 録画用の Surface
            val recorderSurface = mediaRecorder.surface

            // Surface の数だけ CameraGlRenderer 作る
            val previewGlRenderer = CameraGlRenderer(
                rotation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 90f else 0f,
                // プレビュー用に作った SurfaceTexture
                onRequestBackCameraSurfaceTexture = { surfaceTextureList[0] },
                onRequestFrontCameraSurfaceTexture = { surfaceTextureList[1] }
            )
            val recorderGlRenderer = CameraGlRenderer(
                rotation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 90f else 0f,
                // 録画用に作った SurfaceTexture
                onRequestBackCameraSurfaceTexture = { surfaceTextureList[2] },
                onRequestFrontCameraSurfaceTexture = { surfaceTextureList[3] }
            )

            // プレビューSurface 録画Surface それぞれ OpenGL のソースを用意する
            surfaceInputOpenGlList.clear()
            val previewSurfaceInputOpenGl = SurfaceInputOpenGl(previewSurface, previewGlRenderer)
            val recorderSurfaceInputOpenGl = SurfaceInputOpenGl(recorderSurface, recorderGlRenderer)
            surfaceInputOpenGlList.addAll(listOf(previewSurfaceInputOpenGl, recorderSurfaceInputOpenGl))

            // SurfaceTexture を用意
            // makeCurrent を呼ぶと GLThread のエラーが消える？
            previewSurfaceInputOpenGl.makeCurrent()
            val previewSurfaceTexturePair = previewGlRenderer.setupProgram().let { (backCameraTextureId, frontCameraTextureId) ->
                // バックカメラ、フロントカメラ それぞれ SurfaceTexture を作成する
                // SurfaceTexture の場合は setDefaultBufferSize で解像度の設定ができる
                val back = SurfaceTexture(backCameraTextureId).apply {
                    setDefaultBufferSize(CAMERA_RESOLTION_WIDTH, CAMERA_RESOLTION_HEIGHT)
                    setOnFrameAvailableListener(this@MainActivity)
                }
                val front = SurfaceTexture(frontCameraTextureId).apply {
                    setDefaultBufferSize(CAMERA_RESOLTION_WIDTH, CAMERA_RESOLTION_HEIGHT)
                    setOnFrameAvailableListener(this@MainActivity)
                }
                back to front
            }

            recorderSurfaceInputOpenGl.makeCurrent()
            val recorderSurfaceTexturePair = recorderGlRenderer.setupProgram().let { (backCameraTextureId, frontCameraTextureId) ->
                val back = SurfaceTexture(backCameraTextureId).apply {
                    setDefaultBufferSize(CAMERA_RESOLTION_WIDTH, CAMERA_RESOLTION_HEIGHT)
                    setOnFrameAvailableListener(this@MainActivity)
                }
                val front = SurfaceTexture(frontCameraTextureId).apply {
                    setDefaultBufferSize(CAMERA_RESOLTION_WIDTH, CAMERA_RESOLTION_HEIGHT)
                    setOnFrameAvailableListener(this@MainActivity)
                }
                back to front
            }
            // リソース開放用に配列に入れておく
            surfaceTextureList.clear()
            surfaceTextureList.addAll(previewSurfaceTexturePair.toList() + recorderSurfaceTexturePair.toList())

            // カメラを開いて、配列に入れる
            val (backCameraId, frontCameraId) = getCameraIdData()
            useCameraList.clear()
            useCameraList += Camera(this@MainActivity, backCameraId, Surface(previewSurfaceTexturePair.first), Surface(recorderSurfaceTexturePair.first))
            useCameraList += Camera(this@MainActivity, frontCameraId, Surface(previewSurfaceTexturePair.second), Surface(recorderSurfaceTexturePair.second))
            useCameraList.forEach { it.waitOpenCamera() }
            // プレビューを開始する
            val (backCamera, frontCamera) = useCameraList
            backCamera.startPreview()
            frontCamera.startPreview()

            // OpenGL で Surface にレンダリングをする
            // なんでここでやっているのかというと
            // onFrameAvailable が UIスレッド で呼ばれます。
            // そこに MediaRecorder の重たい処理が入ると UI が固まってしまいます、、、
            // なので、新しいフレームが来たかどうかを、別スレッドの while ループで確認してUIスレッドを止めないようにしています。
            var prevUpdate = 0L
            while (isActive) {
                // プレビュー の Surface へ描画する
                if (latestUpdate != prevUpdate) {
                    val (previewGl, recorderGl) = surfaceInputOpenGlList
                    previewGl.makeCurrent()
                    previewGl.drawFrame()
                    previewGl.swapBuffers()
                    // 次に MediaRecorder の Surface に描画する
                    if (isRecording) {
                        recorderGl.makeCurrent()
                        recorderGl.drawFrame()
                        recorderGl.swapBuffers()
                    }
                    prevUpdate = latestUpdate
                }
            }
        }
    }

    /** 録画を開始する */
    private suspend fun startRecord() = withContext(Dispatchers.Main) {
        // Camera2 API で録画をする。出力先は MediaRecorder の Surface
        val (backCamera, frontCamera) = useCameraList
        listOf(
            async { backCamera.startRecord() }, async { frontCamera.startRecord() }
        ).map { it.await() }
        mediaRecorder.start()
    }

    /** Surface の用意が終わるまで一時停止する */
    private suspend fun waitSurface() = suspendCoroutine { continuation ->
        viewBinding.previewSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                continuation.resume(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // do nothing
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // do nothing
            }
        })
    }

    /** [MediaRecorder]のパラメーターをセットする */
    private fun setMediaRecorderParams(mediaRecorder: MediaRecorder) {
        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncodingBitRate(1_000_000)
            setVideoFrameRate(30)
            setVideoSize(CAMERA_RESOLTION_WIDTH, CAMERA_RESOLTION_HEIGHT)
            setAudioSamplingRate(44_100)
            mediaRecorderFile = File(getExternalFilesDir(null), "${System.currentTimeMillis()}.mp4")
            setOutputFile(mediaRecorderFile)
            prepare()
        }
    }

    /** バックカメラ、フロントカメラのIDを取得する */
    private fun getCameraIdData(): Pair<String, String> {
        var backCameraId = ""
        var frontCameraId = ""
        for (cameraId in cameraManager.cameraIdList) {
            when (cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> backCameraId = cameraId
                CameraCharacteristics.LENS_FACING_FRONT -> frontCameraId = cameraId
            }
            // 両方揃ったら return
            if (backCameraId.isNotEmpty() && frontCameraId.isNotEmpty()) {
                break
            }
        }
        return backCameraId to frontCameraId
    }

    companion object {

        /** 720P 解像度 幅 */
        private const val CAMERA_RESOLTION_WIDTH = 1280

        /** 720P 解像度 高さ */
        private const val CAMERA_RESOLTION_HEIGHT = 720

    }

}