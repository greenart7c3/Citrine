package com.greenart7c3.citrine.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

/** A set a settings screen edits, with the field a new entry is typed in; [parse] turns the typed text into an entry, or null when it is invalid. */
class EditableSet<T>(initial: Set<T>, private val parse: (String) -> T?) {
    var values by mutableStateOf(initial)
        private set
    var input by mutableStateOf(TextFieldValue(""))

    /** Adds the typed entry and clears the field; false when it is invalid. */
    fun add(): Boolean {
        val entry = parse(input.text) ?: return false
        values = values + entry
        input = TextFieldValue("")
        return true
    }

    /** Puts the pasted [text] in the field and adds it; false when it is invalid. */
    fun paste(text: String): Boolean {
        input = TextFieldValue(text)
        return add()
    }

    fun addAll(entries: Set<T>) {
        values = values + entries
    }

    fun remove(entry: T) {
        values = values - entry
    }

    fun reset(defaults: Set<T>) {
        values = defaults
    }
}
