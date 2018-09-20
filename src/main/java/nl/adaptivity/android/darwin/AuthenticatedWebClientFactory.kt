/*
 * Copyright (c) 2018.
 *
 * This file is part of ProcessManager.
 *
 * ProcessManager is free software: you can redistribute it and/or modify it under the terms of version 2.1 of the
 * GNU Lesser General Public License as published by the Free Software Foundation.
 *
 * ProcessManager is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with ProcessManager.  If not,
 * see <http://www.gnu.org/licenses/>.
 */

package nl.adaptivity.android.darwin

import android.Manifest
import android.accounts.*
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.annotation.WorkerThread
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat
import android.support.v4.content.ContextCompat
import android.support.v4.content.FileProvider
import android.util.Log
import kotlinx.coroutines.experimental.*
import nl.adaptivity.android.coroutines.*
import nl.adaptivity.android.darwin.DarwinLibStatusEvents.*
import nl.adaptivity.android.darwinlib.R
import java.io.File
import java.io.IOException
import java.net.URI
import nl.adaptivity.android.darwin.ensureAccount as ensureAccountToplevel
import nl.adaptivity.android.darwin.tryDownloadAndInstallAuthenticator as tryDownloadAndInstallAuthenticatorTL

/**
 * A class for creating authenticated web clients
 */
object AuthenticatedWebClientFactory {

    private const val TAG = "AuthWebClientFty"

    private val DEFAULT_AUTHBASE_ARRAY = arrayOf<String?>(null)

    @Suppress("ObjectPropertyName")
    private var _notificationChannel: String? = null
    private val Context.notificationChannel: String
        @SuppressLint("NewApi")
        get() {
            _notificationChannel?.let { return it }
            val channelId = "AuthWebClient"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (manager.getNotificationChannel(channelId) != null) {
                    val channel = NotificationChannel(channelId, "Darwin Authentication",
                                                      NotificationManager.IMPORTANCE_HIGH).apply {
                        this.setShowBadge(true)
                    }
                    manager.createNotificationChannel(channel)
                }
            }
            return channelId.also { _notificationChannel = it }
        }

    /**
     * Get an authentication token. Just a forward to the actual client.
     */
    @Suppress("unused")
    @JvmStatic
    @WorkerThread
    fun getAuthToken(context: Context, authBase: URI, account: Account): String? {
        @Suppress("DEPRECATION")
        return getAuthToken(AccountManager.get(context), context, authBase, account)
    }


    @Deprecated("Use suspend version")
    @WorkerThread
    fun getAuthToken(accountManager: AccountManager, context: Context, authBase: URI?, account: Account): String? {
        @Suppress("DEPRECATION")
        if (!AuthenticatedWebClientFactory.isAccountValid(context, accountManager, account, authBase)) {
            throw AuthenticatedWebClient.InvalidAccountException()
        }

        val callback = AccountManagerCallback<Bundle> { future ->
            try {
                val bundle = future.result
                if (bundle.containsKey(AccountManager.KEY_INTENT)) {
                    val intent = bundle.get(AccountManager.KEY_INTENT) as Intent
                    if (context is Activity) {
                        context.startActivity(intent)
                    } else {
                        val pendingIntent = PendingIntent.getActivity(context, 0, intent, 0)
                        val contentInfo = context.getString(R.string.notification_authreq_contentInfo)
                        val actionLabel = context.getString(R.string.notification_authreq_action)
                        val notificationManager = NotificationManagerCompat.from(context)
                        val channel = context.notificationChannel
                        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(
                            R.drawable.ic_notification)
                            .setContentInfo(contentInfo)
                            .setContentIntent(pendingIntent)
                            .addAction(R.drawable.ic_notification, actionLabel, pendingIntent)
                            .build()
                        notificationManager.notify(0, notification)
                    }
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "The requested account does not exist")
                throw AuthenticatedWebClient.InvalidAccountException(e)
            } catch (e: OperationCanceledException) {
                Log.d(TAG, "Failed to get account", e)
            } catch (e: AuthenticatorException) {
                Log.d(TAG, "Failed to get account", e)
            } catch (e: IOException) {
                Log.d(TAG, "Failed to get account", e)
            }
        }
        val result: AccountManagerFuture<Bundle>
        result = accountManager.getAuthToken(account, AuthenticatedWebClient.ACCOUNT_TOKEN_TYPE, null, false, callback,
                                             null)

        val bundle: Bundle
        try {
            bundle = result.result
        } catch (e: AuthenticatorException) {
            Log.e(TAG, "Error logging in: ", e)
            return null
        } catch (e: IOException) {
            Log.e(TAG, "Error logging in: ", e)
            return null
        } catch (e: OperationCanceledException) {
            Log.e(TAG, "Error logging in: ", e)
            return null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "No such account: ", e)
            return null
        }

        return bundle.getString(AccountManager.KEY_AUTHTOKEN)
    }


    @Suppress("unused")
    @JvmStatic
    fun getAuthBase(mBase: URI?): URI? {
        return mBase?.resolve("/accountmgr/")
    }

    internal fun getSharedPreferences(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    @Suppress("unused")
    @JvmStatic
    fun getStoredAccount(context: Context): Account? {
        val preferences = getSharedPreferences(context)
        val accountName = preferences.getString(AuthenticatedWebClient.KEY_ACCOUNT_NAME, null)
        return if (accountName == null) null else Account(accountName, AuthenticatedWebClient.ACCOUNT_TYPE)
    }

    @Suppress("unused")
    @JvmStatic
    fun setStoredAccount(context: Context, account: Account?) {
        val preferences = getSharedPreferences(context)
        if (account == null) {
            preferences.edit().remove(AuthenticatedWebClient.KEY_ACCOUNT_NAME).apply()
        } else {
            preferences.edit().putString(AuthenticatedWebClient.KEY_ACCOUNT_NAME, account.name).apply()
        }
    }

    @Suppress("unused")
    @WorkerThread
    @JvmStatic
    @Deprecated("Use suspend version", ReplaceWith("AccountManager.get(context).isAccountValid(account, authBase)"))
    fun isAccountValid(context: Context, account: Account, authBase: URI?): Boolean {
        val accountManager = AccountManager.get(context)
        @Suppress("DEPRECATION")
        return isAccountValid(context, accountManager, account, authBase)
    }

    @SuppressLint("MissingPermission")
    @WorkerThread
    @JvmStatic
    @Deprecated("Use suspend version", ReplaceWith("accountManager.isAccountValid(account, authBase)"))
    fun isAccountValid(context: Context, accountManager: AccountManager, account: Account?, authBase: URI?): Boolean {
        if (ContextCompat.checkSelfPermission(context,
                                              Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED) {
            @Suppress("DEPRECATION")
            return isAccountValid(accountManager, account, authBase)
        } else { // If we are not able to enumerate the accounts use a workaround instead by attempting to get the auth token.

            val result = accountManager.getAuthToken(account, AuthenticatedWebClient.ACCOUNT_TOKEN_TYPE, null, false,
                                                     null,
                                                     null)

            try {
                result.result
            } catch (e: Exception) {
                when (e) {
                    is IllegalArgumentException,
                    is IOException,
                    is OperationCanceledException,
                    is AuthenticatorException -> {
                        Log.e(TAG, "Error logging in: ", e)
                        return false
                    }
                    else                      -> throw e
                }
            }

            return true
        }
    }

    @JvmStatic
    fun hasAuthenticator(context: Context): Boolean {
        return hasAuthenticator(AccountManager.get(context))
    }


    @WorkerThread
    @JvmStatic
    @Deprecated("Use suspend version", ReplaceWith("am.isAccountValid(account, authBase)"))
    fun isAccountValid(am: AccountManager, account: Account?, authBase: URI?): Boolean {
        if (account == null) return false

        try {
            return am.hasFeatures(account, AuthenticatedWebClientFactory.accountFeatures(authBase), null, null).result
        } catch (e: AuthenticatorException) {
            return false
        }
    }

    @JvmStatic
    fun hasAuthenticator(am: AccountManager): Boolean {
        return am.authenticatorTypes.any { AuthenticatedWebClient.ACCOUNT_TYPE == it.type }
    }


    internal fun accountFeatures(authbase: URI?): Array<String?> =
        authbase?.let { arrayOf(it.toASCIIString()) } ?: DEFAULT_AUTHBASE_ARRAY

    /**
     * Create a new intent that allow the account to be selected.
     * @param context The context to use
     * @param account A current account hint (passed to [AccountManager.newChooseAccountIntent]
     * @param authbase The authentication base to use
     */
    @JvmStatic
    @JvmOverloads
    fun selectAccount(context: Context, account: Account?, authbase: URI?, promptAlways: Boolean = false): Intent {
        val options = authbase?.let {
            Bundle(1).apply {
                putString(AuthenticatedWebClient.KEY_AUTH_BASE, it.toASCIIString())
            }
        }

        @Suppress("DEPRECATION")
        return AccountManager.newChooseAccountIntent(account,
                                                     null,
                                                     arrayOf(AuthenticatedWebClient.ACCOUNT_TYPE),
                                                     promptAlways,
                                                     context.getString(R.string.descriptionOverrideText),
                                                     null,
                                                     accountFeatures(authbase), options)
    }

    @JvmStatic
    fun handleSelectAcountActivityResult(context: Context, resultCode: Int, resultData: Intent?): Account? {
        if (resultCode == Activity.RESULT_OK) {
            val accountName = resultData?.accountName
            val accountType = resultData?.accountType
            if (accountName != null && accountType != null) {
                return Account(accountName, accountType).also { account ->
                    setStoredAccount(context, account)
                }
            } else {
                setStoredAccount(context, null)
            }

        } // else ignore
        return getStoredAccount(context) // Just return the stored account instead.
    }

    @Deprecated("Use toplevel version", ReplaceWith("nl.adaptivity.android.darwin.ensureAccount(authBase)"))
    suspend fun ActivityCoroutineScope<*, *>.ensureAccount(authBase: URI?): Maybe<Account?> {
        return ensureAccountToplevel(authBase)
    }

    @JvmStatic
    fun tryEnsureAccount(context: Activity,
                         authBase: URI?,
                         callback: SerializableHandler<Activity, Maybe<Account?>>): Job {
        return context.aLaunch {
            callback(context, ensureAccountToplevel(authBase))
        }
    }

    suspend fun ActivityCoroutineScope<*, *>.tryDownloadAndInstallAuthenticator(): Maybe<Unit> {
        return tryDownloadAndInstallAuthenticatorTL()
    }



    @JvmStatic
    fun newClient(account: Account, authbase: URI?): AuthenticatedWebClient {
        return AuthenticatedWebClientV14(account, authbase)
    }


    @JvmStatic
    fun <R> withClient(account: Account,
                       authBase: URI?,
                       body: suspend CoroutineScope.(AuthenticatedWebClient) -> R): Deferred<R> {
        return async {

            val client = newClientAsync(account, authBase)

            if (isActive) body(client) else throw CancellationException()
        }
    }

    private fun newClientAsync(account: Account, authBase: URI?): AuthenticatedWebClient {
        return AuthenticatedWebClientV14(account, authBase)
    }

}

private suspend fun ActivityCoroutineScope<*, *>.ensureAuthenticator(): Boolean {
    if (AuthenticatedWebClientFactory.hasAuthenticator(activity)) return true
    return tryDownloadAndInstallAuthenticatorTL() is Maybe.Ok<*>
}

suspend fun ActivityCoroutineScope<*, *>.tryDownloadAndInstallAuthenticator(): Maybe<Unit> {
    if (SuspDownloadDialog.newInstance(-1).show(activity,
                                                AuthenticatedWebClient.DOWNLOAD_DIALOG_TAG).flatMap() != true) {
        activity.reportStatus(AUTHENTICATOR_DOWNLOAD_REJECTED)
        return Maybe.cancelled()
    }
    if (!isActive) return Maybe.cancelled()

    val downloadedApk = DownloadFragment.download(activity, Uri.parse(AUTHENTICATOR_URL))
    if (!isActive) return Maybe.cancelled()
    activity.reportStatus(AUTHENTICATOR_DOWNLOAD_SUCCESS)

    val downloaded = File(URI.create(downloadedApk.toString()))

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        doInstall(
            FileProvider.getUriForFile(activity, "${activity.applicationInfo.packageName}.darwinlib.fileProvider",
                                       downloaded))
    } else {
        doInstall(Uri.fromFile(downloaded))
    }
}


suspend fun ActivityCoroutineScope<*, *>.doInstall(uri: Uri): Maybe<Unit> {
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    activity.activityResult(installIntent).also { result ->
        result.onError {
            activity.reportStatus(AUTHENTICATOR_INSTALL_ERROR)
            return this
        }
    }

    return if (AuthenticatedWebClientFactory.hasAuthenticator(AccountManager.get(activity))) {
        activity.reportStatus(AUTHENTICATOR_INSTALL_SUCCESS)
        Maybe.Ok(Unit)
    } else {
        activity.reportStatus(AUTHENTICATOR_INSTALL_CANCELLED)
        Maybe.cancelled()
    }
}

suspend fun ContextedCoroutineScope<*, *>.isAccountValid(account: Account?, authBase: URI?): Boolean {
    if (account == null) return false

    try {
        return account.hasFeatures(AuthenticatedWebClientFactory.accountFeatures(authBase))
    } catch (e: AuthenticatorException) {
        return false
    }
}

internal const val AUTHENTICATOR_URL = "https://darwin.bournemouth.ac.uk/darwin-auth.apk"


suspend fun ActivityCoroutineScope<*, *>.ensureAccount(authBase: URI?): Maybe<Account?> {
    // If we have a stored, valid, account, just return it
    AuthenticatedWebClientFactory.getStoredAccount(activity)?.let { account ->
        if (isAccountValid(account, authBase)) {
            return Maybe.Ok(account)
        } else { // Not valid, forget the account
            AuthenticatedWebClientFactory.setStoredAccount(activity, null)
        }
    }
    if (!ensureAuthenticator()) return Maybe.cancelled()
    val selectAccountIntent = AuthenticatedWebClientFactory.selectAccount(activity, null, authBase)
    return activity.activityResult(selectAccountIntent)
        .apply {
            activity.reportStatus(select(ACCOUNT_SELECTED, ACCOUNT_SELECTION_CANCELLED, ACCOUNT_SELECTION_FAILED))
        }
        .map { it?.account?.also { AuthenticatedWebClientFactory.setStoredAccount(activity, it) } }
}
