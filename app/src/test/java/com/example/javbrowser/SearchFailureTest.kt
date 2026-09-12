package com.example.javbrowser
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class SearchFailureTest {
    @Test fun distinguishesTransportFailuresWithoutLeakingMessages() {
        assertEquals("DNS_FAILURE", SearchFailure.code(UnknownHostException("private query")))
        assertEquals("TLS_FAILURE", SearchFailure.code(SSLHandshakeException("private query")))
        assertEquals("CONNECT_FAILURE", SearchFailure.code(ConnectException("private query")))
        assertEquals("HTTP_TIMEOUT", SearchFailure.code(SocketTimeoutException("private query")))
        assertEquals("UNEXPECTED_REDIRECT_HOST", SearchFailure.code(SearchTransportException("UNEXPECTED_REDIRECT_HOST")))
    }
    @Test fun parsesRetryAfterSecondsAndDate() {
        assertEquals(61_000L, SearchHttpFetcher.retryAfter("60", 1000L))
        assertEquals(0L, SearchHttpFetcher.retryAfter(null, 1000L))
        assertTrue(SearchHttpFetcher.retryAfter("Wed, 09 Sep 2026 00:00:00 GMT", 1000L) > 1000L)
    }
}
