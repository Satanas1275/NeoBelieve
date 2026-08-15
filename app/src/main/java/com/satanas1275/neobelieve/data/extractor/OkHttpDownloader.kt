package com.satanas1275.neobelieve.data.extractor

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response as NPResponse

/**
 * NewPipeExtractor a besoin qu'on lui fournisse notre propre client HTTP.
 * On réutilise OkHttp (déjà dans les deps) plutôt que d'ajouter une lib de plus.
 */
class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): NPResponse {
        val builder = Request.Builder().url(request.url())

        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }

        val body = request.dataToSend()?.toRequestBody()
        builder.method(request.httpMethod(), body)

        client.newCall(builder.build()).execute().use { response ->
            val bodyString = response.body?.string() ?: ""
            return NPResponse(
                response.code,
                response.message,
                response.headers.toMultimap(),
                bodyString,
                response.request.url.toString(),
            )
        }
    }

    companion object {
        val instance: OkHttpDownloader by lazy {
            OkHttpDownloader(
                OkHttpClient.Builder()
                    .followRedirects(true)
                    .build(),
            )
        }
    }
}
