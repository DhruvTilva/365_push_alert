package com.dhruvtilva.pushalert

import android.app.Application
import android.content.Context
import com.dhruvtilva.pushalert.collect.CollectionEngine
import com.dhruvtilva.pushalert.data.AppDatabaseHelper
import com.dhruvtilva.pushalert.data.AndroidDb
import com.dhruvtilva.pushalert.data.KeystoreCipher
import com.dhruvtilva.pushalert.data.PrefsKeyValueStore
import com.dhruvtilva.pushalert.data.SecretStore
import com.dhruvtilva.pushalert.data.SettingsStore
import com.dhruvtilva.pushalert.data.Store
import com.dhruvtilva.pushalert.discord.BatchPublisher
import com.dhruvtilva.pushalert.discord.DiscordClient
import com.dhruvtilva.pushalert.discord.UrlConnectionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-wired object graph; small enough that a DI framework would only add weight. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppGraph(context: Context) {
    val settings = SettingsStore(PrefsKeyValueStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE)))
    val secrets by lazy { SecretStore(PrefsKeyValueStore(context.getSharedPreferences("secrets", Context.MODE_PRIVATE)), KeystoreCipher()) }
    val store by lazy { Store(AndroidDb(AppDatabaseHelper(context).writableDatabase)) }
    val client = DiscordClient(UrlConnectionTransport())
    val engine by lazy { CollectionEngine(store, { settings.settings.value }) }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val collectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    val publisher by lazy { BatchPublisher(store, client, { secrets.current() }, { settings.settings.value }, appScope) }

    /** UI state that should survive activity re-creation (rotation, theme change). */
    @Volatile var inboxSearch: String = ""
    @Volatile var inboxGroupFilter: String? = null
    @Volatile var lastWebhookTestResult: String? = null

    init {
        appScope.launch {
            secrets // warm up keystore access off the main thread
            store.recoverInterrupted() // sends interrupted by a process kill may or may not have arrived
            store.pruneSeen()
        }
    }
}

class PushAlertApp : Application() {
    /** Created on first use so that a failure is reported by [CrashReporter] instead of killing the process in onCreate. */
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
