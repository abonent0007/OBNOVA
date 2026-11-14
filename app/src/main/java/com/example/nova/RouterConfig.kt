package com.example.nova

/**
 * Сборка JSON-конфигурации для `nova-router`.
 *
 * Пишем вручную, без org.json: модуль должен оставаться чистым Kotlin и
 * проверяться на JVM, а порядок ключей — быть предсказуемым (его видно в логах
 * и диффе конфигурации).
 */
object RouterConfig {

    data class SlotSpec(
        val type: String,
        val addr: String = "",
        val url: String = "",
        val secret: String = "",
        val via: String = "",
        val relayVia: String = "",
        /** Явная UDP-capability транспорта; не выводится из имени слота. */
        val udp: Boolean = false,
    )

    data class RuleSpec(
        val domains: List<String>,
        /** TCP-порядок; имя `order` сохранено для совместимости вызовов. */
        val order: List<String>,
        val name: String = "",
        val cidrs: List<String> = emptyList(),
        /** UDP-порядок задаётся явно; пустой = DROP для этого правила. */
        val udpOrder: List<String> = emptyList(),
        /**
         * Разрешает UDP/443 для этого правила. Это именно порт, а не
         * определение QUIC: транспорт и SupportsUDP проверяются отдельно.
         */
        val allowQuic: Boolean = false,
    )

    fun build(
        slots: Map<String, SlotSpec>,
        rules: List<RuleSpec>,
        defaultOrder: List<String>,
        defaultUdpOrder: List<String> = emptyList(),
        strict: Boolean = false,
    ): String {
        val json = StringBuilder()
        json.append('{')
        json.append("\"slots\":{")
        slots.toSortedMap().entries.forEachIndexed { index, (name, slot) ->
            if (index > 0) json.append(',')
            json.append('"').append(escape(name)).append("\":{")
            json.append("\"type\":\"").append(escape(slot.type)).append('"')
            if (slot.addr.isNotEmpty()) {
                json.append(",\"addr\":\"").append(escape(slot.addr)).append('"')
            }
            if (slot.url.isNotEmpty()) {
                json.append(",\"url\":\"").append(escape(slot.url)).append('"')
            }
            if (slot.secret.isNotEmpty()) {
                json.append(",\"secret\":\"").append(escape(slot.secret)).append('"')
            }
            if (slot.via.isNotEmpty()) {
                json.append(",\"via\":\"").append(escape(slot.via)).append('"')
            }
            if (slot.relayVia.isNotEmpty()) {
                json.append(",\"relayVia\":\"").append(escape(slot.relayVia)).append('"')
            }
            if (slot.udp) {
                json.append(",\"udp\":true")
            }
            json.append('}')
        }
        json.append("},")

        json.append("\"rules\":[")
        rules.forEachIndexed { index, rule ->
            if (index > 0) json.append(',')
            json.append('{')
            if (rule.name.isNotBlank()) {
                json.append("\"name\":\"").append(escape(rule.name)).append("\",")
            }
            json.append("\"domains\":[")
            rule.domains.forEachIndexed { dIndex, domain ->
                if (dIndex > 0) json.append(',')
                json.append('"').append(escape(domain)).append('"')
            }
            json.append(']')
            if (rule.cidrs.isNotEmpty()) {
                json.append(",\"cidrs\":[")
                rule.cidrs.forEachIndexed { cIndex, cidr ->
                    if (cIndex > 0) json.append(',')
                    json.append('"').append(escape(cidr)).append('"')
                }
                json.append(']')
            }
            json.append(",\"tcpOrder\":[")
            rule.order.forEachIndexed { oIndex, slot ->
                if (oIndex > 0) json.append(',')
                json.append('"').append(escape(slot)).append('"')
            }
            json.append("],\"udpOrder\":[")
            rule.udpOrder.forEachIndexed { uIndex, slot ->
                if (uIndex > 0) json.append(',')
                json.append('"').append(escape(slot)).append('"')
            }
            json.append("]")
            if (rule.allowQuic) {
                json.append(",\"allowQuic\":true")
            }
            json.append("}")
        }
        json.append("],")

        json.append("\"defaultOrder\":[")
        defaultOrder.forEachIndexed { index, slot ->
            if (index > 0) json.append(',')
            json.append('"').append(escape(slot)).append('"')
        }
        json.append("],\"defaultUdpOrder\":[")
        defaultUdpOrder.forEachIndexed { index, slot ->
            if (index > 0) json.append(',')
            json.append('"').append(escape(slot)).append('"')
        }
        json.append(']')
        if (strict) {
            // Флаг читает Go-валидатор: он обязан отклонить такую конфигурацию,
            // если в ней где-либо остался direct, независимо от того, кто её собрал.
            json.append(",\"strict\":true")
        }
        json.append('}')

        return json.toString()
    }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        return out.toString()
    }
}
