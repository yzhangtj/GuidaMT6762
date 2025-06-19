package com.guidaco.guidaapp0606

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) {
    
    companion object {
        private val SPEECH_VOLUME = floatPreferencesKey("speech_volume")
        private val SPEECH_RATE = floatPreferencesKey("speech_rate")
    }
    
    val speechVolume: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[SPEECH_VOLUME] ?: 1.0f
    }
    
    val speechRate: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[SPEECH_RATE] ?: 1.0f
    }
    
    suspend fun setSpeechVolume(volume: Float) {
        context.dataStore.edit { preferences ->
            preferences[SPEECH_VOLUME] = volume
        }
    }
    
    suspend fun setSpeechRate(rate: Float) {
        context.dataStore.edit { preferences ->
            preferences[SPEECH_RATE] = rate
        }
    }
} 