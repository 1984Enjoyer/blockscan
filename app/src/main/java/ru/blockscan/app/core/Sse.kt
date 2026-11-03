package ru.blockscan.app.core

data class SseEvent(val event: String, val data: String)

/**
 * Построчный разбор text/event-stream (WHATWG). Принимает строки без символов конца строки.
 * Комментарии (строки с ':') игнорируются. Пробел после двоеточия необязателен:
 * реальный сервис отдаёт "event:result" без пробела.
 */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Возвращает событие, если пустая строка завершила его, иначе null. */
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val idx = line.indexOf(':')
        val field = if (idx < 0) line else line.substring(0, idx)
        var value = if (idx < 0) "" else line.substring(idx + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    /** Завершить поток: отдать событие, не закрытое пустой строкой. */
    fun flush(): SseEvent? {
        if (!hasData && event == null) return null
        val e = SseEvent(event ?: "message", data.toString())
        event = null
        data.setLength(0)
        hasData = false
        return e
    }
}
