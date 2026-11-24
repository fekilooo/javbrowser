package com.example.javbrowser

/** Offline search-key conversion only. Display text and other providers are unchanged. */
object ChineseSearchVariants {
    private class Node {
        val children = HashMap<Char, Node>()
        var value: String? = null
    }
    private val dictionary: Node by lazy {
        val root = Node()
        listOf("TSCharacters.txt", "TSPhrases.txt").forEach { name ->
            val stream = ChineseSearchVariants::class.java.getResourceAsStream("/opencc/$name")
                ?: throw java.io.IOException("缺少繁簡轉換字典 / Missing Chinese conversion dictionary")
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                    val fields = line.split('\t', limit = 2)
                    require(fields.size == 2) { "Invalid Chinese conversion dictionary" }
                    val replacement = fields[1].trim().substringBefore(' ')
                    var node = root
                    fields[0].forEach { character -> node = node.children.getOrPut(character) { Node() } }
                    node.value = replacement
                }
            }
        }
        root
    }
    fun variants(input: String): List<String> {
        val raw = input.trim()
        if (raw.none { it.code in 0x3400..0x9fff || Character.isHighSurrogate(it) }) return listOf(raw)
        val result = StringBuilder()
        var start = 0
        while (start < raw.length) {
            var node = dictionary
            var position = start
            var matchedEnd = start
            var replacement: String? = null
            while (position < raw.length) {
                node = node.children[raw[position]] ?: break
                position++
                node.value?.let { replacement = it; matchedEnd = position }
            }
            if (replacement == null) { result.append(raw[start]); start++ }
            else { result.append(replacement); start = matchedEnd }
        }
        return listOf(raw, result.toString()).distinct()
    }
}
