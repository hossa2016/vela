package com.hossa.vela

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatMsg(val fromUser: Boolean, val text: String)

data class AiReply(val text: String, val alert: Alert?, val source: String)

/** Asistente: Gemini primero y Groq de respaldo. Responde en JSON. */
object Ai {
    private fun post(url: String, headers: Map<String, String>, body: JSONObject): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 45000
        c.doOutput = true
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        try {
            val code = c.responseCode
            if (code != 200) throw Exception(when (code) {
                400, 401, 403 -> "clave no válida"
                429 -> "límite gratis agotado por ahora"
                503 -> "servidor saturado"
                else -> "error $code"
            })
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun gemini(key: String, system: String, msgs: List<ChatMsg>): String {
        val contents = JSONArray()
        msgs.forEach {
            contents.put(JSONObject().put("role", if (it.fromUser) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", it.text))))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.6).put("responseMimeType", "application/json"))
        val r = post("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent",
            mapOf("x-goog-api-key" to key), body)
        return JSONObject(r).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text")
    }

    private fun groq(key: String, system: String, msgs: List<ChatMsg>): String {
        val m = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        msgs.forEach { m.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) }
        val body = JSONObject().put("model", "openai/gpt-oss-120b").put("temperature", 0.6)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("reasoning_effort", "low").put("messages", m)
        val r = post("https://api.groq.com/openai/v1/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
        return JSONObject(r).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    /** Bloquea: llamar fuera del hilo principal. */
    fun ask(ctx: Context, history: List<ChatMsg>, marketContext: String): AiReply {
        val system = """
Eres el asistente de Vela, una app personal de cripto con dinero virtual (simulación).
Explica en español sencillo, breve (máximo 120 palabras), sin jerga innecesaria.
Nunca des recomendaciones de inversión ni prometas ganancias: explica, compara y enseña.
Si el usuario pide una alerta de precio, créala.
Datos actuales del mercado (USDT):
$marketContext
Responde SOLO JSON: {"reply":"texto","alert":null} o con alerta
{"reply":"texto","alert":{"symbol":"BTC","above":false,"price":92000}}
Símbolos válidos: ${Market.COINS.joinToString(", ") { it.symbol }}.
""".trimIndent()
        val errors = ArrayList<String>()
        val tries = listOf(
            Triple("Gemini", Store.geminiKey(ctx)) { k: String -> gemini(k, system, history) },
            Triple("Groq", Store.groqKey(ctx)) { k: String -> groq(k, system, history) }
        )
        for ((name, key, call) in tries) {
            if (key.isBlank()) continue
            try {
                val raw = call(key)
                val a = raw.indexOf('{'); val z = raw.lastIndexOf('}')
                if (a < 0 || z <= a) { errors += "$name: respuesta no válida"; continue }
                val j = JSONObject(raw.substring(a, z + 1))
                val alert = j.optJSONObject("alert")?.let {
                    val s = it.optString("symbol").uppercase()
                    val p = it.optDouble("price", Double.NaN)
                    if (Market.COINS.any { c -> c.symbol == s } && !p.isNaN())
                        Alert(System.currentTimeMillis().toString(), s, it.optBoolean("above"), p) else null
                }
                return AiReply(j.optString("reply").ifBlank { "Listo." }, alert, name)
            } catch (e: Exception) {
                errors += "$name: ${e.message ?: "error"}"
            }
        }
        if (Store.geminiKey(ctx).isBlank() && Store.groqKey(ctx).isBlank()) {
            return AiReply("Para usar el asistente, pega tu clave gratis de Gemini o de Groq en Ajustes (el ícono de engranaje). Son las mismas que usas en RockPlay IA.", null, "Vela")
        }
        return AiReply("No pude responder ahora (${errors.joinToString("; ")}). Intenta en un momento.", null, "Vela")
    }
}
