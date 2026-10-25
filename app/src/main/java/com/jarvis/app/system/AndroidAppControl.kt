package com.jarvis.app.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import com.jarvis.app.R
import com.jarvis.core.skills.AppControl
import com.jarvis.core.skills.AppControlPhrases
import com.jarvis.core.skills.AppMatcher
import com.jarvis.core.skills.LaunchableApp
import com.jarvis.core.skills.SkillCall
import com.jarvis.core.skills.SkillResult
import com.jarvis.core.skills.UiNode
import com.jarvis.core.skills.VolumeLevels
import com.jarvis.core.skills.YouTubeUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AndroidAppControl(context: Context) : AppControl {
    private val appContext = context.applicationContext
    private val packages = appContext.packageManager
    private val playback = PlaybackMonitor(appContext, onPlaybackStopped = { pkg ->
        Log.i(TAG, "auto-return after $pkg stopped")
        KioskGate.returnToJarvis(appContext)
    })

    init {
        playback.start()
    }

    override suspend fun perform(call: SkillCall): SkillResult {
        val slots = call.slots
        val action = slots["action"].orEmpty()
        Log.i(
            TAG,
            "intent matched action=$action target=${slots["target"].orEmpty()} query=${slots["query"].orEmpty()} " +
                "media=${slots["media"].orEmpty()} volume=${slots["volume"].orEmpty()}",
        )
        playback.setBusy(true)
        return try {
            withContext(Dispatchers.Main) {
                when (action) {
                    AppControlPhrases.OPEN -> open(slots)
                    AppControlPhrases.PLAY -> play(slots)
                    AppControlPhrases.MEDIA -> media(slots)
                    AppControlPhrases.VOLUME -> volume(slots)
                    AppControlPhrases.SKIP_AD -> skipAd(slots)
                    AppControlPhrases.RETURN -> goBack(slots)
                    else -> SkillResult("I didn't catch that.", success = false)
                }
            }
        } finally {
            playback.setBusy(false)
        }
    }

    private suspend fun open(slots: Map<String, String>): SkillResult {
        val arabic = slots["lang"] == "ar"
        val query = slots["target"].orEmpty()
        val match = AppMatcher.best(query, launchers())
        if (match.ambiguous) {
            return SkillResult(if (arabic) "في أكثر من تطبيق." else "More than one app matches.", success = false)
        }
        val app = match.app ?: return SkillResult(
            if (arabic) "ما لقيت التطبيق." else "I can't find that app.",
            success = false,
        )
        if (!launch(app.packageName)) {
            return SkillResult(if (arabic) "ما قدرت أفتح التطبيق." else "I couldn't open that app.", success = false)
        }
        return SkillResult(if (arabic) "بفتح ${app.label}." else "Opening ${app.label}.")
    }

    private suspend fun play(slots: Map<String, String>): SkillResult {
        val arabic = slots["lang"] == "ar"
        val query = slots["query"].orEmpty()
        val target = slots["target"].orEmpty()
        if (query.isBlank()) return SkillResult(if (arabic) "شغل إيه؟" else "Play what?", success = false)
        val named = packageForTarget(target)
        if (target.isNotEmpty() && named == null) {
            return SkillResult(missingTarget(target, arabic), success = false)
        }
        if (named != null && named != AppMatcher.YOUTUBE) {
            val directStarted = directPlay(named, query)
            if (named == AppMatcher.YOUTUBE_MUSIC) {
                return playOnYouTubeMusic(query, arabic, directStarted)
            }
            if (directStarted) {
                playback.keepPlaying(named)
                return SkillResult(if (arabic) "شغال." else "Playing.")
            }
            return SkillResult(
                if (arabic) "فتحت التطبيق، بس ما قدرت أشغل." else "I opened the app, but I couldn't start playback.",
                success = false,
            )
        }
        if (target.isEmpty()) {
            val player = defaultDirectPlayer()
            if (player != null && directPlay(player, query)) {
                playback.keepPlaying(player)
                return SkillResult(if (arabic) "شغال." else "Playing.")
            }
        }
        if ((target.isEmpty() || target == AppControlPhrases.TARGET_YOUTUBE) && youtubeInstalled()) {
            if (!playback.isStarted(AppMatcher.YOUTUBE) && directPlay(AppMatcher.YOUTUBE, query)) {
                playback.keepPlaying(AppMatcher.YOUTUBE)
                return SkillResult(if (arabic) "شغال على يوتيوب." else "Playing on YouTube.")
            }
            return playOnYouTube(query, arabic)
        }
        return SkillResult(
            if (arabic) "ما لقيت تطبيق يشغل ده." else "I can't find an app that plays that.",
            success = false,
        )
    }

    private suspend fun playOnYouTubeMusic(query: String, arabic: Boolean, directStarted: Boolean): SkillResult {
        var tapStarted = false
        if (!directStarted && openYouTubeMusicSearch(query)) {
            Log.i(TAG, "ytm search opened")
            val service = awaitYouTubeService()
            if (service == null) {
                Log.i(TAG, "ytm accessibility missing, tapping skipped")
            } else {
                tapStarted = tapYouTubeMusicResult(service)
            }
        }
        val youtubeAvailable = youtubeInstalled()
        var youtubeStarted = false
        if (!directStarted && !tapStarted && youtubeAvailable) {
            youtubeStarted = playOnYouTube(query, arabic).success
        }
        val outcome = YouTubeMusicRouting.outcome(directStarted, tapStarted, youtubeAvailable, youtubeStarted)
        if (outcome == MusicPlayOutcome.PLAYING_ON_YT_MUSIC) {
            playback.keepPlaying(AppMatcher.YOUTUBE_MUSIC)
        }
        return SkillResult(YouTubeMusicRouting.message(outcome, arabic), success = outcome != MusicPlayOutcome.FAILED)
    }

    private suspend fun openYouTubeMusicSearch(query: String): Boolean {
        val uri = Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")
        val view = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(AppMatcher.YOUTUBE_MUSIC)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (resolves(view)) return startExternal(AppMatcher.YOUTUBE_MUSIC, view)
        val search = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(AppMatcher.YOUTUBE_MUSIC)
            putExtra(android.app.SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!resolves(search)) return false
        return startExternal(AppMatcher.YOUTUBE_MUSIC, search)
    }

    private suspend fun tapYouTubeMusicResult(service: JarvisAccessibilityService): Boolean {
        Log.i(TAG, "ytm screen ${service.screenWidth()}x${service.screenHeight()} enabled=${JarvisAccessibilityService.isEnabled(appContext)}")
        for (attempt in 0 until 3) {
            if (attempt == 0 && !awaitYouTubeMusicResults(service)) {
                Log.i(TAG, "ytm results did not appear")
                return false
            }
            if (attempt > 0) delay(800)
            val nodes = service.snapshot()
            val pick = YouTubeMusicResultPicker.bestPlayable(nodes)
            if (pick == null) {
                Log.i(TAG, "ytm no tappable result (attempt $attempt)")
                continue
            }
            val label = pick.desc.ifBlank { pick.text }.trim()
            Log.i(TAG, "ytm tap \"$label\"")
            service.tap(pick)
            delay(1_500)
            if (awaitStarted(AppMatcher.YOUTUBE_MUSIC, 4_000)) {
                Log.i(TAG, "ytm started=true")
                return true
            }
            Log.i(TAG, "ytm started=false, media key backup")
            playback.dispatch(KeyEvent.KEYCODE_MEDIA_PLAY)
            if (awaitStarted(AppMatcher.YOUTUBE_MUSIC, 4_000)) {
                Log.i(TAG, "ytm started=true (media key)")
                return true
            }
        }
        Log.i(TAG, "ytm tap failed")
        return false
    }

    private suspend fun awaitYouTubeMusicResults(service: JarvisAccessibilityService): Boolean {
        repeat(20) {
            if (YouTubeMusicResultPicker.bestPlayable(service.snapshot()) != null) return true
            delay(300)
        }
        return false
    }

    private suspend fun playOnYouTube(query: String, arabic: Boolean): SkillResult {
        if (!openYouTubeSearch(query)) {
            return SkillResult(
                if (arabic) "يوتيوب مش متثبت." else "YouTube isn't installed.",
                success = false,
            )
        }
        val service = awaitYouTubeService()
        if (service == null) {
            Log.i(TAG, "accessibility disabled, search only")
            return SkillResult(searchOnly(arabic), success = false)
        }
        val started = tapFirstVideo(service)
        if (!started) {
            return SkillResult(
                if (arabic) "فتحت البحث في يوتيوب، بس ما قدرت أشغل." else "I opened the YouTube search, but I couldn't start playback.",
                success = false,
            )
        }
        playback.keepPlaying(AppMatcher.YOUTUBE)
        return SkillResult(if (arabic) "شغال على يوتيوب." else "Playing on YouTube.")
    }

    private suspend fun tapFirstVideo(service: JarvisAccessibilityService): Boolean {
        Log.i(TAG, "youtube screen ${service.screenWidth()}x${service.screenHeight()} enabled=${JarvisAccessibilityService.isEnabled(appContext)}")
        if (!awaitResults(service)) {
            Log.i(TAG, "youtube results did not appear")
            return false
        }
        var mix: UiNode? = null
        var tapped = false
        var scrolls = 0
        for (attempt in 0 until 8) {
            val nodes = service.snapshot()
            val width = service.screenWidth()
            val height = service.screenHeight()
            val video = YouTubeUi.firstVideo(nodes, width, height)
            if (video != null) {
                service.tap(video)
                tapped = true
                delay(1_200)
                if (startedOnWatchPage(service, service.snapshot(), width)) return true
                return awaitPlayback(service)
            }
            val visibleMix = YouTubeUi.firstMix(nodes, width, height)
            if (visibleMix != null) {
                // A playlist row is the result. Another scroll pushes it under the nav bar.
                service.tap(visibleMix)
                tapped = true
                delay(1_200)
                if (startedOnWatchPage(service, service.snapshot(), width)) return true
                return awaitPlayback(service)
            }
            mix = visibleMix ?: mix
            if (attempt == 0) logRows(nodes)
            // The search field appears before the rows. Scrolling then jumps past the first videos.
            if (!YouTubeUi.hasLoadedRows(nodes)) {
                delay(400)
                continue
            }
            if (scrolls >= 3 || !service.scrollResults()) break
            scrolls++
            delay(700)
        }
        if (!tapped && mix != null) {
            service.tap(mix)
            delay(1_200)
        }
        return awaitPlayback(service)
    }

    private suspend fun awaitResults(service: JarvisAccessibilityService): Boolean {
        repeat(16) {
            if (YouTubeUi.isResults(service.snapshot())) return true
            delay(300)
        }
        return YouTubeUi.isResults(service.snapshot())
    }

    private suspend fun awaitPlayback(service: JarvisAccessibilityService): Boolean {
        var remaining = 18
        while (remaining > 0) {
            remaining--
            val nodes = service.snapshot()
            val width = service.screenWidth()
            if (startedOnWatchPage(service, nodes, width)) return true
            val playAll = YouTubeUi.playAll(nodes)
            if (playAll != null && !YouTubeUi.isFullPlayer(nodes, width)) {
                service.tap(playAll)
                delay(800)
                continue
            }
            val skip = YouTubeUi.skipButton(nodes)
            if (skip != null) {
                service.tap(skip)
                delay(800)
                continue
            }
            if (YouTubeUi.isFullPlayer(nodes, width)) {
                val play = YouTubeUi.control(nodes, "play")
                if (play != null && !playback.isStarted(AppMatcher.YOUTUBE)) {
                    service.tap(play)
                }
            }
            delay(400)
        }
        val nodes = service.snapshot()
        return startedOnWatchPage(service, nodes, service.screenWidth())
    }

    private suspend fun startedOnWatchPage(
        service: JarvisAccessibilityService,
        nodes: List<UiNode>,
        width: Int,
    ): Boolean {
        if (!playback.isStarted(AppMatcher.YOUTUBE)) return false
        val skip = YouTubeUi.skipButton(nodes)
        if (skip != null) {
            service.tap(skip)
            delay(600)
            return false
        }
        return YouTubeUi.isFullPlayer(nodes, width)
    }

    private fun logRows(nodes: List<UiNode>) {
        val rows = nodes.filter { it.clickable && (it.desc.isNotBlank() || it.text.isNotBlank()) }.take(8)
        Log.i(TAG, "youtube rows ${rows.size}")
        for (row in rows) {
            Log.i("JarvisAccessibility", "row h=${row.height} w=${row.width} ${row.desc.ifBlank { row.text }.take(110)}")
        }
    }

    private suspend fun directPlay(packageName: String, query: String): Boolean {
        if (!playback.isStarted(packageName)) {
            if (!sendPlayFromSearch(packageName, query)) return false
            if (awaitStarted(packageName, 2_500)) return true
        }
        return false
    }

    private suspend fun media(slots: Map<String, String>): SkillResult {
        val arabic = slots["lang"] == "ar"
        playback.cancelKeep()
        val which = slots["media"].orEmpty()
        if (which == "pause") playback.noteUserPaused(true) else playback.noteUserPaused(false)
        val key = when (which) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return SkillResult(if (arabic) "ما فهمتش." else "I didn't catch that.", success = false)
        }
        playback.dispatch(key)
        delay(700)
        val youtube = JarvisAccessibilityService.instance
        if (youtube != null && which != "previous") {
            val still = when (which) {
                "pause" -> playback.isStarted(AppMatcher.YOUTUBE)
                "resume" -> !playback.isStarted(AppMatcher.YOUTUBE)
                else -> false
            }
            if (still) {
                val action = if (which == "resume") "play" else which
                youtube.tapControl(action)
                delay(500)
            }
        }
        val ok = when (which) {
            "pause" -> !anyWatchedStarted()
            "resume" -> anyWatchedStarted() || true
            else -> true
        }
        if (which == "pause" && anyWatchedStarted()) {
            return SkillResult(if (arabic) "ما قدرت أوقف." else "I couldn't pause that.", success = false)
        }
        val text = when (which) {
            "pause" -> if (arabic) "وقفت." else "Paused."
            "resume" -> if (arabic) "كملت." else "Playing."
            "next" -> if (arabic) "التالي." else "Next."
            else -> if (arabic) "السابق." else "Previous."
        }
        return SkillResult(text, success = ok)
    }

    private fun volume(slots: Map<String, String>): SkillResult {
        val arabic = slots["lang"] == "ar"
        return when (slots["volume"]) {
            "up" -> {
                val before = playback.musicVolume()
                playback.adjust(AudioManager.ADJUST_RAISE)
                val after = playback.musicVolume()
                if (after > before) {
                    SkillResult(if (arabic) "رفعت الصوت." else "Volume up.")
                } else {
                    SkillResult(if (arabic) "الصوت أعلى حاجة." else "Volume is already at the top.")
                }
            }
            "down" -> {
                val before = playback.musicVolume()
                playback.adjust(AudioManager.ADJUST_LOWER)
                val after = playback.musicVolume()
                if (after < before) {
                    SkillResult(if (arabic) "وطيت الصوت." else "Volume down.")
                } else {
                    SkillResult(if (arabic) "الصوت أقل حاجة." else "Volume is already at the bottom.")
                }
            }
            "mute" -> {
                playback.setMuted(true)
                SkillResult(if (arabic) "كتمت الصوت." else "Muted.")
            }
            "unmute" -> {
                playback.setMuted(false)
                SkillResult(if (arabic) "فتحت الصوت." else "Unmuted.")
            }
            "set" -> {
                val amount = slots["amount"]?.toIntOrNull()
                if (amount == null) {
                    return SkillResult(if (arabic) "الصوت كام؟" else "Set the volume to what?", success = false)
                }
                val (min, max) = playback.musicRange()
                val level = VolumeLevels.resolve(min, max, amount, slots["unit"].orEmpty())
                playback.setLevel(level)
                SkillResult(if (arabic) "الصوت $level." else "Volume $level.")
            }
            else -> SkillResult(if (arabic) "ما فهمتش." else "I didn't catch that.", success = false)
        }
    }

    private suspend fun skipAd(slots: Map<String, String>): SkillResult {
        val arabic = slots["lang"] == "ar"
        val service = awaitYouTubeService()
        if (service == null) {
            return SkillResult(enableAccessibility(arabic), success = false)
        }
        val skipped = service.skipAd()
        if (!skipped) {
            return SkillResult(if (arabic) "ما شفت زر التخطي." else "I don't see a skip button.", success = false)
        }
        awaitStarted(AppMatcher.YOUTUBE, 4_000)
        return SkillResult(if (arabic) "تخطيت الإعلان." else "Skipped the ad.")
    }

    private fun goBack(slots: Map<String, String>): SkillResult {
        val arabic = slots["lang"] == "ar"
        playback.cancelKeep()
        playback.disarm()
        KioskGate.returnToJarvis(appContext)
        return SkillResult(if (arabic) "رجعت." else "Back.")
    }

    private suspend fun launch(packageName: String): Boolean {
        val intent = packages.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startExternal(packageName, intent)
    }

    private suspend fun openYouTubeSearch(query: String): Boolean {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
        val view = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(AppMatcher.YOUTUBE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (resolves(view)) return startExternal(AppMatcher.YOUTUBE, view)
        val search = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(AppMatcher.YOUTUBE)
            putExtra(android.app.SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!resolves(search)) return false
        return startExternal(AppMatcher.YOUTUBE, search)
    }

    private suspend fun sendPlayFromSearch(packageName: String, query: String): Boolean {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage(packageName)
            putExtra(android.app.SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!resolves(intent)) return false
        return startExternal(packageName, intent)
    }

    private suspend fun startExternal(packageName: String, intent: Intent): Boolean {
        KioskGate.prepareToLeave(appContext, packageName)
        ListenForegroundService.startWhileVisible(appContext)
        playback.arm(packageName)
        return try {
            appContext.startActivity(intent)
            Log.i(TAG, "package launched $packageName ${intent.action}")
            true
        } catch (_: ActivityNotFoundException) {
            Log.i(TAG, "package launch failed $packageName")
            false
        } catch (_: SecurityException) {
            Log.i(TAG, "package launch blocked $packageName")
            false
        }
    }

    private suspend fun awaitYouTubeService(): JarvisAccessibilityService? {
        if (!JarvisAccessibilityService.isEnabled(appContext)) return null
        repeat(20) {
            JarvisAccessibilityService.instance?.let { return it }
            delay(150)
        }
        return JarvisAccessibilityService.instance
    }

    private suspend fun awaitStarted(packageName: String, timeoutMs: Long): Boolean {
        val slices = (timeoutMs / 250).toInt().coerceAtLeast(1)
        var hits = 0
        repeat(slices) {
            if (playback.isStarted(packageName)) {
                hits++
                if (hits >= 2) return true
            } else {
                hits = 0
            }
            delay(250)
        }
        return playback.isStarted(packageName)
    }

    private fun anyWatchedStarted(): Boolean {
        val watched = listOf(
            AppMatcher.YOUTUBE,
            AppMatcher.YOUTUBE_MUSIC,
            AppMatcher.SPOTIFY,
            AppMatcher.SAMSUNG_MUSIC,
            AppMatcher.SAMSUNG_MUSIC_ALT,
        )
        return watched.any { playback.isStarted(it) }
    }

    private fun launchers(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packages.queryIntentActivities(intent, 0).map { info ->
            LaunchableApp(
                packageName = info.activityInfo.packageName,
                label = info.loadLabel(packages).toString(),
            )
        }.distinctBy { it.packageName }
    }

    private fun defaultDirectPlayer(): String? {
        val probe = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(android.app.SearchManager.QUERY, "music")
        }
        val resolved = packages.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        val pkg = resolved?.activityInfo?.packageName
        if (pkg != null && pkg in AppMatcher.DIRECT_PLAY_PACKAGES && installed(pkg) && resolvesPlay(pkg)) {
            return pkg
        }
        return AppMatcher.DIRECT_PLAY_PACKAGES.firstOrNull { installed(it) && resolvesPlay(it) }
    }

    private fun packageForTarget(target: String): String? {
        val candidates = when (target) {
            AppControlPhrases.TARGET_YOUTUBE -> listOf(AppMatcher.YOUTUBE)
            AppControlPhrases.TARGET_YOUTUBE_MUSIC -> listOf(AppMatcher.YOUTUBE_MUSIC)
            AppControlPhrases.TARGET_SPOTIFY -> listOf(AppMatcher.SPOTIFY)
            AppControlPhrases.TARGET_SAMSUNG -> listOf(AppMatcher.SAMSUNG_MUSIC, AppMatcher.SAMSUNG_MUSIC_ALT)
            else -> return null
        }
        return candidates.firstOrNull { installed(it) }
    }

    private fun resolvesPlay(packageName: String): Boolean {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage(packageName)
        }
        return resolves(intent)
    }

    private fun resolves(intent: Intent): Boolean = intent.resolveActivity(packages) != null

    private fun installed(packageName: String): Boolean {
        return try {
            packages.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun youtubeInstalled(): Boolean = installed(AppMatcher.YOUTUBE)

    private fun missingTarget(target: String, arabic: Boolean): String {
        val name = when (target) {
            AppControlPhrases.TARGET_YOUTUBE -> if (arabic) "يوتيوب" else "YouTube"
            AppControlPhrases.TARGET_YOUTUBE_MUSIC -> if (arabic) "يوتيوب ميوزك" else "YouTube Music"
            AppControlPhrases.TARGET_SPOTIFY -> if (arabic) "سبوتيفاي" else "Spotify"
            AppControlPhrases.TARGET_SAMSUNG -> if (arabic) "سامسونج ميوزك" else "Samsung Music"
            else -> target
        }
        return if (arabic) "$name مش متثبت." else "$name isn't installed."
    }

    private fun searchOnly(arabic: Boolean): String {
        val path = appContext.getString(R.string.accessibility_onboarding)
        return if (arabic) {
            "فتحت البحث في يوتيوب، بس التشغيل ما بدأش. $path"
        } else {
            "I opened the YouTube search, but playback didn't start. $path"
        }
    }

    private fun enableAccessibility(arabic: Boolean): String {
        val path = appContext.getString(R.string.accessibility_onboarding)
        return if (arabic) "محتاج أشغّل خدمة جارفس. $path" else path
    }

    private companion object {
        const val TAG = "JarvisAppControl"
    }
}
