package tw.language.listener

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import tw.language.listener.authoring.*

class ApiFailureTest {
    @Test fun geminiInvalidKeyAndOpenAiModelOrQuotaFailuresHaveDifferentHints() {
        val invalid=ApiFailure.parse(400,"""{"error":{"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""")
        assertTrue(invalid.hint.contains("金鑰無效"))
        assertTrue(ApiFailure.parse(404,"""{"error":{"code":"model_not_found"}}""").hint.contains("找不到模型"))
        assertTrue(ApiFailure.parse(429,"""{"error":{"code":"insufficient_quota"}}""").hint.contains("額度不足"))
        assertTrue(ApiFailure.parse(400,"""{"error":{"code":"unsupported_parameter","param":"response_format"}}""").hint.contains("參數"))
    }
    @Test fun rawServerMessagesAndUnknownParametersNeverReachUi()=runBlocking {
        AuthoringFixture().use { api ->
            api.json("""{"error":{"message":"secret-test-key and private prompt","code":"secret-test-key","param":"secret-test-key"}}""",400)
            try { api.http.request(api.server.url("/v1/responses"),"AI 產生教材",mapOf("Authorization" to "Bearer secret-test-key"));fail("accepted error") }
            catch(e: RemoteError) { assertFalse(e.message!!.contains("secret-test-key"));assertFalse(e.message!!.contains("private prompt"));assertNull(e.failure!!.parameter) }
            assertEquals(1,api.server.requestCount)
        }
    }
    @Test fun allowlistedParameterIsShownWithoutEchoingErrorBody() {
        val failure=ApiFailure.parse(400,"""{"error":{"message":"do not show this","param":"max_output_tokens"}}""")
        assertEquals("max_output_tokens",failure.parameter)
        assertFalse(RemoteError(400,"AI 產生教材",failure).message!!.contains("do not show"))
    }
}
