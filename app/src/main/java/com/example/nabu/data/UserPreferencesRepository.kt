package com.example.nabu.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class UserPreferencesRepository(private val context: Context) {

    private val hfTokenKey = stringPreferencesKey("hf_token")

    val hfToken: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[hfTokenKey]
        }

    suspend fun saveHfToken(token: String) {
        context.dataStore.edit { settings ->
            settings[hfTokenKey] = token
        }
    }
}
