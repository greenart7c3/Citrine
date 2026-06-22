import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.LogDatabase
import com.greenart7c3.citrine.logs.format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val MAX_LOG_LINES = 500

class LogcatViewModel : ViewModel() {
    private val logDao = LogDatabase.getDatabase(Citrine.instance).logDao()

    val logMessages = logDao.getRecent(MAX_LOG_LINES)
        .map { logs -> logs.map { it.format() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
}
