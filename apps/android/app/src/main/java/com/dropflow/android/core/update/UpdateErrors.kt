package com.dropflow.android.core.update

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

fun updateErrorMessage(error: Exception): String = when (error) {
    is UnknownHostException -> "Couldn't reach the update server. Check your internet connection and try again."
    is SocketTimeoutException -> "The update server took too long to respond. Please try again."
    is SSLException -> "Couldn't establish a secure connection. Check your device date, time and connection."
    is IOException -> "Couldn't download or save the update. Check your connection and free storage, then try again."
    else -> error.message?.take(300) ?: "Could not complete the update. Please try again."
}
