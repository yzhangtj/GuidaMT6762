package com.guidaco.guidaglassesapp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) {
    
    companion object {
        private val SPEECH_VOLUME = floatPreferencesKey("speech_volume")
        private val SPEECH_RATE = floatPreferencesKey("speech_rate")
        private val PHONE_API_URL = stringPreferencesKey("phone_api_url")
        private val USE_PHONE_GEMMA = booleanPreferencesKey("use_phone_gemma")
    }
    
    val speechVolume: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[SPEECH_VOLUME] ?: 1.0f
    }
    
    val speechRate: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[SPEECH_RATE] ?: 1.0f
    }

    val phoneApiUrl: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PHONE_API_URL]
    }

    val usePhoneGemma: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[USE_PHONE_GEMMA] ?: false
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

    suspend fun setPhoneApiUrl(url: String?) {
        context.dataStore.edit { preferences ->
            if (url == null) {
                preferences.remove(PHONE_API_URL)
            } else {
                preferences[PHONE_API_URL] = url
            }
        }
    }

    suspend fun setUsePhoneGemma(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[USE_PHONE_GEMMA] = enabled
        }
    }
} 