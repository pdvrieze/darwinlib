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

@file:Suppress("DEPRECATION")

package nl.adaptivity.android.darwin

import android.app.Activity
import android.app.DownloadManager
import android.app.Fragment
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.experimental.CancellationException
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import nl.adaptivity.android.coroutines.Maybe
import nl.adaptivity.android.coroutines.ParcelableContinuation
import nl.adaptivity.android.darwinlib.R
import nl.adaptivity.android.kotlin.bundle
import nl.adaptivity.android.kotlin.set
import java.io.File
import kotlin.coroutines.experimental.Continuation

/**
 * Fragment that encapsulates the state of downloading a file.
 *
 * TODO Actually handle the case where download completed when the activity is in the background.
 */
class DownloadFragment(): Fragment() {
    var downloadReference = -1L
    private var continuation: ParcelableContinuation<Uri?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { continuation = it.getParcelable(KEY_CONTINUATION) }
        savedInstanceState?.apply { downloadReference = getLong(KEY_DOWNLOAD_REFERENCE, -1L) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_DOWNLOAD_REFERENCE, downloadReference)
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.isActionDownloadComplete) {
                if (intent.downloadId == downloadReference) {
                    context.unregisterReceiver(this)
                    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val query = DownloadManager.Query()
                    query.setFilterById(downloadReference)
                    downloadManager.query(query).use { data ->
                        val cont = continuation
                        if (data.moveToNext()) {
                            val status = data.getInt(DownloadManager.COLUMN_STATUS)
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                cont?.resume(context, data.getUri(DownloadManager.COLUMN_LOCAL_URI))
                                continuation = null
                            } else if (status == DownloadManager.STATUS_FAILED) {
                                cont?.cancel(context)
                                continuation = null
                            }
                        }
                    }
                }
            }
        }
    }


    private fun doDownload(activity: Activity, downloadUri: Uri, fileName: String, description: String = fileName, title: String = getString(R.string.download_title)) {
        val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (downloadReference >= 0) {
            val query = DownloadManager.Query()
            query.setFilterById(downloadReference)
            val data = downloadManager.query(query)
            if (data.moveToNext()) {
                val status = data.getInt(data.getColumnIndex(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_FAILED) {
                    downloadReference = -1
                } else {// do something better
                    Toast.makeText(activity, "Download already in progress", Toast.LENGTH_SHORT).show()
                }

            } else {
                downloadReference = -1
            }
        }
        val request = DownloadManager.Request(downloadUri).apply {
            setDescription(description)
            setTitle(title)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        }
        val cacheDir = activity.externalCacheDir
        val downloadFile = File(cacheDir, fileName)
        if (downloadFile.exists()) {
            downloadFile.delete()
        }

        request.setDestinationUri(Uri.fromFile(downloadFile))
        downloadReference = downloadManager.enqueue(request)
        activity.registerReceiver(broadcastReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    companion object {
        private const val KEY_DOWNLOAD_REFERENCE = "DOWNLOAD_REFERENCE"
        private const val KEY_CONTINUATION = "_CONTINUATION_"
        private var fragNo = 0

        fun newInstance(continuation: Continuation<Uri>, context: Context?): DownloadFragment {
            return DownloadFragment().apply {
                arguments = bundle { it[KEY_CONTINUATION] = ParcelableContinuation(continuation, context) }
            }
        }

        /**
         * Download the resource at [downloadUri] and return a URI of the local location
         */
        suspend fun download(activity: Activity, downloadUri: Uri): Uri {
            return suspendCancellableCoroutine<Uri> { cont ->
                val frag = newInstance(cont, activity)
                activity.fragmentManager.beginTransaction().add(frag, nextTag()).commit()
                activity.runOnUiThread {
                    activity.fragmentManager.executePendingTransactions()
                    frag.doDownload(activity, downloadUri, fileName = "darwin-auth.apk")
                }
            }
        }

        /**
         * Async version of [download] that has a callback instead of being a suspend function.
         */
        fun download(activity: Activity, downloadUri: Uri, callback: (Maybe<Uri>) -> Unit) {
            launch {
                try {
                    download(activity, downloadUri).also { callback(Maybe.Ok(it)) }
                } catch (e: CancellationException) {
                    callback(Maybe.cancelled())
                } catch (e: Exception) {
                    callback(Maybe.error(e))
                }
            }
        }

        private fun nextTag(): String? {
            fragNo++
            return "__DOWNLOAD_FRAGMENT_$fragNo"
        }
    }

}


internal inline var Intent.downloadId: Long
    get() = getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
    set(value) { extras.putLong(DownloadManager.EXTRA_DOWNLOAD_ID, value) }

internal inline val Intent.isActionDownloadComplete get() = action == DownloadManager.ACTION_DOWNLOAD_COMPLETE

internal fun Cursor.getInt(columnName:String) = getInt(getColumnIndex(columnName))
internal fun Cursor.getUri(columnName:String) = Uri.parse(getString(getColumnIndex(columnName)))
