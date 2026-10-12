package io.github.pablocollantes.cfusage.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(message: String, val code: Int = 0) : IOException(message)

/** One suspend function per endpoint in docs/API.md. The base URL is read on every call. */
class ApiClient(
    private val baseUrl: suspend () -> String,
    val http: OkHttpClient = defaultHttpClient(),
) {
    suspend fun health(): Health = get(listOf("health"))
    suspend fun summary(): Summary = get(listOf("summary"))
    suspend fun metric(key: String, range: String): MetricDetail = get(listOf("metrics", key), "range" to range)
    suspend fun workers(range: String): List<WorkerRow> = get(listOf("workers"), "range" to range, ser = ListSerializer(WorkerRow.serializer()))
    suspend fun worker(name: String, range: String): WorkerDetail = get(listOf("workers", name), "range" to range)
    suspend fun sites(range: String): List<SiteRow> = get(listOf("sites"), "range" to range, ser = ListSerializer(SiteRow.serializer()))
    suspend fun site(host: String, range: String): SiteDetail = get(listOf("sites", host), "range" to range)
    suspend fun alerts(limit: Int = 50): List<Alert> = get(listOf("alerts"), "limit" to limit.toString(), ser = ListSerializer(Alert.serializer()))
    suspend fun ack(id: String): Alert = send("POST", listOf("alerts", id, "ack"), "{}")
    suspend fun kills(): KillState = get(listOf("kill"))
    suspend fun applyKill(target: KillTarget, reason: String?): Kill =
        send("POST", listOf("kill"), ApiJson.encodeToString(KillRequest.serializer(), KillRequest(target.kind, target.target, reason)))
    suspend fun restoreKill(id: String): Kill = send("DELETE", listOf("kill", id), null)
    suspend fun settings(): ServerSettings = get(listOf("settings"))
    suspend fun putSettings(s: ServerSettings): ServerSettings =
        send("PUT", listOf("settings"), ApiJson.encodeToString(ServerSettings.serializer(), s))

    suspend fun url(vararg segments: String): HttpUrl = buildUrl(segments.toList(), emptyArray())

    private suspend fun buildUrl(segments: List<String>, query: Array<out Pair<String, String>>): HttpUrl {
        val base = baseUrl().trimEnd('/').toHttpUrlOrNull()
            ?: throw ApiException("Server URL is not a valid https:// address")
        return base.newBuilder().apply {
            addPathSegment("api")
            addPathSegment("v1")
            // addPathSegment encodes "/" and ":" inside names like "pages:foo", so each one stays one segment.
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    }

    private suspend inline fun <reified T> get(
        segments: List<String>,
        vararg query: Pair<String, String>,
        ser: KSerializer<T> = serializer(),
    ): T = execute(Request.Builder().url(buildUrl(segments, query)).get().build(), ser)

    private suspend inline fun <reified T> send(method: String, segments: List<String>, body: String?): T {
        val rb = body?.toRequestBody(JSON) ?: if (method == "DELETE") null else "".toRequestBody(JSON)
        val req = Request.Builder().url(buildUrl(segments, emptyArray())).method(method, rb).build()
        return execute(req, serializer())
    }

    private suspend fun <T> execute(req: Request, ser: KSerializer<T>): T = withContext(Dispatchers.IO) {
        val text = http.newCall(req).await().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { ApiJson.decodeFromString(ApiError.serializer(), body).error }.getOrNull()
                throw ApiException(msg ?: "HTTP ${resp.code}", resp.code)
            }
            body
        }
        ApiJson.decodeFromString(ser, text)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) = cont.resume(response)
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
