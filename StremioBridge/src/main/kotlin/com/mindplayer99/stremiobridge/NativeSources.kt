package com.mindplayer99.stremiobridge

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeyClass
import com.lagradost.cloudstream3.ui.player.source_priority.ProfileSettings
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream3.utils.Qualities

/** Real source priorities, never fake resolution values. Only bridge-owned names are written. */
internal object NativeSources {
    fun name(profile: Profile, addon: Addon, debrid: Boolean): String {
        val title = addon.manifest?.name ?: "Addon"
        val duplicate = profile.addons.count { it.manifest?.name == title } > 1
        return listOf(
                "StremioBridge",
                profile.name,
                title + if (duplicate) " · " + addon.key.take(6) else "",
                if (debrid) "Debrid" else "HTTP",
            )
            .joinToString(" · ")
            .replace('/', '∕')
    }

    /** One priority step exceeds the full native quality range, keeping each group contiguous. */
    fun priority(index: Int, count: Int, qualities: List<Int>): Int {
        val range = (qualities.maxOrNull() ?: 0).toLong() - (qualities.minOrNull() ?: 0)
        return (1L + (count - index) * (range + 1)).coerceAtMost(Int.MAX_VALUE.toLong() / 2).toInt()
    }

    fun prepare(profile: Profile, available: Set<String>): Boolean =
        runCatching {
                if (!profile.groupPlayback) return false
                val addons =
                    profile.addons.filter {
                        it.uses("stream") || it.uses("meta") && "stream" in it.switches
                    }
                val groups =
                    listOf(true, false).flatMap { debrid ->
                        addons.map { name(profile, it, debrid) }
                    }
                QualityDataHelper.getProfiles().forEach { native ->
                    val setting = ProfileSettings.HideNegativeSources
                    val hide =
                        getKeyClass(
                            "$currentAccount/${QualityDataHelper.VIDEO_PROFILE_SETTINGS}/${native.id}/${setting.key}",
                            Boolean::class.javaObjectType,
                        ) ?: setting.defaultValue
                    if (!hide) {
                        val qualities =
                            Qualities.values().map {
                                QualityDataHelper.getQualityPriority(native.id, it)
                            }
                        groups.forEachIndexed { index, source ->
                            if (source !in available) return@forEachIndexed
                            QualityDataHelper.setSourcePriority(
                                native.id,
                                source,
                                priority(index, groups.size, qualities),
                            )
                        }
                    } else {
                        // Never lift sources a native quality profile deliberately hides.
                        groups
                            .filter { it in available }
                            .forEach { QualityDataHelper.setSourcePriority(native.id, it, 1) }
                    }
                }
                true
            }
            .getOrDefault(false)

    fun clear(profile: Profile) {
        runCatching {
            QualityDataHelper.getProfiles().forEach { native ->
                profile.addons.forEach { addon ->
                    listOf(true, false).forEach {
                        QualityDataHelper.setSourcePriority(native.id, name(profile, addon, it), 1)
                    }
                }
            }
        }
    }
}
