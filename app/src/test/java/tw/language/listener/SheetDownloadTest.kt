package tw.language.listener

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*
import tw.language.listener.sync.downloadSheet
import java.io.File
import java.util.concurrent.TimeUnit

class SheetDownloadTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var target: File
    @Before fun setup() {
        val certificate=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build()
        server=MockWebServer().apply { useHttps(serverTls.sslSocketFactory(),false); start() }
        target=File.createTempFile("download", ".csv")
    }
    @After fun close() { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); target.delete() }
    @Test fun downloadsCsvOverRealTls() = runBlocking {
        val csv="ID,zh-TW,en-US,id-ID\n1,你好,Hello,Halo\n"
        server.enqueue(MockResponse().setHeader("Content-Type","text/csv").setBody(csv))
        withTimeout(10000) { downloadSheet(client,server.url("/sheet").toString(),target) }
        assertEquals(csv,target.readText())
    }
    @Test fun rejectsHtmlErrorsAndInsecureRedirect() = runBlocking {
        for(response in listOf(MockResponse().setHeader("Content-Type","text/html").setBody("login"),
            MockResponse().setResponseCode(403),MockResponse().setResponseCode(302).setHeader("Location","http://localhost:1/private"))) {
            server.enqueue(response)
            try { downloadSheet(client,server.url("/sheet").toString(),target); fail("response accepted") } catch(_: IllegalStateException) { }
        }
        assertEquals(3,server.requestCount)
    }
    @Test fun cancellingBlockedRequestClosesCallPromptly() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job=launch { downloadSheet(client,server.url("/slow").toString(),target) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(10,TimeUnit.SECONDS) })
        withTimeout(10000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }
    @Test fun changedNetworkPolicyStopsBeforeDownloading() = runBlocking {
        try { downloadSheet(client,server.url("/sheet").toString(),target) { false }; fail("policy ignored") }
        catch(_: IllegalStateException) { }
        assertEquals(0,server.requestCount)
    }
    @Test fun networkPolicyChangeDuringBodyReadRejectsPartialDownload() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(100000)))
        var checks=0
        try {
            downloadSheet(client,server.url("/sheet").toString(),target) { ++checks<=2 }
            fail("policy change ignored")
        } catch(_: IllegalStateException) { }
        assertTrue(target.length()<100000)
        assertEquals(1,server.requestCount)
    }
}
