package com.mindplayer99.stremiobridge

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StremioBridgePlugin : Plugin() {
    private val providers = mutableListOf<BridgeProvider>()
    private lateinit var refs: References

    override fun load(context: Context) {
        val store = ProfileStore(context.applicationContext)
        refs = References(context.applicationContext)
        openSettings = { ctx ->
            var c: Context? = ctx
            while (c is ContextWrapper && c !is Activity) c = c.baseContext
            val activity = c as? Activity
            if (activity != null)
                activity.runOnUiThread {
                    Settings(activity, store) { old, new ->
                            register(store.replace(old, new))
                            if (old != null && new == null) refs.drop(old.key)
                            afterPluginsLoadedEvent.invoke(true)
                        }
                        .manager()
                }
            else Toast.makeText(ctx, "Open settings from CloudStream.", Toast.LENGTH_LONG).show()
        }
        register(store.all())
    }

    @Synchronized
    private fun register(profiles: List<Profile>) {
        val old = providers.toSet()
        old.forEach { APIHolder.removePluginMapping(it) }
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it in old } }
        providers.clear()
        profiles.forEach { profile ->
            BridgeProvider(profile, refs).also {
                registerMainAPI(it)
                providers.add(it)
            }
        }
    }
}
