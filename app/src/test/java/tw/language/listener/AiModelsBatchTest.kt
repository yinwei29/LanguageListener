package tw.language.listener

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tw.language.listener.authoring.*
import tw.language.listener.data.*

class AiModelsBatchTest {
    private fun item(text: String)="""{"zh-TW":"$text","en-US":"$text","id-ID":"$text","pronunciation_note":""}"""
    private fun chat(api: AuthoringFixture,text: String) { api.json(buildJsonObject { putJsonArray("choices") { add(buildJsonObject { putJsonObject("message") { put("content","{\"items\":[${item(text)}]}") } }) } }.toString()) }
    @Test fun responsesTransportUsesCorrectSchemaAndExtractsTextWithoutReasoning()=runBlocking {
        AuthoringFixture().use { api ->
            api.json(buildJsonObject { put("status","completed");putJsonArray("output") {
                add(buildJsonObject { put("type","reasoning") })
                add(buildJsonObject { put("type","message");putJsonArray("content") { add(buildJsonObject { put("type","output_text");put("text","{\"items\":[${item("Hello")}]}") }) } })
            } }.toString())
            val profile=AiProfile("compatible",api.server.url("/v1").toString(),"model",protocol="responses",outputMode="schema")
            val draft=AiGenerator(api.http).generate(profile,"test-key",AiRequest("s","topic",count=1))
            assertEquals(1,draft.items.size)
            val sent=api.server.takeRequest();assertEquals("/v1/responses",sent.path)
            val body=Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertFalse(body["store"]!!.jsonPrimitive.boolean);assertFalse(body.containsKey("messages"));assertFalse(body.containsKey("response_format"))
            val format=body["text"]!!.jsonObject["format"]!!.jsonObject
            assertEquals("json_schema",format["type"]!!.jsonPrimitive.content);assertTrue(format["strict"]!!.jsonPrimitive.boolean)
            assertFalse(format["schema"]!!.jsonObject["additionalProperties"]!!.jsonPrimitive.boolean)
        }
    }
    @Test fun incompleteAndRefusedResponsesDoNotBecomeReviewableDrafts() {
        val generator=AiGenerator();val request=AiRequest("s","topic")
        val openai=AiProfile("openai","https://api.openai.com/v1","gpt-4.1-mini")
        assertTrue(generator.url(openai).encodedPath.endsWith("/responses"))
        assertThrows(IllegalStateException::class.java) { generator.decodeResponse(openai,buildJsonObject { put("status","incomplete") },request) }
        assertThrows(IllegalStateException::class.java) { generator.decodeResponse(AiProfile(),buildJsonObject { putJsonArray("candidates") { add(buildJsonObject { put("finishReason","MAX_TOKENS") }) } },request) }
        assertThrows(IllegalStateException::class.java) { generator.decodeResponse(openai,buildJsonObject { putJsonArray("output") { add(buildJsonObject { put("type","message");putJsonArray("content") { add(buildJsonObject { put("type","refusal") }) } }) } },request) }
        assertFalse(generator.body(openai.copy(outputMode="prompt"),request).containsKey("text"))
    }
    @Test fun geminiModelsPaginateAndFilterUnsupportedGenerationMethods()=runBlocking {
        AuthoringFixture().use { api ->
            api.json("""{"models":[{"name":"models/gemini-text","supportedGenerationMethods":["generateContent"]},{"name":"models/embed","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"next"}""")
            api.json("""{"models":[{"name":"models/gemini-other","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-image","supportedGenerationMethods":["generateContent"]}]}""")
            val models=AiModels(api.http,api.server.url("/v1beta")).load(AiProfile(),"test-key")
            assertEquals(listOf("gemini-other","gemini-text"),models.map { it.id })
            val first=api.server.takeRequest();val second=api.server.takeRequest()
            assertEquals("test-key",first.getHeader("x-goog-api-key"));assertFalse(first.path!!.contains("test-key"));assertTrue(second.path!!.contains("pageToken=next"))
        }
    }
    @Test fun openAiAndCompatibleModelsUseBearerAndDoNotRequireAChosenModel()=runBlocking {
        AuthoringFixture().use { api ->
            api.json("""{"data":[{"id":"gpt-4.1-mini"},{"id":"gpt-audio"},{"id":"whisper-1"},{"id":"gpt-5-mini"}]}""")
            val models=AiModels(api.http,api.server.url("/v1")).load(AiProfile("openai","https://api.openai.com/v1",""),"test-key")
            assertEquals(listOf("gpt-4.1-mini","gpt-5-mini"),models.map { it.id })
            assertEquals("Bearer test-key",api.server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun failedBatchPreservesCheckpointAndResumeSkipsSuccessfulRequests()=runBlocking {
        AuthoringFixture().use { api ->
            val profile=AiProfile("compatible",api.server.url("/v1").toString(),"model")
            val request=AiRequest("s","topic",count=25);val checkpoints=mutableListOf<AiDraft>()
            val generator=AiBatchGenerator(AiGenerator(api.http))
            chat(api,"first");api.json("{}",400)
            try { generator.generate(profile,"test-key",request,checkpoint={ checkpoints+=it });fail("accepted failure") } catch(_: RemoteError) { }
            assertEquals(2,api.server.requestCount);assertEquals(1,checkpoints.size)
            val first=checkpoints.single();assertEquals(10,first.generatedCount);assertFalse(first.generationComplete)
            val stable=first.items.single().itemId
            chat(api,"second");chat(api,"third")
            val final=generator.generate(profile,"test-key",request,first,checkpoint={ checkpoints+=it })
            assertEquals(4,api.server.requestCount);assertEquals(25,final.generatedCount);assertTrue(final.generationComplete)
            assertEquals(stable,final.items.first().itemId);assertEquals(3,final.items.size)
            val bodies=(1..4).map { Json.parseToJsonElement(api.server.takeRequest().body.readUtf8()).jsonObject.toString() }
            assertTrue(bodies.last().contains("產生 5 筆"));assertTrue(bodies.last().contains("first"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { generator.generate(profile.copy(model="changed"),"test-key",request,first,checkpoint={}) } }
            Unit
        }
    }
    @Test fun batchesDeduplicateAgainstEachOtherAndSavedLessons()=runBlocking {
        AuthoringFixture().use { api ->
            val profile=AiProfile("compatible",api.server.url("/v1").toString(),"model")
            chat(api,"same");chat(api,"same")
            val draft=AiBatchGenerator(AiGenerator(api.http)).generate(profile,"test-key",AiRequest("s","topic",count=20),checkpoint={})
            assertEquals(1,draft.items.size);assertEquals(1,draft.removedDuplicates)
            chat(api,"already saved")
            val empty=AiBatchGenerator(AiGenerator(api.http)).generate(profile,"test-key",AiRequest("s","topic",count=1),checkpoint={},existing={ rows -> rows.map(AuthoringValidation::fingerprint).toSet() })
            assertTrue(empty.generationComplete);assertTrue(empty.items.isEmpty())
        }
    }
    @Test fun legacyProfileAndDraftDecodeWithSafeDefaults() {
        val profile=JsonCodec.json.decodeFromString(AiProfile.serializer(),"""{"provider":"openai","endpoint":"https://api.openai.com/v1","model":"gpt-4o-mini"}""")
        assertEquals("auto",profile.protocol);assertEquals("json",profile.outputMode)
        val draft=AuthoringValidation.parse("{\"items\":[${item("legacy")}]}",AiRequest("s","topic",count=1))
        val json=JsonCodec.json.encodeToJsonElement(AiDraft.serializer(),draft).jsonObject.filterKeys { it !in listOf("generationComplete","generatedCount","generationProfile") }
        assertTrue(JsonCodec.json.decodeFromJsonElement(AiDraft.serializer(),JsonObject(json)).generationComplete)
    }
    @Test fun stoppingAfterAValidResponseFinishesCheckpointBeforeCancelling()=runBlocking {
        AuthoringFixture().use { api ->
            chat(api,"saved-before-stop")
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val saved=mutableListOf<AiDraft>()
            val profile=AiProfile("compatible",api.server.url("/v1").toString(),"model")
            val job=launch {
                AiBatchGenerator(AiGenerator(api.http)).generate(profile,"test-key",AiRequest("s","topic",count=20),checkpoint={ saved+=it },existing={ entered.complete(Unit);release.await();emptySet() })
            }
            entered.await();job.cancel();release.complete(Unit);job.join()
            assertEquals(1,saved.size);assertEquals(10,saved.single().generatedCount)
            assertEquals(1,api.server.requestCount)
        }
    }

}
