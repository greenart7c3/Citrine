import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greenart7c3.citrine.Citrine
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogcatViewModel : ViewModel() {
    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages = _logMessages.asStateFlow()

    init {
        startCollectingLogs()
    }

    private fun startCollectingLogs() {
        viewModelScope.launch {
            collectLogs()
        }
    }

    private suspend fun collectLogs() {
        withContext(Dispatchers.IO) {
            try {
                // Get the process ID for the current app
                val processId = android.os.Process.myPid()
                // Start listening to logcat for the current app's logs continuously
                val process = Runtime.getRuntime().exec("logcat --pid=$processId -s ${Citrine.TAG}")
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                var log = mutableListOf<String>()
                reader.useLines { lines ->
                    lines.forEach { logMessage ->
                        // Update logs with the new log message added at the top (inverted order)
                        log = (mutableListOf(logMessage) + log).toMutableList()
                        _logMessages.value = log
                    }
                }
            } catch (e: Exception) {
                Log.e("LogcatViewModel", "Error reading Logcat", e)
            }
        }
    }
}
