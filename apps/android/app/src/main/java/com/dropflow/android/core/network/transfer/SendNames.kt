package com.dropflow.android.core.network.transfer

internal object SendNames {
    /**
     * Two picked files can share a display name (the same photo name in two
     * albums). Receivers reject duplicate paths, so later ones become
     * "name (2).ext". Compared without case, as receivers do.
     */
    fun unique(names: List<String>): List<String> {
        val seen = HashSet<String>()
        return names.map { name ->
            if (seen.add(name.lowercase())) return@map name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            generateSequence(2) { it + 1 }
                .map { "${name.substring(0, dot)} ($it)${name.substring(dot)}" }
                .first { seen.add(it.lowercase()) }
        }
    }
}
