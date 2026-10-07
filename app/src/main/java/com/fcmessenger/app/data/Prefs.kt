package com.fcmessenger.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ds: DataStore<Preferences> by preferencesDataStore("fcm_prefs")

data class Profile(val uid: String, val name: String, val username: String)

class Prefs(private val ctx: Context) {
    private val UID = stringPreferencesKey("uid")
    private val NAME = stringPreferencesKey("name")
    private val USER = stringPreferencesKey("username")
    private val REG = booleanPreferencesKey("registered")
    private val ZERO = booleanPreferencesKey("zero")

    /** null — не зарегистрирован, показать экран регистрации. */
    val profile: Flow<Profile?> = ctx.ds.data.map { p ->
        if (p[REG] != true) null
        else Profile(p[UID] ?: return@map null, p[NAME] ?: "?", p[USER] ?: "?")
    }

    suspend fun cachedUid(): String? = ctx.ds.data.first()[UID]

    suspend fun setUid(uid: String) {
        ctx.ds.edit { it[UID] = uid }
    }

    suspend fun setProfile(uid: String, name: String, username: String) {
        ctx.ds.edit {
            it[UID] = uid
            it[NAME] = name
            it[USER] = username
            it[REG] = true
        }
    }

    /** 0-режим: отправка только по локальному кешу, лишних запросов в сеть нет. */
    val zeroMode: Flow<Boolean> = ctx.ds.data.map { it[ZERO] == true }

    suspend fun setZeroMode(b: Boolean) {
        ctx.ds.edit { it[ZERO] = b }
    }
}
