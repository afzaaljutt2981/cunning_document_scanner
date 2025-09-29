package biz.cunning.cunning_document_scanner

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.core.app.ActivityCompat
import biz.cunning.cunning_document_scanner.fallback.DocumentScannerActivity
import biz.cunning.cunning_document_scanner.fallback.constants.DocumentScannerExtra
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

/** CunningDocumentScannerPlugin */
class CunningDocumentScannerPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private var delegate: PluginRegistry.ActivityResultListener? = null
    private var binding: ActivityPluginBinding? = null
    private var pendingResult: Result? = null
    private lateinit var activity: Activity

    // Only the fallback request code is needed now
    private val START_DOCUMENT_FB_ACTIVITY: Int = 0x362737

    private lateinit var channel: MethodChannel

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "cunning_document_scanner")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        if (call.method == "getPictures") {
            val noOfPages = call.argument<Int>("noOfPages") ?: 50
            val isGalleryImportAllowed = call.argument<Boolean>("isGalleryImportAllowed") ?: false
            // NOTE: isGalleryImportAllowed is ignored by fallback; kept for API compatibility
            this.pendingResult = result
            startScan(noOfPages, isGalleryImportAllowed)
        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.activity = binding.activity
        addActivityResultListener(binding)
    }

    private fun addActivityResultListener(binding: ActivityPluginBinding) {
        this.binding = binding
        if (this.delegate == null) {
            this.delegate = PluginRegistry.ActivityResultListener { requestCode, resultCode, data ->
                if (requestCode != START_DOCUMENT_FB_ACTIVITY) {
                    return@ActivityResultListener false
                }

                var handled = false
                when (resultCode) {
                    Activity.RESULT_OK -> {
                        val error = data?.extras?.getString("error")
                        if (error != null) {
                            pendingResult?.error("ERROR", "error - $error", null)
                        } else {
                            val croppedImageResults =
                                data?.getStringArrayListExtra("croppedImageResults")?.toList()
                                    ?: let {
                                        pendingResult?.error("ERROR", "No cropped images returned", null)
                                        return@ActivityResultListener true
                                    }

                            // remove "file://" for Flutter File compatibility
                            val successResponse = croppedImageResults.map { it.removePrefix("file://") }
                            pendingResult?.success(successResponse)
                        }
                        handled = true
                    }
                    Activity.RESULT_CANCELED -> {
                        pendingResult?.success(emptyList<String>())
                        handled = true
                    }
                }

                if (handled) {
                    // avoid reuse + restore orientation
                    pendingResult = null
                    unlockOrientation()
                }
                return@ActivityResultListener handled
            }
        } else {
            binding.removeActivityResultListener(this.delegate!!)
        }
        binding.addActivityResultListener(delegate!!)
    }

    /** 🔒 Force the host Activity to portrait while scanner UI is active */
    private fun lockPortrait() {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    /** 🔓 Restore orientation policy after scanner returns */
    private fun unlockOrientation() {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        // Or: activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

    /** create intent to launch the fallback scanner with custom options */
    private fun createDocumentScanIntent(noOfPages: Int): Intent {
        val documentScanIntent = Intent(activity, DocumentScannerActivity::class.java)
        documentScanIntent.putExtra(
            DocumentScannerExtra.EXTRA_MAX_NUM_DOCUMENTS,
            noOfPages
        )
        return documentScanIntent
    }

    /** Always launch the fallback (portrait-locked) scanner */
    private fun startScan(noOfPages: Int, @Suppress("UNUSED_PARAMETER") isGalleryImportAllowed: Boolean) {
        lockPortrait()
        val intent = createDocumentScanIntent(noOfPages)
        try {
            ActivityCompat.startActivityForResult(
                this.activity,
                intent,
                START_DOCUMENT_FB_ACTIVITY,
                null
            )
        } catch (e: ActivityNotFoundException) {
            pendingResult?.error("ERROR", "FAILED TO START ACTIVITY", null)
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        // in case we are detached mid-flow
        unlockOrientation()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        addActivityResultListener(binding)
    }

    override fun onDetachedFromActivity() {
        removeActivityResultListener()
        unlockOrientation()
    }

    private fun removeActivityResultListener() {
        this.delegate?.let { this.binding?.removeActivityResultListener(it) }
    }
}
