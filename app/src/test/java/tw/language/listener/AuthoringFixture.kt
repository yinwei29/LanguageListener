package tw.language.listener

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import tw.language.listener.authoring.*
import java.io.Closeable

class AuthoringFixture : Closeable {
    val server=MockWebServer()
    val client: OkHttpClient
    val http: JsonHttp
    val sheets: GoogleSheets
    init {
        val certificate=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build()
        server.useHttps(tls.sslSocketFactory(),false); server.start()
        http=JsonHttp(client); sheets=GoogleSheets(http,server.url("/v4/spreadsheets/"))
    }
    fun json(body: String, code: Int=200) { server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(body)) }
    override fun close() { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}

class MemoryVault : SecretVault {
    private val values=mutableMapOf<String,String>()
    override fun get(name: String)=values[name]
    override fun put(name: String, value: String) { values[name]=value }
    override fun remove(name: String) { values.remove(name) }
}
