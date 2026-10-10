package com.dhruvtilva.pushalert.collect

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.dhruvtilva.pushalert.PushAlertApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Read-only listener. It looks at a notification's content only if the posting package is one of
 * the configured WhatsApp packages; every other app's notifications are ignored without being read.
 */
class PushAlertListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        connectedState.value = true
    }

    override fun onListenerDisconnected() {
        connectedState.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val graph = (applicationContext as PushAlertApp).graph
        val cfg = graph.settings.settings.value
        if (sbn.packageName !in cfg.whatsappPackages || !cfg.collectionEnabled) return
        val raw = try {
            NotificationAdapter.toRaw(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read a notification (${e.javaClass.simpleName})")
            return
        }
        graph.collectScope.launch {
            try {
                graph.engine.process(raw)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Collection failed (${e.javaClass.simpleName})")
            }
        }
    }

    companion object {
        private const val TAG = "PushAlert"
        private val connectedState = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = connectedState.asStateFlow()

        fun component(context: Context) = ComponentName(context, PushAlertListenerService::class.java)

        /** Whether the user has granted notification access to this app in Android settings. */
        fun isAccessGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }
        }

        fun requestRebindIfNeeded(context: Context) {
            if (isAccessGranted(context) && !connectedState.value) {
                try {
                    requestRebind(component(context))
                } catch (e: Exception) {
                    Log.w(TAG, "Rebind request failed (${e.javaClass.simpleName})")
                }
            }
        }
    }
}

/** Converts a [StatusBarNotification] into the platform-independent [RawNotification]. */
object NotificationAdapter {
    private const val EXTRA_CONVERSATION_TITLE = "android.conversationTitle"
    private const val EXTRA_IS_GROUP_CONVERSATION = "android.isGroupConversation"

    fun toRaw(sbn: StatusBarNotification): RawNotification {
        val n = sbn.notification
        val extras: Bundle = n.extras ?: Bundle()
        val messages = ArrayList<RawMessage>()
        @Suppress("DEPRECATION")
        val bundles = try { extras.getParcelableArray(Notification.EXTRA_MESSAGES) } catch (e: Exception) { null }
        bundles?.forEach { p ->
            val b = p as? Bundle ?: return@forEach
            val sender = b.getCharSequence("sender")?.toString()
                ?: (try { b.getBundle("sender_person")?.getCharSequence("name")?.toString() } catch (e: Exception) { null })
            messages += RawMessage(
                text = b.getCharSequence("text")?.toString(),
                timeMs = if (b.containsKey("time")) b.getLong("time") else null,
                senderName = sender,
            )
        }
        val lines = try {
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        return RawNotification(
            packageName = sbn.packageName,
            postTime = sbn.postTime,
            tag = sbn.tag,
            shortcutId = n.shortcutId,
            isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString(),
            conversationTitle = extras.getCharSequence(EXTRA_CONVERSATION_TITLE)?.toString(),
            isGroupConversation = if (extras.containsKey(EXTRA_IS_GROUP_CONVERSATION)) extras.getBoolean(EXTRA_IS_GROUP_CONVERSATION) else null,
            messages = messages,
            textLines = lines,
        )
    }
}
