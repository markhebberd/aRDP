package nz.co.ardp.connection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "connections")
private val CONNECTIONS_KEY = stringPreferencesKey("connections_json")

class ConnectionStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    val connections: Flow<List<ConnectionConfig>> = context.dataStore.data.map { prefs ->
        val raw = prefs[CONNECTIONS_KEY] ?: "[]"
        json.decodeFromString<List<ConnectionConfig>>(raw)
    }

    suspend fun save(config: ConnectionConfig) {
        context.dataStore.edit { prefs ->
            val current = prefs[CONNECTIONS_KEY]?.let {
                json.decodeFromString<MutableList<ConnectionConfig>>(it)
            } ?: mutableListOf()
            val index = current.indexOfFirst { it.id == config.id }
            if (index >= 0) current[index] = config else current.add(config)
            prefs[CONNECTIONS_KEY] = json.encodeToString(current)
        }
    }

    suspend fun delete(id: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[CONNECTIONS_KEY]?.let {
                json.decodeFromString<MutableList<ConnectionConfig>>(it)
            } ?: return@edit
            current.removeAll { it.id == id }
            prefs[CONNECTIONS_KEY] = json.encodeToString(current)
        }
    }
}
