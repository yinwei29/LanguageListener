package tw.language.listener

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tw.language.listener.authoring.*
import java.util.UUID

class AiAuthoringTest {
    private val request=AiRequest("source","飯店入住",count=3)
    private val item="""{"zh-TW":"你好","en-US":"Hello","id-ID":"Halo","pronunciation_note":""}"""
    @Test fun validatesTranslatesDeduplicatesAndAssignsItsOwnStableIds() {
        val duplicate="""{"ID":"untrusted-id","zh-TW":" 你好 ","en-US":"ＨＥＬＬＯ","id-ID":"halo","source_url":"https://not-verified.example"}"""
        val other="""{"zh-TW":"謝謝","en-US":"Thank you","id-ID":"Terima kasih"}"""
        val draft=AuthoringValidation.parse("{\"items\":[$item,$duplicate,$other]}",request)
        assertEquals(2,draft.items.size); assertEquals(1,draft.removedDuplicates)
        draft.items.forEach {
            UUID.fromString(it.itemId)
            assertEquals("source",it.sourceId); assertEquals("ai_generated_unverified",it.status); assertEquals("",it.sourceUrl)
        }
        assertFalse(draft.written)
    }
    @Test fun rejectsWrongShapesMissingTranslationsOversizedAndInvalidReview() {
        for(text in listOf("not json","{}","{\"items\":[]}","{\"items\":[{\"zh-TW\":1}]}",
            "{\"items\":[{\"en-US\":\"Hello\",\"id-ID\":\"Halo\"}]}",
            "{\"items\":[$item,$item,$item,$item]}",
            "{\"items\":[${item.replace("Hello","x".repeat(4001))}]}")) {
            try { AuthoringValidation.parse(text,request); fail("invalid AI response accepted") } catch(_: IllegalArgumentException) { }
        }
        val draft=AuthoringValidation.parse("{\"items\":[$item]}",request)
        assertThrows(IllegalArgumentException::class.java) { AuthoringValidation.validateReview(draft,emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { AuthoringValidation.validateReview(draft,listOf(draft.items.single().copy(itemId="forged"))) }
        assertThrows(IllegalArgumentException::class.java) { AuthoringValidation.validateReview(draft,listOf(draft.items.single().copy(sourceId="other"))) }
        assertThrows(IllegalArgumentException::class.java) { AuthoringValidation.validateReview(draft,listOf(draft.items.single().copy(status="verified"))) }
        AuthoringValidation.validateReview(draft,listOf(draft.items.single().copy(en="Hello there")))
    }
    @Test fun originsHaveSeparateCredentialsAndInsecureSettingsAreRejected() {
        val vault=MemoryVault(); val prefs=AiPreferences(vault)
        val a=AiProfile("compatible","https://one.example/v1","model")
        val b=a.copy(endpoint="https://two.example/v1")
        prefs.save(a,"test-key-not-real"); prefs.save(b,"")
        assertFalse(prefs.hasKey(b)); assertTrue(prefs.hasKey(a))
        assertThrows(IllegalStateException::class.java) { prefs.key(b) }
        prefs.clear(a); assertFalse(prefs.hasKey(a))
        for(url in listOf("http://one.example/v1","https://user:password@one.example/v1","https://one.example/v1?key=secret")) {
            assertThrows(IllegalArgumentException::class.java) { a.copy(endpoint=url).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { AiProfile(endpoint="https://evil.example/v1beta").validate() }
    }
    @Test fun compatibleApiUsesBearerBodyAndProducesOnlyDrafts() = runBlocking {
        AuthoringFixture().use { api ->
            val content="{\"items\":[$item]}"
            api.json(buildJsonObject { putJsonArray("choices") { add(buildJsonObject { putJsonObject("message") { put("content",content) } }) } }.toString())
            val profile=AiProfile("compatible",api.server.url("/v1").toString(),"example-model")
            val draft=AiGenerator(api.http).generate(profile,"test-key-not-real",request)
            val sent=api.server.takeRequest()
            assertEquals("/v1/chat/completions",sent.path)
            assertEquals("Bearer test-key-not-real",sent.getHeader("Authorization"))
            assertFalse(sent.path!!.contains("test-key"))
            val body=Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals("example-model",body["model"]!!.jsonPrimitive.content)
            assertTrue(body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content.contains("飯店入住"))
            assertFalse(draft.written); assertEquals(1,draft.items.size); assertEquals(1,api.server.requestCount)
        }
    }
    @Test fun geminiAndOpenAiEnvelopesAndRequestsFollowProviderSchemas() {
        val generator=AiGenerator(); val gemini=AiProfile()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",generator.url(gemini).toString())
        assertEquals("application/json",generator.body(gemini,request)["generationConfig"]!!.jsonObject["responseMimeType"]!!.jsonPrimitive.content)
        val envelope=buildJsonObject { putJsonArray("candidates") { add(buildJsonObject { putJsonObject("content") { putJsonArray("parts") {
            add(buildJsonObject { put("text","{\"items\":[$item]}") })
        } } }) } }
        assertEquals(1,generator.decodeResponse(gemini,envelope,request).items.size)
        val openai=AiProfile("openai","https://api.openai.com/v1","gpt-4o-mini")
        assertEquals("json_object",generator.body(openai,request)["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertThrows(IllegalStateException::class.java) { generator.decodeResponse(gemini,JsonObject(emptyMap()),request) }
    }
    @Test fun redirectsDoNotForwardSecretsAndErrorsDoNotEchoServerBodies() = runBlocking {
        AuthoringFixture().use { api ->
            api.server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location","https://other.example/steal"))
            try { api.http.request(api.server.url("/redirect"),"test",mapOf("Authorization" to "Bearer test-secret")); fail("redirect followed") }
            catch(e: RemoteError) { assertEquals(302,e.status); assertFalse(e.message!!.contains("test-secret")) }
            api.json("{\"echo\":\"test-secret\"}",403)
            try { api.http.request(api.server.url("/error"),"test",emptyMap()); fail("error accepted") }
            catch(e: RemoteError) { assertFalse(e.message!!.contains("test-secret")) }
            assertEquals(2,api.server.requestCount)
        }
    }
    @Test fun mutationErrorDoesNotAutomaticallyReplayEvenWithRetryAfterZero() = runBlocking {
        AuthoringFixture().use { api ->
            api.server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503).setHeader("Retry-After","0").setBody("{}"))
            api.json("{}") // would be consumed by an unsafe automatic retry
            try { api.http.request(api.server.url("/append"),"test",emptyMap(),"POST",buildJsonObject { put("value","test") }); fail("mutation was retried") }
            catch(e: RemoteError) { assertEquals(503,e.status) }
            assertEquals(1,api.server.requestCount)
        }
    }
}
