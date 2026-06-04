package nz.co.ardp.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import nz.co.ardp.connection.ConnectionConfig
import nz.co.ardp.connection.ConnectionStore

class ConnectionListViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ConnectionStore(application)

    val connections: Flow<List<ConnectionConfig>> = store.connections

    fun delete(id: String) {
        viewModelScope.launch { store.delete(id) }
    }
}
