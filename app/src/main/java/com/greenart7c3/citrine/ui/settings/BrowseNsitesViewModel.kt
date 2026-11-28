package com.greenart7c3.citrine.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.greenart7c3.citrine.service.NsiteManager

class BrowseNsitesViewModel : ViewModel() {
    var searchQuery by mutableStateOf(TextFieldValue(""))

    /** The discovered [nsites] whose name, description, author or address contains the search query. */
    fun matching(nsites: List<NsiteManager.DiscoveredNsite>): List<NsiteManager.DiscoveredNsite> {
        val query = searchQuery.text.trim()
        return if (query.isEmpty()) {
            nsites
        } else {
            nsites.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true) ||
                    it.authorName.contains(query, ignoreCase = true) ||
                    it.address.contains(query, ignoreCase = true)
            }
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { BrowseNsitesViewModel() }
        }
    }
}
