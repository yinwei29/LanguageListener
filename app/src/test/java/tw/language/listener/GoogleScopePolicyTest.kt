package tw.language.listener

import org.junit.Assert.assertThrows
import org.junit.Test
import tw.language.listener.authoring.GoogleAuthorization
import tw.language.listener.authoring.requireSheetScopes

class GoogleScopePolicyTest {
    @Test fun readonlyConsentCannotAuthorizeInitializationOrWrites() {
        requireSheetScopes(listOf(GoogleAuthorization.READ_SCOPE),false)
        requireSheetScopes(listOf(GoogleAuthorization.WRITE_SCOPE),false)
        requireSheetScopes(listOf(GoogleAuthorization.WRITE_SCOPE),true)
        assertThrows(IllegalStateException::class.java) { requireSheetScopes(listOf(GoogleAuthorization.READ_SCOPE),true) }
        assertThrows(IllegalStateException::class.java) { requireSheetScopes(emptyList(),false) }
        assertThrows(IllegalStateException::class.java) { requireSheetScopes(listOf("unrelated-scope"),true) }
    }
}
