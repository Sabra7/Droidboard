package dev.phonemic.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.phonemic.camera.CameraState
import dev.phonemic.camera.CameraStreamer
import dev.phonemic.camera.YuvJpeg
import dev.phonemic.ui.RunOnce
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/** What the Camera page and [CameraKeeper] share. Compose state, so a change redraws both. */
object CameraUi {
    var granted by mutableStateOf(false)               // the camera permission
    var previewView by mutableStateOf<PreviewView?>(null)   // set while the Camera page is on screen
    var revision by mutableIntStateOf(0)               // bumped when back/front or the quality changes
}

/**
 * Owns the camera for the whole app, so it keeps sending when you leave the Camera page (Home, Deck).
 *
 * It is bound while something needs it: the page is open (for the preview) or the stream is on.
 * Leaving the app (the activity stops) ends the stream, because Android takes the camera away then.
 */
@Composable
fun CameraKeeper() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    RunOnce {
        CameraState.load(ctx)
        CameraUi.granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    var sending by remember { mutableStateOf(CameraState.sending) }
    LaunchedEffect(Unit) {
        while (true) {
            sending = CameraState.sending
            delay(250)
        }
    }

    val granted = CameraUi.granted
    val preview = CameraUi.previewView
    val revision = CameraUi.revision
    val wanted = granted && (sending || preview != null)
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(wanted, preview, revision) {
        if (!wanted) return@DisposableEffect onDispose { }
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val quality = CameraState.quality
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(quality.width, quality.height),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    if (CameraState.sending) {
                        CameraStreamer.submit(YuvJpeg.encode(image), image.imageInfo.rotationDegrees, CameraState.mirror)
                    }
                } finally {
                    image.close()
                }
            }
            val selector = if (CameraState.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll()
            if (preview != null) {
                val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                provider.bindToLifecycle(owner, selector, p, analysis)
            } else {
                provider.bindToLifecycle(owner, selector, analysis)          // no picture on screen: just the stream
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    DisposableEffect(sending) {                                              // the screen stays on while sending
        view.keepScreenOn = sending
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(owner) {                                                // the app left the screen: the camera is gone
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && CameraState.sending) {
                CameraStreamer.stop()
                CameraState.status = "Stopped: the app left the screen"
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
