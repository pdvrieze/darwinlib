/*
 * Copyright (c) 2016.
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
 * You should have received a copy of the GNU Lesser General Public License along with Foobar.  If not,
 * see <http://www.gnu.org/licenses/>.
 */

package nl.adaptivity.android.darwin

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.support.annotation.CallSuper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import nl.adaptivity.android.coroutines.CoroutineActivity
import nl.adaptivity.kotlin.getValue
import nl.adaptivity.kotlin.weakLazy
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URI
import java.nio.charset.Charset


/**
 * A class making it easier to make authenticated requests to darwin.
 */
interface AuthenticatedWebClient {

    /**
     * Get the authentication base for this client
     */
    val authBase: URI?

    /**
     * Get the stored account.
     * @return The account.
     */
    val account: Account

    class InvalidAccountException : RuntimeException {
        constructor()

        @Suppress("unused")
        constructor(detailMessage: String) : super(detailMessage)

        constructor(throwable: Throwable) : super(throwable)

        @Suppress("unused")
        constructor(detailMessage: String, throwable: Throwable) : super(detailMessage, throwable)
    }

    open class WebRequest(val uri: URI) {
        private var headers: Array<String?>? = null
        var headerCount: Int = 0
            private set

        open val connection: HttpURLConnection
            @CallSuper
            @Throws(IOException::class)
            get() {
                val connection = uri.toURL().openConnection() as HttpURLConnection
                for (i in 0 until headerCount) {
                    connection.addRequestProperty(headers!![i * 2], headers!![i * 2 + 1])
                }
                return connection
            }

        init {
            val uriScheme = uri.scheme
            if (!("http" == uriScheme || "https" == uriScheme)) {
                throw IllegalArgumentException("Webrequests only work with http and https schemes")
            }
        }

        /**
         * Get the value of the header with the given name
         */
        fun getHeader(name:String): String? {
            headers?.let { headers ->
                for(i in headers.indices step 2) {
                    if (headers[i]==name) return headers[i+1]
                }
            }
            return null
        }

        /**
         * Set a header with the given [name] to the given [value]. This will not prevent duplicates
         */
        fun setHeader(name: String, value: String) {
            val headers = headers.let { h ->
                when (h) {
                // No headers
                    null -> arrayOfNulls<String>(10).also { headers = it }
                    else -> {
                        /* Short circuit for updating an existing header, not even trying to extend
                                 * in that case. */
                        for (i in h.indices step 2) {
                            if (h[i]==name) {
                                h[i+1] = value
                                return
                            }
                        }
                        when {
                        // The header array is full, we need a bigger one.
                            h.size == headerCount * 2 -> h.copyOf(h.size * 2).also { headers = it }
                            else -> h
                        }
                    }
                }
            }
            val pos = headerCount * 2
            headers[pos] = name
            headers[pos + 1] = value
            headerCount++
        }

        /**
         * Get the name of the header at the given index.
         * @param index The index of the header
         */
        @Suppress("unused")
        fun getHeaderName(index: Int): String {
            return headers?.get(index * 2) ?: throw IndexOutOfBoundsException("No headers have been defined")
        }

        /**
         * Get the value of the header at the given index.
         * @param index The index of the header
         */
        @Suppress("unused")
        fun getHeaderValue(index: Int): String {
            return headers?.get(index * 2 + 1) ?: throw IndexOutOfBoundsException("No headers have been defined")
        }
    }

    /**
     * A class representing a simple HTTP GET request.
     *
     * @param uri The URI to request
     */
    class GetRequest(uri: URI) : WebRequest(uri)

    @Suppress("unused")
    /**
     * A class representing a simple HTTP DELETE request.
     *
     * @param uri The URI to delete
     */
    class DeleteRequest(uri: URI) : WebRequest(uri) {

        override val connection: HttpURLConnection
            @Throws(ProtocolException::class)
            get() = super.connection.apply { requestMethod = "DELETE" }
    }

    @Suppress("unused")
    /**
     * A class representing a simple HTTP POST request.
     *
     * @param uri The uri to update.
     * @param writingCallback The callback that can write the post data when needed. This could be
     *                        called multiple times!
     */
    class PostRequest @JvmOverloads constructor(uri: URI, private val writingCallback: StreamWriterCallback, contentType: String? = null) : WebRequest(uri) {

        var contentType:String
            get() = getHeader("Content-type") ?: "application/binary"
            set(value) { setHeader("Content-type", value) }

        init {
            this.contentType = contentType ?: "application/binary"
        }

        override val connection: HttpURLConnection
            @Throws(IOException::class)
            get() {
                return super.connection.apply {
                    doOutput = true
                    setChunkedStreamingMode(0)
                    outputStream.use { writingCallback.writeTo(it) }
                }
            }

        /**
         * Convenience constructor for a post body that is a character sequence.
         *
         * @param uri The uri to update
         * @param body The body string. This will be converted to UTF-8 and sent as
         */
        @Suppress("unused")
        @JvmOverloads
        constructor(uri: URI, body: CharSequence, contentType: String? = null) : this(uri, CharSequenceCallback(body), contentType)

    }

    class CharSequenceCallback(val body: CharSequence) : StreamWriterCallback {
        @Throws(IOException::class)
        override fun writeTo(stream: OutputStream) {
            val out = OutputStreamWriter(stream, UTF8)
            try {
                out.append(body)
            } finally {
                out.flush() // Just flush, don't close.
            }
        }
        companion object {
            val UTF8 by weakLazy { Charset.forName("UTF-8")!! }
        }
    }

    interface StreamWriterCallback {
        @Throws(IOException::class)
        fun writeTo(stream: OutputStream)
    }

    /**
     * Execute the web request synchronously.
     */
    @Throws(IOException::class)
    fun execute(context: Context, request: WebRequest): HttpURLConnection?

    fun <A> A.execute(
        request: WebRequest,
        onSuccess: RequestSuccess
    ) where A : Activity, A : CoroutineScope =
        execute(request, false, NULL_ERROR_HANDLER, onSuccess)

    fun <A> A.execute(
        request: WebRequest,
        onError: RequestFailure,
        onSuccess: RequestSuccess
    ) where A : Activity, A : CoroutineScope =
        execute(request, false, onError, onSuccess)

    /**
     * Execute the web request. This will launch it's own worker thread so no need to worry. The
     * onSuccess will be called on a working thread. The client is responsible for invoking the UI
     * thread with results.
     *
     * @receiver The activity to use as context and to launch needed activities to provide permission.
     * @param request The request to make
     * @param currentlyInRetry Unused
     * @param onError Called when the request fails for some reason
     * @param onSuccess Called when the request was successful.
     */
    fun <A> A.execute(request: WebRequest, currentlyInRetry: Boolean, onError: RequestFailure, onSuccess: RequestSuccess): Job where A : Activity, A:CoroutineScope

    companion object {
        private val NULL_ERROR_HANDLER: RequestFailure = RequestFailure { }

        const val KEY_ACCOUNT_NAME = "ACCOUNT_NAME"

        const val ACCOUNT_TYPE = "uk.ac.bournemouth.darwin.account"

        const val ACCOUNT_TOKEN_TYPE = "uk.ac.bournemouth.darwin.auth"

        @Suppress("unused")
        const val KEY_ASKED_FOR_NEW = "askedForNewAccount"

        const val KEY_AUTH_BASE = "authbase"

        const val DARWIN_AUTH_COOKIE = "DWNID"
        const val DOWNLOAD_DIALOG_TAG = "DOWNLOAD_DIALOG"
    }
}

typealias RequestSuccess = IRequestSuccess// (HttpURLConnection)->Unit
typealias RequestFailure = IRequestFailure//(HttpURLConnection?)->Unit


@Suppress("NOTHING_TO_INLINE")
inline operator fun IRequestSuccess.invoke(connection: HttpURLConnection) = onSuccess(connection)
@Suppress("NOTHING_TO_INLINE")
inline operator fun IRequestFailure.invoke(connection: HttpURLConnection?) = onFailure(connection)
