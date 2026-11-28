import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.greenart7c3.citrine.logs.LogDao
import com.greenart7c3.citrine.logs.LogDatabase
import com.greenart7c3.citrine.logs.format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val MAX_LOG_LINES = 500

class LogcatViewModel(logDao: LogDao) : ViewModel() {
    val logMessages = logDao.getRecent(MAX_LOG_LINES)
        .map { logs -> logs.map { it.format() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { LogcatViewModel(LogDatabase.getDatabase(appContext).logDao()) }
        }
    }
}
