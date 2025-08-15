package com.example.purramid.purramidtime.timer

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicUrlManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("music_urls_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val KEY_RECENT_URLS = "recent_music_urls"
        private const val MAX_RECENT_URLS = 3
    }
    
    fun getRecentUrls(): List<String> {
        val json = prefs.getString(KEY_RECENT_URLS, "[]")
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun addRecentUrl(url: String) {
        val urls = getRecentUrls().toMutableList()
        urls.remove(url) // Remove if exists
        urls.add(0, url) // Add to beginning
        if (urls.size > MAX_RECENT_URLS) {
            urls.removeAt(MAX_RECENT_URLS)
        }
        
        val json = gson.toJson(urls)
        prefs.edit().putString(KEY_RECENT_URLS, json).apply()
    }
    
    fun clearRecentUrls() {
        prefs.edit().remove(KEY_RECENT_URLS).apply()
    }
}