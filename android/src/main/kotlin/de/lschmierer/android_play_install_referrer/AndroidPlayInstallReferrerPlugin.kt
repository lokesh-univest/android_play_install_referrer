package de.lschmierer.android_play_install_referrer

import android.content.Context
import androidx.annotation.NonNull
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlin.collections.ArrayList


/** AndroidPlayInstallReferrerPlugin */
class AndroidPlayInstallReferrerPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    private val pendingResults = ArrayList<Result>(1)
    private var referrerClient: InstallReferrerClient? = null
    private var referrerDetails: ReferrerDetails? = null
    private var referrerError: Pair<String, String>? = null

    private val isInstallReferrerPending: Boolean
        @Synchronized
        get() {
            return referrerClient != null && !isInstallReferrerResolved
        }

    private val isInstallReferrerResolved: Boolean
        @Synchronized
        get() {
            return referrerDetails != null || referrerError != null
        }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        this.context = flutterPluginBinding.applicationContext
        channel = MethodChannel(
            flutterPluginBinding.binaryMessenger,
            "de.lschmierer.android_play_install_referrer")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        if (call.method == "getInstallReferrer") {
            getInstallReferrer(result)
        } else {
            result.notImplemented()
        }

    }

    @Synchronized
    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        pendingResults.clear()
        referrerClient?.endConnection()
        channel.setMethodCallHandler(null)
    }

    @Synchronized
    private fun getInstallReferrer(@NonNull result: Result) {
        if (isInstallReferrerResolved) {
            resolveInstallReferrerResult(result)
        } else {
            pendingResults.add(result)

            if(!isInstallReferrerPending) {
                referrerClient = InstallReferrerClient.newBuilder(context).build()
                referrerClient?.startConnection(object : InstallReferrerStateListener {
                    override fun onInstallReferrerSetupFinished(responseCode: Int) {
                        handleOnInstallReferrerSetupFinished(responseCode)
                    }

                    override fun onInstallReferrerServiceDisconnected() {
                        // Handle service disconnection
                        synchronized(this@AndroidPlayInstallReferrerPlugin) {
                            if (!isInstallReferrerResolved) {
                                referrerError = Pair("SERVICE_DISCONNECTED", "Install Referrer service was disconnected")
                                resolvePendingInstallReferrerResults()
                            }
                        }
                    }
                })
            }
        }
    }

    @Synchronized
    private fun handleOnInstallReferrerSetupFinished(responseCode: Int) {
        when (responseCode) {
            InstallReferrerClient.InstallReferrerResponse.OK -> {
                try {
                    referrerClient?.let { client ->
                        // Add null check and exception handling
                        val details = client.installReferrer
                        if (details != null) {
                            referrerDetails = details
                        } else {
                            referrerError = Pair("BAD_STATE", "Install referrer details are null")
                        }
                    } ?: run {
                        referrerError = Pair("BAD_STATE", "Install referrer client is null")
                    }
                } catch (e: Exception) {
                    // Handle DeadObjectException and other potential exceptions
                    when (e) {
                        is android.os.DeadObjectException -> {
                            referrerError = Pair("SERVICE_DEAD", "Install Referrer service connection is dead")
                        }
                        is SecurityException -> {
                            referrerError = Pair("PERMISSION_ERROR", "Security exception: ${e.message}")
                        }
                        is IllegalStateException -> {
                            referrerError = Pair("BAD_STATE", "Illegal state: ${e.message}")
                        }
                        else -> {
                            referrerError = Pair("UNKNOWN_ERROR", "Unexpected error: ${e.message}")
                        }
                    }
                }
            }
            InstallReferrerClient.InstallReferrerResponse.SERVICE_DISCONNECTED -> {
                referrerError = Pair("SERVICE_DISCONNECTED", "Play Store service is not connected now - potentially transient state.")
            }
            InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> {
                referrerError = Pair("SERVICE_UNAVAILABLE", "Connection couldn't be established.")
            }
            InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                referrerError = Pair("FEATURE_NOT_SUPPORTED", "API not available on the current Play Store app.")
            }
            InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR -> {
                referrerError = Pair("DEVELOPER_ERROR", "General errors caused by incorrect usage.")
            }
            InstallReferrerClient.InstallReferrerResponse.PERMISSION_ERROR -> {
                referrerError = Pair("PERMISSION_ERROR", "App is not allowed to bind to the Service.")
            }
            else -> {
                referrerError = Pair("UNKNOWN_ERROR", "InstallReferrerClient returned unknown response code: $responseCode")
            }
        }

        resolvePendingInstallReferrerResults()

        // Safely end connection
        try {
            referrerClient?.endConnection()
        } catch (e: Exception) {
            // Ignore exceptions when ending connection
        }
    }

    @Synchronized
    private fun resolvePendingInstallReferrerResults() {
        pendingResults.forEach {
            resolveInstallReferrerResult(it)
        }
        pendingResults.clear()
    }

    @Synchronized
    private fun resolveInstallReferrerResult(@NonNull result: Result) {
        referrerDetails?.let {
            try {
                result.success(
                    mapOf(
                        "installReferrer" to it.installReferrer,
                        "referrerClickTimestampSeconds" to it.referrerClickTimestampSeconds,
                        "installBeginTimestampSeconds" to it.installBeginTimestampSeconds,
                        "referrerClickTimestampServerSeconds" to it.referrerClickTimestampServerSeconds,
                        "installBeginTimestampServerSeconds" to it.installBeginTimestampServerSeconds,
                        "installVersion" to it.installVersion,
                        "googlePlayInstantParam" to it.googlePlayInstantParam
                    )
                )
                return
            } catch (e: Exception) {
                // If we can't access the referrer details, treat it as an error
                result.error("ACCESS_ERROR", "Could not access referrer details: ${e.message}", null)
                return
            }
        }
        referrerError?.let {
            result.error(it.first, it.second, null)
            return
        }
    }
}