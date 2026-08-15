package com.beautifulquran.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beautifulquran.data.QuranRepository
import com.beautifulquran.data.SettingsRepository
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.tarjilab.ReciterTarjiProfiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    repository: QuranRepository,
    val settings: SettingsRepository,
    private val tarjiProfiles: ReciterTarjiProfiles? = null,
) : ViewModel() {

    private val _reciters = MutableStateFlow<List<Reciter>>(emptyList())
    val reciters: StateFlow<List<Reciter>> = _reciters

    init {
        viewModelScope.launch { _reciters.value = repository.reciters() }
    }

    fun selectReciter(reciter: Reciter) {
        settings.update { it.copy(reciterId = reciter.id) }
        tarjiProfiles?.applyToEngine(reciter.id)
    }
}
