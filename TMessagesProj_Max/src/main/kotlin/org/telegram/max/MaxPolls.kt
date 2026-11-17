package org.telegram.max

import org.json.JSONObject

/**
 * Опрос MAX, сверено с Komet (models/poll.dart): вложение `POLL` с `pollId`, `title`,
 * `settings` (1 — несколько ответов, 4 — анонимный), `answers [{answerId, text}]`;
 * результаты — `state {total, result [{answerId, voteCount, rate, options}]}`, у своего
 * ответа в `options` выставлен бит 1.
 */
class MaxPoll(
    val pollId: Long,
    val title: String,
    val settings: Int,
    val total: Int,
    val answers: List<Answer>,
) {
    class Answer(val id: Int, val text: String, val votes: Int, val rate: Double, val mine: Boolean)

    val isMultiple: Boolean
        get() = settings and 1 != 0

    val isAnonymous: Boolean
        get() = settings and 4 != 0

    val voted: Boolean
        get() = answers.any { it.mine }

    /** Тот же опрос с новыми результатами (`state`) — ответ на голос или опрос 306. */
    fun withState(state: JSONObject?): MaxPoll = parse(JSONObject().put("pollId", pollId).put("title", title).put("settings", settings)
        .put("answers", org.json.JSONArray(answers.map { JSONObject().put("answerId", it.id).put("text", it.text) }))
        .put("state", state ?: JSONObject())) ?: this

    companion object {
        fun parse(json: JSONObject?): MaxPoll? {
            if (json == null) return null
            val state = json.optJSONObject("state") ?: JSONObject()
            val results = HashMap<Int, JSONObject>()
            state.optJSONArray("result")?.let { r ->
                for (i in 0 until r.length()) r.optJSONObject(i)?.let { results[it.optInt("answerId", -1)] = it }
            }
            val raw = json.optJSONArray("answers")
            val answers = ArrayList<Answer>()
            if (raw != null) for (i in 0 until raw.length()) {
                val a = raw.optJSONObject(i) ?: continue
                val id = a.optInt("answerId", i)
                val res = results[id]
                answers.add(Answer(id, a.optString("text", ""), res?.optInt("voteCount", 0) ?: 0, res?.optDouble("rate", 0.0) ?: 0.0, ((res?.optInt("options", 0) ?: 0) and 1) != 0))
            }
            return MaxPoll(json.optLong("pollId", 0L), json.optString("title", ""), json.optInt("settings", 0), state.optInt("total", 0), answers)
        }
    }
}
