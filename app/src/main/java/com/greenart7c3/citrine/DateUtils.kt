package com.greenart7c3.citrine

import java.text.DateFormat
import java.util.Locale

fun Long.toDateString(dateFormat: Int = DateFormat.MEDIUM): String {
    val df = DateFormat.getDateInstance(dateFormat, Locale.getDefault())
    return df.format(this * 1000)
}
