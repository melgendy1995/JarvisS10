package com.jarvis.provider.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundingQuotaTest {
    @Test
    fun readsQuotaIdWindowAndModelFromTheErrorBody() {
        val body = """
            [429] You exceeded your current quota.
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_search_request_free, limit: 0, model: gemini-3.1-flash-lite","details":[{"violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_search_request_free","quotaId":"GenerateContentSearchRequestPerDayPerProjectPerModel-FreeTier","quotaValue":"0"}]}]}}
        """.trimIndent()
        val report = QuotaSignals.report(body)
        assertEquals(429, report.httpStatus)
        assertEquals("RESOURCE_EXHAUSTED", report.status)
        assertEquals(
            "GenerateContentSearchRequestPerDayPerProjectPerModel-FreeTier",
            report.quotaId,
        )
        assertEquals(
            "generativelanguage.googleapis.com/generate_content_search_request_free",
            report.metric,
        )
        assertEquals("day", report.window)
        assertEquals("gemini-3.1-flash-lite", report.model)
        assertEquals("0", report.limit)

        val line = QuotaSignals.failureLine("gemini-2.5-flash-lite", RuntimeException(body))
        assertTrue(line.contains("http=429"))
        assertTrue(line.contains("window=day"))
        assertTrue(line.contains("quotaModel=gemini-3.1-flash-lite"))
        assertTrue(line.contains("model=gemini-2.5-flash-lite"))
        assertTrue(QuotaSignals.failureBody(RuntimeException(body)).contains("quotaId"))
    }

    @Test
    fun minuteQuotaIsNotLabeledAsADay() {
        val body = """
            [429] {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_requests, limit: 15, model: gemini-2.5-flash-lite. Please retry in 30 seconds. per minute"}}
        """.trimIndent()
        val report = QuotaSignals.report(body)
        assertEquals("minute", report.window)
        assertEquals("gemini-2.5-flash-lite", report.model)
        assertEquals("15", report.limit)
        assertEquals("gemini-3.5-flash-lite", ModelConfig.GROUNDING_MODEL)
        assertEquals("gemini-2.5-flash", ModelConfig.GROUNDING_MODEL_FALLBACK)
        assertEquals(30, ModelConfig.DAILY_GROUNDED_CAP)
    }
}
