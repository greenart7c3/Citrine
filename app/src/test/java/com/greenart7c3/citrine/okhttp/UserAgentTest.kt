package com.greenart7c3.citrine.okhttp

import com.greenart7c3.citrine.BuildConfig
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class UserAgentTest {
    @Test
    fun outgoingRequestsCarryCitrineUserAgent() {
        var sent: String? = null
        // Runs after DefaultContentTypeInterceptor and answers locally, so no network is touched.
        val client = HttpClientManager.getHttpClient(false).newBuilder()
            .addInterceptor { chain ->
                sent = chain.request().header("User-Agent")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody())
                    .build()
            }
            .build()

        client.newCall(Request.Builder().url("http://example.invalid/").build()).execute().close()

        assertEquals("Citrine/${BuildConfig.VERSION_NAME}", sent)
    }
}
