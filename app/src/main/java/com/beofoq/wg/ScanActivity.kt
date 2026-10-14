package com.beofoq.wg

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

class ScanActivity : BaseActivity() {
    private lateinit var preview: TextureView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var lastTry = 0L
    private var done = false
    private val qrReader = MultiFormatReader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        preview = findViewById(R.id.preview)
        findViewById<View>(R.id.btnCloseScan).setOnClickListener { finish() }
        qrReader.setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                startCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 5)
            return
        }
        bgThread = HandlerThread("cam")
        bgThread?.start()
        bgHandler = Handler(bgThread!!.looper)
        openCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                toast(R.string.camera_perm)
                finish()
            }
        }
    }

    private fun openCamera() {
        val manager = getSystemService(CameraManager::class.java) ?: return
        try {
            var camId = manager.cameraIdList.firstOrNull() ?: return
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    camId = id
                    break
                }
            }
            val chars = manager.getCameraCharacteristics(camId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: return
            val size = sizes.filter { it.width <= 1280 }.maxByOrNull { it.width * it.height } ?: sizes.last()
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader?.setOnImageAvailableListener({ r -> decodeFrame(r) }, bgHandler)
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            manager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    createSession()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                }
            }, bgHandler)
        } catch (e: Exception) {
            toast(R.string.camera_perm)
        }
    }

    private fun createSession() {
        val device = camera ?: return
        val texture = preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(preview.width, preview.height)
        val previewSurface = Surface(texture)
        val readerSurface = reader?.surface ?: return
        try {
            device.createCaptureSession(
                listOf(previewSurface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            builder.addTarget(previewSurface)
                            builder.addTarget(readerSurface)
                            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            s.setRepeatingRequest(builder.build(), null, bgHandler)
                        } catch (e: Exception) {
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                },
                bgHandler
            )
        } catch (e: Exception) {
        }
    }

    private fun decodeFrame(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val now = System.currentTimeMillis()
            if (done || now - lastTry < 250) return
            lastTry = now
            val plane = image.planes[0]
            val buffer = plane.buffer
            val w = image.width
            val h = image.height
            val rowStride = plane.rowStride
            val data = ByteArray(w * h)
            val rowData = ByteArray(rowStride)
            for (row in 0 until h) {
                buffer.position(row * rowStride)
                buffer.get(rowData, 0, minOf(rowStride, buffer.remaining()))
                System.arraycopy(rowData, 0, data, row * w, w)
            }
            val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val text = qrReader.decodeWithState(bitmap).text
            if (!text.isNullOrBlank() && !done) {
                done = true
                runOnUiThread {
                    val intent = Intent()
                    intent.putExtra("text", text)
                    setResult(RESULT_OK, intent)
                    finish()
                }
            }
        } catch (e: Exception) {
            try {
                qrReader.reset()
            } catch (e2: Exception) {
            }
        } finally {
            image.close()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            session?.close()
            camera?.close()
            reader?.close()
        } catch (e: Exception) {
        }
        bgThread?.quitSafely()
        session = null
        camera = null
        reader = null
    }
}
