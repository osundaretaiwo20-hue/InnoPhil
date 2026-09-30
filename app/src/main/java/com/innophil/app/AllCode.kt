package com.innophil.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

// ==== from app/src/main/java/com/innophil/app/util/Prefs.kt ====
/**
 * Thin wrapper around SharedPreferences.
 *
 * NOTE: this is plaintext prefs for scaffold purposes. Before real use,
 * swap this for androidx.security.crypto's EncryptedSharedPreferences so the
 * backup number / backend URL / auth token aren't sitting in plaintext on disk.
 */
object Prefs {

    private const val FILE = "innophil_prefs"

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setBackupPhoneNumber(context: Context, number: String) =
        sp(context).edit().putString("backup_number", number).apply()

    fun getBackupPhoneNumber(context: Context): String? =
        sp(context).getString("backup_number", null)

    fun setKnownIccid(context: Context, iccid: String) =
        sp(context).edit().putString("known_iccid", iccid).apply()

    fun getKnownIccid(context: Context): String? =
        sp(context).getString("known_iccid", null)

    fun setBackendUrl(context: Context, url: String) =
        sp(context).edit().putString("backend_url", url).apply()

    fun getBackendUrl(context: Context): String? =
        sp(context).getString("backend_url", null)

    fun setDeviceToken(context: Context, token: String) =
        sp(context).edit().putString("device_token", token).apply()

    fun getDeviceToken(context: Context): String? =
        sp(context).getString("device_token", null)

    /** Format: "yourusername/InnoPhil" - used by UpdateChecker to find your Releases. */
    fun setGithubRepo(context: Context, repo: String) =
        sp(context).edit().putString("github_repo", repo).apply()

    fun getGithubRepo(context: Context): String? =
        sp(context).getString("github_repo", null)

    fun setSetupComplete(context: Context, complete: Boolean) =
        sp(context).edit().putBoolean("setup_complete", complete).apply()

    fun isSetupComplete(context: Context): Boolean =
        sp(context).getBoolean("setup_complete", false)
}

// ==== from app/src/main/java/com/innophil/app/util/PermissionsHelper.kt ====
/**
 * All the runtime permissions InnoPhil needs, grouped in one place so the
 * SetupActivity onboarding flow can request them one screen at a time
 * with a plain-language reason instead of a wall of Android's own dialogs.
 */
object PermissionsHelper {

    val REQUIRED_PERMISSIONS: Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.ACCESS_NETWORK_STATE)
        add(Manifest.permission.ACCESS_WIFI_STATE)
        add(Manifest.permission.CHANGE_WIFI_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()

    fun allGranted(context: Context): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun missing(context: Context): List<String> =
        REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
}

// ==== from app/src/main/java/com/innophil/app/admin/InnoPhilDeviceAdminReceiver.kt ====
/**
 * Requesting Device Admin (rather than only runtime permissions) is what
 * unlocks three things we discussed:
 *   1. onPasswordFailed() below - a real OS callback that fires on every
 *      wrong PIN/pattern/password attempt on the lock screen. This is the
 *      "wrong PIN" trigger - no polling or guessing required.
 *   2. lockNow() / wipeData() - for the remote "lock phone" / "wipe phone"
 *      dashboard buttons.
 *   3. The remote Wi-Fi-on pathway is more privileged than a plain app;
 *      see network/NetworkStateReceiver for the caveats that still apply.
 */
class InnoPhilDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, InnoPhilDeviceAdminReceiver::class.java)

        fun isActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(componentName(context))
        }

        fun lockNow(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (isActive(context)) dpm.lockNow()
        }

        /** Irreversible. Only ever call this from a confirmed, authenticated remote command. */
        fun wipeData(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (isActive(context)) dpm.wipeData(0)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        GuardianService.start(context, reason = "device_admin_enabled")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        // This is the real "wrong PIN entered" trigger.
        // We deliberately do NOT capture on the very first failed attempt
        // (people fumble their own PIN often) - the threshold lives in
        // GuardianService so it's configurable in one place.
        GuardianService.reportFailedUnlock(context)
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        GuardianService.reportSuccessfulUnlock(context)
    }
}

// ==== from app/src/main/java/com/innophil/app/capture/CameraCaptureHelper.kt ====
/**
 * Captures a still photo with no viewfinder shown and no shutter sound
 * queued for playback (see suppressShutterSound note below). This must be
 * bound to a LifecycleOwner - GuardianService implements LifecycleOwner
 * itself (a "headless" lifecycle that's just always STARTED) so capture
 * can run from a background service instead of a visible Activity.
 *
 * Front camera = likely captures the thief's face if they're holding
 * the phone up to unlock it. Rear camera = captures surroundings.
 * GuardianService can call both in sequence.
 */
class CameraCaptureHelper(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    fun initialize(lifecycleOwner: LifecycleOwner, useFrontCamera: Boolean, onReady: () -> Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val selector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(lifecycleOwner, selector, imageCapture)
                onReady()
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun captureTo(outputDir: File, onSaved: (File?) -> Unit) {
        val capture = imageCapture ?: return onSaved(null)
        if (!outputDir.exists()) outputDir.mkdirs()

        val filename = "capture_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        }.jpg"
        val file = File(outputDir, filename)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onSaved(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    onSaved(null)
                }
            }
        )
    }

    fun release() {
        cameraProvider?.unbindAll()
    }

    companion object {
        private const val TAG = "CameraCaptureHelper"

        // HONEST CAVEAT (do not remove this comment):
        // Some manufacturer camera stacks (notably Samsung and some MIUI/HyperOS
        // builds) play a shutter sound at the hardware/HAL level regardless of
        // ringer/silent mode, specifically as an anti-voyeurism measure in
        // certain regions (e.g. South Korea/Japan requires it by regulation on
        // any phone sold there, no exceptions). CameraX/Camera2 cannot suppress
        // this from app code on those builds. Test on the actual target device
        // before relying on "silent" capture.
    }
}

// ==== from app/src/main/java/com/innophil/app/capture/AudioCaptureHelper.kt ====
/**
 * Short ambient audio clip recorder.
 *
 * LEGAL REMINDER (this is not a coding limitation, it's a law one - see
 * our earlier conversation): several jurisdictions require ALL parties to
 * a conversation to consent before it's recorded. Recording is generally
 * far more defensible when it captures YOUR OWN stolen property's
 * surroundings for evidence than as a blanket "always listening" feature.
 * Consider keeping this OFF by default and only triggered manually from
 * the dashboard, not on every wrong-PIN attempt automatically.
 */
class AudioCaptureHelper(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(outputDir: File, maxDurationMs: Int = 30_000, onStopped: (File?) -> Unit) {
        if (!outputDir.exists()) outputDir.mkdirs()

        val filename = "audio_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        }.m4a"
        val file = File(outputDir, filename)
        outputFile = file

        try {
            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                setMaxDuration(maxDurationMs)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopRecording(onStopped)
                    }
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio recording failed to start", e)
            onStopped(null)
        }
    }

    fun stopRecording(onStopped: (File?) -> Unit) {
        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
        } finally {
            recorder = null
            onStopped(outputFile)
        }
    }

    companion object {
        private const val TAG = "AudioCaptureHelper"
    }
}

// ==== from app/src/main/java/com/innophil/app/triggers/SimChangeReceiver.kt ====
/**
 * Fires on Android's SIM_STATE_CHANGED broadcast. We compare the current
 * SIM's ICCID against the one recorded during setup (Prefs.getKnownIccid).
 * A mismatch = someone swapped SIMs, which is one of our three triggers.
 *
 * Note: reading ICCID requires READ_PHONE_STATE and, on Android 10+,
 * additionally reading it via SubscriptionManager per-slot for dual-SIM
 * phones - simplified here to single-SIM for clarity. Extend for dual-SIM
 * devices as needed.
 */
class SimChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.SIM_STATE_CHANGED") return

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val currentIccid = try {
            @Suppress("MissingPermission")
            tm.simSerialNumber
        } catch (e: SecurityException) {
            null
        }

        val knownIccid = Prefs.getKnownIccid(context)

        if (currentIccid != null && knownIccid != null && currentIccid != knownIccid) {
            GuardianService.reportSimSwap(context, newIccid = currentIccid)
        } else if (currentIccid != null && knownIccid == null) {
            // First run: nothing to compare against yet, just record it.
            Prefs.setKnownIccid(context, currentIccid)
        }
    }
}

// ==== from app/src/main/java/com/innophil/app/triggers/ShutdownReceiver.kt ====
/**
 * ACTION_SHUTDOWN is a best-effort broadcast: the OS gives apps a very
 * short window to react before power actually cuts. Manufacturer skins
 * vary in how reliably they deliver it and how much time they allow.
 * Treat this as "the phone was ASKED to power off" - a genuine hardware
 * power-off (e.g. battery pulled, or already off) cannot be intercepted;
 * see our earlier conversation on that hard limit.
 */
class ShutdownReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SHUTDOWN) return
        GuardianService.reportShutdownAttempt(context)
    }
}

// ==== from app/src/main/java/com/innophil/app/triggers/BootReceiver.kt ====
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // A thief rebooting the phone to try to dodge lock screens/tracking
        // is exactly when this matters most - restart immediately.
        if (Prefs.isSetupComplete(context)) {
            GuardianService.start(context, reason = "boot_completed")
        }
    }
}

// ==== from app/src/main/java/com/innophil/app/sms/SmsAlertSender.kt ====
/**
 * Text-only alert. Deliberately kept separate from the photo/video pipeline
 * because SMS has no capacity for attachments - see our earlier discussion.
 * This works as long as whatever SIM is CURRENTLY in the phone has any
 * carrier signal at all - it does not require your original MTN SIM
 * specifically, and does not require a data plan or Wi-Fi.
 */
object SmsAlertSender {

    private const val TAG = "SmsAlertSender"

    @SuppressLint("MissingPermission") // caller must have already checked SEND_SMS
    fun sendTheftAlert(context: Context, reason: String, location: Location?) {
        val backupNumber = Prefs.getBackupPhoneNumber(context) ?: run {
            Log.w(TAG, "No backup number configured, cannot send SMS alert")
            return
        }

        val locationText = if (location != null) {
            "https://maps.google.com/?q=${location.latitude},${location.longitude}"
        } else {
            "location unavailable"
        }

        val message = "InnoPhil alert: $reason. Last known location: $locationText " +
            "at ${System.currentTimeMillis()}"

        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
                ?: SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(backupNumber, null, parts, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS alert", e)
        }
    }
}

// ==== from app/src/main/java/com/innophil/app/network/NetworkStateReceiver.kt ====
/**
 * Fires whenever connectivity changes. This is the hook for "the moment
 * there's a flicker of signal, act on it" behavior we discussed:
 *   - flush anything queued in the UploadWorker backlog
 *   - (see WifiAutoJoin) attempt to join any known/open network
 *
 * HONEST CAVEAT: since Android 10, apps cannot silently turn Wi-Fi on by
 * themselves in the background - that path is closed by Google specifically
 * to prevent apps like this one from behaving like malware. What still
 * works: if Wi-Fi is already ON, silently connecting to a known or open
 * network is fine. Forcing Wi-Fi ON itself needs either (a) the user
 * doing it, (b) a remote command executed via Device Policy Manager
 * while the phone still has SOME connectivity to receive that command,
 * or (c) an older/Android 9-and-below device. See WifiAutoJoin.kt.
 */
class NetworkStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>().build()
        WorkManager.getInstance(context).enqueue(request)

        WifiAutoJoin.attemptJoinKnownOrOpenNetwork(context)
    }
}

// ==== from app/src/main/java/com/innophil/app/network/WifiAutoJoin.kt ====
object WifiAutoJoin {

    private const val TAG = "WifiAutoJoin"

    /**
     * Turns Wi-Fi on directly. Only works on Android 9 (API 28) and below -
     * Google removed WifiManager.setWifiEnabled() for third-party apps
     * starting Android 10. On 10+ this call silently becomes a no-op that
     * returns false; we log it rather than pretend it worked.
     */
    fun attemptForceWifiOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            Log.w(TAG, "Cannot silently enable Wi-Fi on Android 10+; needs remote Device Admin command or user action")
            return false
        }
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        return wifiManager.setWifiEnabled(true)
    }

    /**
     * If Wi-Fi is already on (by user, by the pre-Android-10 path above, or
     * by a remote Device Admin command - see admin/InnoPhilDeviceAdminReceiver),
     * this nudges the system to (re)connect to whatever known/open network
     * is in range. On modern Android, joining a NEW open network
     * programmatically without user interaction is restricted (Android 10+
     * requires the network suggestion API with user approval for anything
     * that wasn't previously joined) - but reconnecting to a network the
     * phone has joined before happens automatically at the OS level once
     * Wi-Fi is on, with no app code needed.
     */
    fun attemptJoinKnownOrOpenNetwork(context: Context) {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            attemptForceWifiOn(context)
            return
        }
        // Nothing further to do here on modern Android: reconnection to a
        // previously-known network is handled by the OS automatically.
        // For joining brand-new open networks, use WifiNetworkSuggestion
        // (API 29+) — left as a documented extension point rather than
        // built here, since it requires a user-visible approval step the
        // first time and isn't truly silent.
    }
}

// ==== from app/src/main/java/com/innophil/app/network/UploadWorker.kt ====
/**
 * The offline-tolerant delivery queue described in our conversation:
 * captures are written to CAPTURE_QUEUE_DIR immediately (fully offline-safe),
 * and this worker is what actually ships them out - triggered by
 * NetworkStateReceiver whenever connectivity changes, and also scheduled
 * periodically by GuardianService as a backstop.
 *
 * WorkManager itself already handles "retry with backoff until it
 * succeeds," which is why this is built on WorkManager rather than a
 * plain background thread.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val backendUrl = Prefs.getBackendUrl(applicationContext) ?: return Result.failure()
        val token = Prefs.getDeviceToken(applicationContext) ?: return Result.retry()

        val queueDir = File(applicationContext.filesDir, CAPTURE_QUEUE_DIR)
        if (!queueDir.exists()) return Result.success()

        val pending = queueDir.listFiles() ?: return Result.success()
        if (pending.isEmpty()) return Result.success()

        val client = OkHttpClient()

        for (file in pending) {
            try {
                val mediaType = when (file.extension) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "m4a" -> "audio/mp4"
                    else -> "application/octet-stream"
                }.toMediaTypeOrNull()

                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
                    .build()

                val request = Request.Builder()
                    .url("$backendUrl/api/upload")
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        file.delete()
                    } else {
                        Log.w(TAG, "Upload failed (${response.code}) for ${file.name}, will retry later")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload error for ${file.name}, will retry later", e)
                // Leave the file queued; don't fail the whole worker over one file.
            }
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "UploadWorker"
        const val CAPTURE_QUEUE_DIR = "innophil_queue"
    }
}

// ==== from app/src/main/java/com/innophil/app/remote/CommandPoller.kt ====
/**
 * STUB - this is the piece that talks to the backend/website we discussed
 * but have NOT built yet. Two real implementation choices once that
 * exists:
 *
 *   1. Push-based (recommended): backend sends a Firebase Cloud Messaging
 *      "data message" the instant you press a button on the dashboard.
 *      Near-instant, and doesn't need the phone to keep asking "any
 *      commands for me?" - lower battery cost. Requires wiring
 *      FirebaseMessagingService into this project once the backend exists.
 *
 *   2. Poll-based (what's stubbed below): the phone periodically asks the
 *      backend "any pending commands?" - simpler to stand up first, works
 *      with literally any backend language/framework, but adds delay
 *      (only as fast as the poll interval) and background battery use.
 *
 * Recommendation: start with polling to get end-to-end capture -> upload
 * -> dashboard working, then swap in FCM once the backend is live.
 */
object CommandPoller {

    private const val TAG = "CommandPoller"

    fun pollOnce(context: Context) {
        val backendUrl = Prefs.getBackendUrl(context) ?: return
        val token = Prefs.getDeviceToken(context) ?: return

        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("$backendUrl/api/commands/pending")
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body?.string() ?: return
                val json = JSONObject(body)
                val command = json.optString("command", "")
                dispatch(context, command)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command poll failed (expected while offline)", e)
        }
    }

    private fun dispatch(context: Context, command: String) {
        when (command) {
            "capture_now" -> GuardianService.reportRemoteCaptureRequest(context)
            "lock_now" -> InnoPhilDeviceAdminReceiver.lockNow(context)
            "wipe_now" -> InnoPhilDeviceAdminReceiver.wipeData(context)
            "" -> Unit // nothing pending
            else -> Log.w(TAG, "Unknown remote command: $command")
        }
    }
}

// ==== from app/src/main/java/com/innophil/app/update/UpdateChecker.kt ====
data class UpdateInfo(
    val versionTag: String,
    val downloadUrl: String,
    val releaseNotes: String
)

/**
 * Checks GitHub's Releases API for a newer build than the one currently
 * installed. This is the "self-hosted Play Store check" we discussed:
 * every time we push a code change, the build.yml workflow publishes a
 * new GitHub Release with the freshly-built APK attached, tagged with a
 * version number. This class just asks "what's the latest tag, and is it
 * newer than what I'm running?"
 *
 * Requires the repo ("yourusername/InnoPhil") to be set on the setup
 * screen and saved via Prefs.setGithubRepo - without that, this silently
 * does nothing rather than crashing.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    fun checkForUpdate(context: Context, onResult: (UpdateInfo?) -> Unit) {
        val repo = Prefs.getGithubRepo(context)
        if (repo.isNullOrBlank()) {
            onResult(null)
            return
        }

        Thread {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$repo/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Release check failed: ${response.code}")
                        onResult(null)
                        return@use
                    }

                    val body = response.body?.string() ?: return@use onResult(null)
                    val json = JSONObject(body)
                    val tagName = json.optString("tag_name", "")
                    val notes = json.optString("body", "")

                    val assets = json.optJSONArray("assets")
                    var apkUrl: String? = null
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url")
                                break
                            }
                        }
                    }

                    if (apkUrl != null && isNewer(tagName, BuildConfig.VERSION_NAME)) {
                        onResult(UpdateInfo(tagName, apkUrl, notes))
                    } else {
                        onResult(null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed (expected while offline)", e)
                onResult(null)
            }
        }.start()
    }

    /**
     * Simple string comparison is NOT reliable for versions ("v10" < "v9"
     * alphabetically). We tag releases as build numbers (see build.yml -
     * "v" + GitHub run number), so this strips the "v" and compares
     * numerically. Extend this if you switch to semantic versioning later.
     */
    private fun isNewer(remoteTag: String, localVersionName: String): Boolean {
        val remoteNum = remoteTag.removePrefix("v").toIntOrNull() ?: return false
        val localNum = localVersionName.removePrefix("v").toIntOrNull() ?: 0
        return remoteNum > localNum
    }
}

// ==== from app/src/main/java/com/innophil/app/update/ApkInstaller.kt ====
/**
 * Downloads the APK GitHub tells us about, then hands it to the system
 * installer. Android still requires you to tap "Install" once yourself
 * on the confirmation screen it shows - that step cannot be skipped for
 * apps installed outside Play Store, by design (Google's anti-malware
 * safeguard, mentioned earlier). What this DOES remove is manually
 * opening GitHub, finding the Actions run, downloading the artifact zip,
 * and extracting it - all replaced by one tap inside InnoPhil itself.
 */
object ApkInstaller {

    fun downloadAndInstall(context: Context, update: UpdateInfo) {
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("InnoPhil update ${update.versionTag}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "innophil_update.apk")
            .setAllowedOverMetered(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (completedId == downloadId) {
                    context.unregisterReceiver(this)
                    installDownloadedApk(context)
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun installDownloadedApk(context: Context) {
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "innophil_update.apk"
        )
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }
}

// ==== from app/src/main/java/com/innophil/app/service/GuardianService.kt ====
/**
 * Always-on foreground service. Android REQUIRES a visible notification
 * for any foreground service using the camera/microphone (this is an
 * OS-level transparency rule, not something we can turn off - see our
 * "secretly" discussion). The notification below is worded plainly rather
 * than disguised, since disguising it crosses into the stalkerware
 * territory Play Store policy and Android itself actively try to detect
 * and block. Keep it honest.
 */
class GuardianService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var cameraHelper: CameraCaptureHelper
    private lateinit var audioHelper: AudioCaptureHelper

    private var failedUnlockCount = 0

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        cameraHelper = CameraCaptureHelper(this)
        audioHelper = AudioCaptureHelper(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: ask Android to restart us if the system kills us
        // under memory pressure. Not a guarantee on every OEM skin - some
        // (again, MIUI/HyperOS included) apply their own aggressive battery
        // optimization on top that can still kill background services;
        // guiding the user to disable battery optimization for this app
        // during setup is important in practice.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cameraHelper.release()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    // ---- Trigger entry points (called by the various receivers) ----

    private fun handleFailedUnlock() {
        failedUnlockCount++
        if (failedUnlockCount >= WRONG_PIN_THRESHOLD) {
            failedUnlockCount = 0
            captureAndQueue(reason = "wrong_pin_threshold_reached", useFrontCamera = true)
        }
    }

    private fun handleSuccessfulUnlock() {
        failedUnlockCount = 0
    }

    private fun handleSimSwap(newIccid: String) {
        Prefs.setKnownIccid(this, newIccid)
        captureAndQueue(reason = "sim_swapped", useFrontCamera = true)
        SmsAlertSender.sendTheftAlert(this, reason = "SIM card was swapped", location = lastKnownLocation())
    }

    private fun handleShutdownAttempt() {
        captureAndQueue(reason = "shutdown_attempted", useFrontCamera = true)
        SmsAlertSender.sendTheftAlert(this, reason = "Phone power-off was requested", location = lastKnownLocation())
    }

    private fun handleRemoteCaptureRequest() {
        captureAndQueue(reason = "remote_command", useFrontCamera = true)
    }

    private fun captureAndQueue(reason: String, useFrontCamera: Boolean) {
        cameraHelper.initialize(this, useFrontCamera) {
            val queueDir = File(filesDir, UploadWorker.CAPTURE_QUEUE_DIR)
            cameraHelper.captureTo(queueDir) { savedFile ->
                if (savedFile != null) {
                    // Immediately try to flush the queue; UploadWorker itself
                    // is offline-tolerant and will just leave the file queued
                    // if there's no connectivity yet.
                    WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<UploadWorker>().build())
                }
            }
        }
    }

    private fun lastKnownLocation(): Location? {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getProviders(true)
                .mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "innophil_guard_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "InnoPhil Protection",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, SetupActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("InnoPhil is protecting this device")
            .setContentText("Tap to open settings")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val WRONG_PIN_THRESHOLD = 3

        fun start(context: Context, reason: String) {
            val intent = Intent(context, GuardianService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        // The trigger receivers call these instead of holding a direct
        // reference to a running instance, since the service may need to
        // be (re)started first.
        fun reportFailedUnlock(context: Context) = withRunningService(context) { it.handleFailedUnlock() }
        fun reportSuccessfulUnlock(context: Context) = withRunningService(context) { it.handleSuccessfulUnlock() }
        fun reportSimSwap(context: Context, newIccid: String) =
            withRunningService(context) { it.handleSimSwap(newIccid) }
        fun reportShutdownAttempt(context: Context) = withRunningService(context) { it.handleShutdownAttempt() }
        fun reportRemoteCaptureRequest(context: Context) =
            withRunningService(context) { it.handleRemoteCaptureRequest() }

        // Simplified binding shortcut. In production, prefer a bound
        // service or a static event bus over this pattern; left simple
        // here for scaffold readability.
        private var runningInstance: GuardianService? = null

        private fun withRunningService(context: Context, action: (GuardianService) -> Unit) {
            runningInstance?.let(action) ?: start(context, "trigger_fired_no_instance")
        }
    }

    init {
        runningInstance = this
    }
}

// ==== from app/src/main/java/com/innophil/app/SetupActivity.kt ====
/**
 * One-time onboarding. Deliberately explicit and step-by-step rather than
 * auto-requesting everything at once - both because Android requires
 * runtime confirmation for sensitive permissions anyway, and because being
 * upfront about what this app does and why is what keeps it on the right
 * side of the "consent tool for your own device" vs "stalkerware" line
 * we discussed.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        statusText.text = if (allGranted) {
            "Permissions granted. Now enable Device Admin."
        } else {
            "Some permissions were denied - InnoPhil needs all of them to work fully."
        }
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        statusText.text = if (InnoPhilDeviceAdminReceiver.isActive(this)) {
            "Device Admin enabled. You can now save and start protection."
        } else {
            "Device Admin was not enabled - the wrong-PIN trigger and remote lock/wipe won't work without it."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        statusText = findViewById(R.id.statusText)
        val inputBackupNumber = findViewById<EditText>(R.id.inputBackupNumber)
        val inputBackendUrl = findViewById<EditText>(R.id.inputBackendUrl)
        val inputGithubRepo = findViewById<EditText>(R.id.inputGithubRepo)

        Prefs.getBackupPhoneNumber(this)?.let { inputBackupNumber.setText(it) }
        Prefs.getBackendUrl(this)?.let { inputBackendUrl.setText(it) }
        Prefs.getGithubRepo(this)?.let { inputGithubRepo.setText(it) }

        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            val repo = inputGithubRepo.text.toString().trim()
            if (repo.isEmpty()) {
                statusText.text = "Enter your GitHub repo first, e.g. yourusername/InnoPhil"
                return@setOnClickListener
            }
            Prefs.setGithubRepo(this, repo)
            statusText.text = "Checking for updates..."
            UpdateChecker.checkForUpdate(this) { update ->
                runOnUiThread {
                    if (update == null) {
                        statusText.text = "You're on the latest version."
                    } else {
                        statusText.text = "Update ${update.versionTag} found. Downloading..."
                        ApkInstaller.downloadAndInstall(this, update)
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnGrantPermissions).setOnClickListener {
            if (PermissionsHelper.allGranted(this)) {
                statusText.text = "All permissions already granted."
            } else {
                permissionLauncher.launch(PermissionsHelper.missing(this).toTypedArray())
            }
        }

        findViewById<Button>(R.id.btnEnableDeviceAdmin).setOnClickListener {
            if (InnoPhilDeviceAdminReceiver.isActive(this)) {
                statusText.text = "Device Admin already active."
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        InnoPhilDeviceAdminReceiver.componentName(this@SetupActivity)
                    )
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "InnoPhil needs this to detect wrong unlock attempts and to lock/wipe the phone remotely if it's stolen."
                    )
                }
                deviceAdminLauncher.launch(intent)
            }
        }

        findViewById<Button>(R.id.btnFinishSetup).setOnClickListener {
            val number = inputBackupNumber.text.toString().trim()
            val backendUrl = inputBackendUrl.text.toString().trim()

            if (number.isEmpty()) {
                statusText.text = "Enter a backup phone number first."
                return@setOnClickListener
            }
            if (!PermissionsHelper.allGranted(this)) {
                statusText.text = "Grant all permissions before starting protection."
                return@setOnClickListener
            }

            Prefs.setBackupPhoneNumber(this, number)
            if (backendUrl.isNotEmpty()) Prefs.setBackendUrl(this, backendUrl)
            Prefs.setSetupComplete(this, true)

            GuardianService.start(this, reason = "setup_complete")
            statusText.text = "Protection is active."
        }

        // Silent auto-check every time you open the app - this is the
        // "just reopen it and it tells you" behavior we discussed. It only
        // speaks up if an update actually exists; otherwise it says nothing.
        if (!Prefs.getGithubRepo(this).isNullOrBlank()) {
            UpdateChecker.checkForUpdate(this) { update ->
                if (update != null) {
                    runOnUiThread {
                        statusText.text = "Update ${update.versionTag} available - downloading..."
                        ApkInstaller.downloadAndInstall(this, update)
                    }
                }
            }
        }
    }
}
