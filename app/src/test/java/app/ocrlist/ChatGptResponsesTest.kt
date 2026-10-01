package app.ocrlist

import app.ocrlist.chatgpt.ChatGptException
import app.ocrlist.chatgpt.ChatGptResponses
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatGptResponsesTest {
    private val items = """{"items":[{"text":"Oat milk","quantity":"2","original_text":"2x Oat milk","needs_review":false},{"text":"[?] apples","quantity":"","original_text":"[?] apples","needs_review":true}]}"""

    @Test fun sendsImageToNonStoredStreamingRequest() {
        val request = ChatGptResponses.request("account-model", "image-data")
        assertFalse(request.getBoolean("store"))
        assertTrue(request.getBoolean("stream"))
        assertFalse(request.has("max_output_tokens"))
        assertFalse(request.has("tools"))
        assertEquals("account-model", request.getString("model"))
        assertEquals("data:image/jpeg;base64,image-data", request.getJSONArray("input")
            .getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("image_url"))
    }

    @Test fun parsesQuantityUncertaintyAndOrder() {
        val parsed = ChatGptResponses.parseItems(items)
        assertEquals(listOf("Oat milk", "[?] apples"), parsed.map { it.text })
        assertEquals("2", parsed[0].quantity)
        assertTrue(parsed[1].needsReview)
        assertTrue(parsed.none { it.checked })
        assertTrue(parsed.all { it.bounds == null })
        assertTrue(ChatGptResponses.parseItems("""{"items":[]}""").isEmpty())
    }

    @Test fun refusesMalformedItemsInsteadOfSilentlyDroppingThem() {
        for (invalid in listOf("not JSON", items.replace("\"Oat milk\"", "\"\""),
            items.replace("\"quantity\":\"2\"", "\"quantity\":\"NaN\""), """{"items":[{"text":"milk"}]}""")) {
            assertThrows(ChatGptException::class.java) { ChatGptResponses.parseItems(invalid) }
        }
    }

    @Test fun waitsForCompletedResponse() {
        val content = JSONArray().put(JSONObject().put("type", "output_text").put("text", items))
        val completed = JSONObject().put("type", "response.completed").put("response", JSONObject()
            .put("status", "completed").put("output", JSONArray().put(JSONObject().put("content", content))))
        assertEquals(items, ChatGptResponses.readStream(("event: response.completed\ndata: $completed\n\n").reader().buffered()))
    }

    @Test fun partialTextFollowedByQuotaFailureIsNotAccepted() {
        val delta = JSONObject().put("type", "response.output_text.delta").put("delta", items)
        val failed = """{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}"""
        val error = assertThrows(ChatGptException::class.java) {
            ChatGptResponses.readStream("data: $delta\n\ndata: $failed\n\n".reader().buffered())
        }
        assertTrue(error.message!!.contains("usage limit"))
    }

    @Test fun disconnectOrDoneWithoutCompletionFails() {
        val delta = JSONObject().put("type", "response.output_text.delta").put("delta", items)
        for (ending in listOf("", "data: [DONE]\n\n", "data: {\"type\":\"response.incomplete\"}\n\n")) {
            assertThrows(ChatGptException::class.java) {
                ChatGptResponses.readStream("data: $delta\n\n$ending".reader().buffered())
            }
        }
    }
}
