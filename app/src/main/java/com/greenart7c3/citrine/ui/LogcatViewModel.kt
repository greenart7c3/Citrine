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

private const val MAX_LOG_LINES = 500

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
                // Include both Citrine and CitrineContentProvider tags to show ContentProvider errors
                // Using multiple -s flags to filter by both tags
                val process = Runtime.getRuntime().exec("logcat --pid=$processId -s ${Citrine.TAG}:* -s CitrineContentProvider:*")
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                val buffer = ArrayDeque<String>()
                reader.useLines { lines ->
                    lines.forEach { logMessage ->
                        buffer.addFirst(logMessage)
                        while (buffer.size > MAX_LOG_LINES) buffer.removeLast()
                        _logMessages.value = buffer.toList()
                    }
                }
            } catch (e: Exception) {
                Log.e("LogcatViewModel", "Error reading Logcat", e)
            }
        }
    }
}
