package com.thirtytwo.steps

import org.json.JSONArray
import org.json.JSONObject

data class EqPreset(
    val name: String,
    val mode: Int, // EQ_MODE_GRAPHIC or EQ_MODE_PARAMETRIC
    val graphicGains: List<Float>? = null, // 10 bands
    val parametricBands: List<ParametricBand>? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("name", name)
        obj.put("mode", mode)
        obj.put("version", 1)
        if (graphicGains != null) {
            val arr = JSONArray()
            for (g in graphicGains) arr.put(g.toDouble())
            obj.put("graphic", arr)
        }
        if (parametricBands != null) {
            val arr = JSONArray()
            for (b in parametricBands) {
                val bo = JSONObject()
                bo.put("on", b.enabled)
                bo.put("type", b.type)
                bo.put("freq", b.frequency.toDouble())
                bo.put("gain", b.gain.toDouble())
                bo.put("q", b.q.toDouble())
                arr.put(bo)
            }
            obj.put("parametric", arr)
        }
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): EqPreset {
            val name = obj.getString("name")
            val mode = obj.getInt("mode")
            val graphic = if (obj.has("graphic")) {
                val arr = obj.getJSONArray("graphic")
                (0 until arr.length()).map { arr.getDouble(it).toFloat() }
            } else null
            val parametric = if (obj.has("parametric")) {
                val arr = obj.getJSONArray("parametric")
                (0 until arr.length()).map { i ->
                    val bo = arr.getJSONObject(i)
                    ParametricBand(
                        enabled = bo.optBoolean("on", true),
                        type = bo.optInt("type", BiquadMath.TYPE_PEAKING),
                        frequency = bo.optDouble("freq", 1000.0).toFloat(),
                        gain = bo.optDouble("gain", 0.0).toFloat(),
                        q = bo.optDouble("q", 1.0).toFloat()
                    )
                }
            } else null
            return EqPreset(name, mode, graphic, parametric)
        }
    }
}
