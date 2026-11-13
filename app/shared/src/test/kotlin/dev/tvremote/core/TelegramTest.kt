package dev.tvremote.core

import dev.tvremote.core.web.Hook
import dev.tvremote.core.web.HttpServer
import dev.tvremote.core.web.Telegram
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class TelegramTest {
    private class Mem : Telegram.Store {
        override var enabled = true; override var token = "123456789:AAH-fake_token_for_tests_0123456789abc"
        override var offset = 0L; override var lang = "en"; override var bot = "@tvbot"; override var chats = "[]"; override var history = "[]"
    }

    /** A Telegram that answers from a script and remembers every call (the token is never part of what it remembers). */
    private class Fake : Telegram.Api {
        val calls = ArrayList<Pair<String, JSONObject>>()
        val photos = ArrayList<Pair<Long, Int>>()
        val captions = ArrayList<String>()
        val queue = HashMap<String, ArrayDeque<() -> Telegram.Reply>>()
        var aborted = 0
        fun on(method: String, r: () -> Telegram.Reply) { queue.getOrPut(method) { ArrayDeque() }.addLast(r) }
        override fun call(token: String, method: String, params: JSONObject, timeoutS: Int): Telegram.Reply {
            calls.add(method to params)
            return queue[method]?.removeFirstOrNull()?.invoke() ?: Telegram.Reply(200, JSONObject("""{"ok":true,"result":[]}"""))
        }
        override fun photo(token: String, chat: Long, png: ByteArray, caption: String): Telegram.Reply { photos.add(chat to png.size); captions.add(caption); return Telegram.Reply(200, JSONObject("""{"ok":true}""")) }
        override fun abort() { aborted++ }
        fun sent(chat: Long) = calls.filter { it.first == "sendMessage" && it.second.optLong("chat_id") == chat }.map { it.second.optString("text") }
        fun named(m: String) = calls.filter { it.first == m }.map { it.second }
    }

    private class Seen(val path: String, val method: String, val body: JSONObject, val scope: String)

    private val store = Mem()
    private val api = Fake()
    private var now = 1_700_000_000_000L
    private val seen = ArrayList<Seen>()
    private var answer: (HttpServer.Request) -> HttpServer.Response = { HttpServer.Response.ok() }
    private val state = JSONObject("""{"is_on":true,"volume":{"level":7,"max":15,"muted":false},"app":"com.netflix.ninja","app_label":"Netflix"}""")
    private val scenes = JSONArray("""[{"id":"s1","name":"Movie","steps":[]}]""")
    private val hook = Hook(object : Hook.Store { override var enabled = true; override var token = "x" }, Hook.Routes(), { HttpServer.Response.ok() }, { state }, { scenes }, { "" }, { now })
    private val apps = listOf("com.netflix.ninja" to "Netflix", "com.google.android.youtube.tv" to "YouTube", "com.google.android.youtube.tvmusic" to "YouTube Music")
    private var shown = ""
    private lateinit var bot: Telegram

    @Before fun setUp() {
        Words.lang = "en"
        bot = Telegram(store, api, hook, { r, scope ->
            seen.add(Seen(r.path, r.method, if (r.method == "POST") r.json() else JSONObject(), scope))
            answer(r)
        }, { state }, { apps }, { shown = it }, { now }, java.util.Random(7), background = false)
    }

    private fun msg(chat: Long, text: String, id: Long = 1, type: String = "private", date: Long = now / 1000) = JSONObject(
        """{"update_id":$id,"message":{"date":$date,"text":${JSONObject.quote(text)},"chat":{"id":$chat,"type":"$type","first_name":"An"}}}""")

    private fun pairChat(chat: Long = 42, scope: String = "remote") {
        val c = bot.newCode()
        assertTrue(bot.pair(chat, "An", c))
        if (scope != "remote") assertTrue(bot.setScope(chat, scope))
        now += 5000
    }

    // ---------------------------------------------------------------- parsing, backoff
    @Test fun commandsAreParsed() {
        assertEquals("vol" to "30", bot.let { Telegram.parse("/vol@TvBot 30", "@tvbot")!!.let { c -> c.name to c.arg } })
        assertEquals("key" to "HOME", Telegram.parse("/KEY   HOME ", "")!!.let { it.name to it.arg })
        assertEquals("say" to "ăn cơm nhé", Telegram.parse("/say ăn cơm nhé", "")!!.let { it.name to it.arg })
        assertNull(Telegram.parse("/key@otherbot HOME", "@tvbot"))      // for another bot in the same group
        assertNull(Telegram.parse("hello", ""))
        assertNull(Telegram.parse("/", ""))
        assertEquals("", Telegram.parse("/help", "")!!.arg)
        assertEquals("OK", Telegram.parse("/key\nOK", "")!!.arg)
    }

    @Test fun backoffDoublesFromFiveSecondsToAMinute() {
        var d = 0L; val seq = ArrayList<Long>()
        repeat(7) { d = Telegram.next(d); seq.add(d) }
        assertEquals(listOf(5000L, 10000L, 20000L, 40000L, 60000L, 60000L, 60000L), seq)
    }

    // ---------------------------------------------------------------- who may talk
    @Test fun aStrangerIsIgnoredEvenWithACommand() {
        bot.onUpdate(msg(7, "/off")); bot.onUpdate(msg(7, "/help", 2)); bot.onUpdate(msg(7, "/pair 000000", 3))
        assertTrue(api.calls.isEmpty()); assertTrue(seen.isEmpty())     // nothing sent, nothing pressed, not even an answer
    }

    @Test fun theRightCodePairsWithRemoteScopeAndIsUsedUp() {
        val code = bot.newCode()
        assertEquals(code, shown); assertEquals(6, code.length)
        bot.onUpdate(msg(42, "/pair $code"))
        val list = bot.chats()
        assertEquals(1, list.size); assertEquals("remote", list[0].scope); assertEquals(42L, list[0].id); assertEquals("An", list[0].name)
        assertTrue(api.sent(42)[0].contains("Paired"))
        assertFalse(bot.pair(43, "B", code))                            // one use
    }

    @Test fun aCodeExpiresAfterFiveMinutes() {
        val code = bot.newCode()
        assertEquals(300, bot.codeLeftS())
        now += 5 * 60_000L + 1
        assertFalse(bot.pair(42, "An", code)); assertEquals(0, bot.codeLeftS()); assertTrue(bot.chats().isEmpty())
    }

    @Test fun fiveWrongCodesCancelItAndLockPairingUntilTheOwnerAsksAgain() {
        val code = bot.newCode()
        repeat(4) { assertFalse(bot.pair(9, "x", "00000$it")) }
        assertEquals(0, bot.lockedLeftS())
        assertFalse(bot.pair(9, "x", "999999"))                         // the fifth
        assertTrue(bot.lockedLeftS() > 0); assertEquals(0, bot.codeLeftS())
        assertFalse(bot.pair(42, "An", code))                           // even the right one: it is dead and pairing is locked
        now += 15 * 60_000L + 1
        assertEquals(0, bot.lockedLeftS())
        val fresh = bot.newCode(); assertTrue(bot.pair(42, "An", fresh))
    }

    @Test fun anOwnerWhoMakesANewCodeLiftsTheLock() {
        bot.newCode(); repeat(5) { bot.pair(9, "x", "11111$it") }
        assertTrue(bot.lockedLeftS() > 0)
        val c = bot.newCode(); assertEquals(0, bot.lockedLeftS()); assertTrue(bot.pair(42, "An", c))
    }

    @Test fun aGroupIsPairedByItsNegativeId() {
        val c = bot.newCode()
        bot.onUpdate(JSONObject("""{"update_id":1,"message":{"date":${now / 1000},"text":"/pair@tvbot $c","chat":{"id":-100123,"type":"supergroup","title":"Family"}}}"""))
        assertEquals("Family", bot.chats().single().name); assertEquals(-100123L, bot.chats().single().id)
    }

    // ---------------------------------------------------------------- rate limit, stale messages
    @Test fun oneCommandASecondPerChat() {
        pairChat()
        bot.onUpdate(msg(42, "/key HOME", 1)); bot.onUpdate(msg(42, "/key UP", 2))
        assertEquals(1, seen.size)
        now += 1000; bot.onUpdate(msg(42, "/key UP", 3))
        assertEquals(2, seen.size)
        bot.onUpdate(msg(42, "/key DOWN", 4)); assertEquals(2, seen.size)
    }

    @Test fun theLimitIsPerChat() {
        pairChat(42); pairChat(43)
        bot.onUpdate(msg(42, "/key HOME", 1)); bot.onUpdate(msg(43, "/key HOME", 2))
        assertEquals(2, seen.size)
    }

    @Test fun aMessageThatWaitedInTelegramIsNotRun() {
        pairChat()
        bot.onUpdate(msg(42, "/off", 1, date = now / 1000 - 600))
        assertTrue(seen.isEmpty())
    }

    // ---------------------------------------------------------------- what the commands do
    @Test fun keyOffAndOnUseTheCoresOwnRoutes() {
        pairChat()
        bot.onUpdate(msg(42, "/key home", 1)); now += 1000
        bot.onUpdate(msg(42, "/off", 2)); now += 1000
        bot.onUpdate(msg(42, "/on", 3))
        assertEquals(listOf("HOME", "SLEEP", "WAKE"), seen.map { it.body.optString("key") })
        assertTrue(seen.all { it.path == "/api/key" && it.scope == "remote" })
    }

    @Test fun volumeKeysStayRemoteAndALevelIsTheOneThingARemoteChatMayDoWithSteps() {
        pairChat()
        bot.onUpdate(msg(42, "/vol up", 1)); now += 1000
        bot.onUpdate(msg(42, "/vol 30", 2))
        assertEquals("VOL_UP", seen[0].body.optString("key")); assertEquals("remote", seen[0].scope)
        assertEquals("/api/scene/run", seen[1].path); assertEquals(30, seen[1].body.getJSONArray("steps").getJSONObject(0).getInt("level")); assertEquals("full", seen[1].scope)
        now += 1000; bot.onUpdate(msg(42, "/vol 300", 3))
        assertEquals(2, seen.size); assertTrue(api.sent(42).last().startsWith("❌") || api.sent(42).last().contains("volume"))
    }

    @Test fun timerSetsAndZeroCancels() {
        pairChat()
        bot.onUpdate(msg(42, "/timer 30", 1)); now += 1000
        bot.onUpdate(msg(42, "/timer 0", 2)); now += 1000
        bot.onUpdate(msg(42, "/timer abc", 3))
        assertEquals(2, seen.size)
        assertEquals("sleep" to 30, seen[0].body.getString("do") to seen[0].body.getInt("minutes"))
        assertEquals(0, seen[1].body.getInt("minutes"))
        assertTrue(api.sent(42)[0].contains("30")); assertTrue(api.sent(42)[1].contains("cancelled")); assertTrue(api.sent(42)[2].startsWith("Use"))
    }

    @Test fun sceneByNameAndTheListWhenThereIsNone() {
        pairChat()
        bot.onUpdate(msg(42, "/scene movie", 1)); now += 1000
        assertEquals("s1", seen[0].body.optString("id"))
        bot.onUpdate(msg(42, "/scene Nope", 2))
        assertTrue(api.sent(42).last().contains("Movie"))
    }

    @Test fun openFindsAnAppByNameAndRefusesWhatTheHomeScreenDoesNotShow() {
        pairChat()
        bot.onUpdate(msg(42, "/open netflix", 1)); now += 1000
        assertEquals("com.netflix.ninja", seen[0].body.optString("link"))
        bot.onUpdate(msg(42, "/open tube", 2)); now += 1000             // two match: asks, opens nothing
        assertEquals(1, seen.size); assertTrue(api.sent(42).last().contains("YouTube Music"))
        bot.onUpdate(msg(42, "/open com.android.settings", 3)); now += 1000
        assertEquals(1, seen.size)                                      // a REMOTE chat opens only what the home screen shows
        assertTrue(bot.setScope(42, "full"))
        bot.onUpdate(msg(42, "/open com.android.settings", 4))
        assertEquals("com.android.settings", seen[1].body.optString("link")); assertEquals("full", seen[1].scope)
    }

    @Test fun sayShowsACardAndStatusReadsTheState() {
        pairChat()
        bot.onUpdate(msg(42, "/say Dinner is ready", 1)); now += 1000
        assertEquals("/api/notify", seen[0].path); assertEquals("Dinner is ready", seen[0].body.optString("text"))
        bot.onUpdate(msg(42, "/status", 2))
        val s = api.sent(42).last()
        assertTrue(s.contains("on") && s.contains("7/15") && s.contains("Netflix"))
    }

    @Test fun speakShowsACardAndAsksTheTvToReadItAndSaysWhyWhenItCannot() {
        pairChat()
        assertEquals("speak" to "ăn cơm nhé", Telegram.parse("/speak ăn cơm nhé", "")!!.let { it.name to it.arg })
        bot.onUpdate(msg(42, "/speak", 1)); assertTrue(api.sent(42).last().contains("/speak")); assertEquals(0, seen.size); now += 1000   // usage
        bot.onUpdate(msg(42, "/speak Dinner is ready", 2)); now += 1000
        assertEquals("/api/notify", seen[0].path); assertEquals("Dinner is ready", seen[0].body.optString("text"))
        assertTrue(seen[0].body.getBoolean("speak"))
        assertFalse(seen[0].body.has("lang"))                                      // Telegram told no language
        seen.clear()
        bot.onUpdate(JSONObject("""{"update_id":9,"message":{"date":${now / 1000},"text":"/speak an com","from":{"id":42,"language_code":"vi"},"chat":{"id":42,"type":"private"}}}""")); now += 1000
        assertEquals("vi", seen[0].body.optString("lang"))                         // the sender's own language goes with it
        bot.onUpdate(msg(42, "/help", 3)); assertTrue(api.sent(42).last().contains("/speak"))
    }

    @Test fun statusWithNoAppRunningDoesNotSayNull() {
        pairChat(); state.put("app", JSONObject.NULL).put("app_label", JSONObject.NULL)
        bot.onUpdate(msg(42, "/status")); assertFalse(api.sent(42).last().contains("null"))
        state.put("app", "com.netflix.ninja").put("app_label", "Netflix")
    }

    @Test fun shotSendsThePngAsAPhoto() {
        pairChat()
        answer = { HttpServer.Response.of("image/png", ByteArray(1234)) }
        bot.onUpdate(msg(42, "/shot"))
        assertEquals("/api/screen", seen[0].path); assertEquals("GET", seen[0].method)
        assertEquals(listOf(42L to 1234), api.photos)
    }

    /** A PNG head (signature + IHDR's width and height), padded to [size] bytes. */
    private fun png(w: Int, h: Int, size: Int) = ByteArray(size).also {
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()).copyInto(it)
        java.nio.ByteBuffer.wrap(it, 16, 8).putInt(w).putInt(h)
    }

    @Test fun aBlackShotSaysWhyItIsBlack() {
        pairChat()
        answer = { HttpServer.Response.of("image/png", png(1920, 1080, 10_728)) }      // a black 1080p RGBA PNG at zlib 6
        bot.onUpdate(msg(42, "/shot"))
        assertTrue(api.captions.last().contains("YouTube"))
        assertFalse(bot.looksBlack(png(1920, 1080, 2_526_541)))                       // the FPT box's home screen
        assertFalse(bot.looksBlack(ByteArray(1234)))                                    // not a PNG: no guess
    }

    @Test fun whenTheCoreRefusesTheScopeTheChatIsToldAndNothingIsHidden() {
        pairChat()
        answer = { HttpServer.Response.json(403, JSONObject().put("error", "scope")) }
        bot.onUpdate(msg(42, "/key HOME"))
        assertTrue(api.sent(42).last().contains("may not"))
    }

    @Test fun adbNotReadyIsSaidInPlainWords() {
        pairChat()
        answer = { HttpServer.Response.error(503, "adb:connecting") }
        bot.onUpdate(msg(42, "/key HOME"))
        assertTrue(api.sent(42).last().contains("not ready"))
    }

    @Test fun theKeypadTapPressesTheKeyAndAnswersTheCallback() {
        pairChat()
        bot.onUpdate(msg(42, "/remote", 1))
        val kb = api.named("sendMessage").last().getJSONObject("reply_markup").getJSONArray("inline_keyboard")
        val data = (0 until kb.length()).flatMap { r -> (0 until kb.getJSONArray(r).length()).map { kb.getJSONArray(r).getJSONObject(it).getString("callback_data") } }
        assertTrue(data.containsAll(listOf("k:UP", "k:DOWN", "k:LEFT", "k:RIGHT", "k:OK", "k:BACK", "k:HOME", "k:VOL_UP", "k:VOL_DOWN", "k:MUTE")))
        now += 1000
        bot.onUpdate(JSONObject("""{"update_id":2,"callback_query":{"id":"cb1","data":"k:UP","message":{"chat":{"id":42}}}}"""))
        assertEquals("UP", seen.last().body.optString("key"))
        assertEquals("cb1", api.named("answerCallbackQuery").last().optString("callback_query_id"))
        bot.onUpdate(JSONObject("""{"update_id":3,"callback_query":{"id":"cb2","data":"k:UP","message":{"chat":{"id":42}}}}"""))   // under 0.4 s
        assertEquals(1, seen.count { it.body.optString("key") == "UP" })
        now += 1000
        bot.onUpdate(JSONObject("""{"update_id":4,"callback_query":{"id":"cb3","data":"k:POWER","message":{"chat":{"id":42}}}}"""))   // not on the keypad
        assertEquals(1, seen.count { it.body.optString("key") == "UP" }); assertTrue(seen.none { it.body.optString("key") == "POWER" })
        bot.onUpdate(JSONObject("""{"update_id":5,"callback_query":{"id":"cb4","data":"k:UP","message":{"chat":{"id":7}}}}"""))      // a stranger's tap
        assertEquals(1, seen.count { it.body.optString("key") == "UP" })
    }

    @Test fun inTheOtherLanguageTheWordsFollow() {
        pairChat(); store.lang = "vi"
        bot.onUpdate(msg(42, "/off"))
        assertTrue(api.sent(42).last().contains("Đã tắt"))
    }

    // ---------------------------------------------------------------- the loop: offset, webhook, errors
    private fun updates(vararg u: JSONObject) = Telegram.Reply(200, JSONObject().put("ok", true).put("result", JSONArray(u.toList())))

    @Test fun theOffsetMovesPastTheBatchBeforeAnyCommandRuns() {
        pairChat()
        var offsetWhenRun = -1L
        answer = { offsetWhenRun = store.offset; HttpServer.Response.ok() }
        bot.handle(JSONArray(listOf(msg(42, "/key HOME", 10), msg(7, "x", 12), msg(42, "/key UP", 11))))
        assertEquals(13L, store.offset); assertEquals(13L, offsetWhenRun)
    }

    @Test fun theFirstStartSkipsWhatWasSentWhileNobodyListened() {
        pairChat()
        api.on("getUpdates") { updates(msg(42, "/off", 55)) }           // the backlog: answered by offset=-1, never run
        api.on("getUpdates") { updates() }
        assertEquals(0L, bot.step())
        assertEquals(56L, store.offset); assertTrue(seen.isEmpty())
        val polls = api.named("getUpdates")
        assertEquals(-1, polls[0].getInt("offset")); assertEquals(56L, polls[1].getLong("offset"))
        assertEquals(50, polls[1].getInt("timeout"))
        assertEquals(listOf("message", "callback_query"), (0 until 2).map { polls[1].getJSONArray("allowed_updates").getString(it) })
    }

    @Test fun theWebhookIsClearedAndTheCommandsSetOnceThenOnlyPolling() {
        store.offset = 5
        bot.step(); bot.step(); bot.step()
        assertEquals(1, api.named("deleteWebhook").size); assertEquals(1, api.named("setMyCommands").size)
        assertEquals(3, api.named("getUpdates").size)
        assertEquals(27, api.named("setMyCommands")[0].getJSONArray("commands").length())
        assertEquals("ok", bot.status)
    }

    @Test fun anErrorWaitsFiveThenTenSecondsAndAGoodAnswerStartsOver() {
        store.offset = 5
        api.on("getMe") { throw IOException("down") }
        api.on("getMe") { throw IOException("down") }
        assertEquals(5000L, bot.step()); assertEquals("offline", bot.status)
        assertEquals(10000L, bot.step())
        assertEquals(0L, bot.step()); assertEquals("ok", bot.status)
        api.on("getUpdates") { Telegram.Reply(500, JSONObject()) }
        assertEquals(5000L, bot.step())
    }

    @Test fun telegramAskingToSlowDownIsHonoured() {
        store.offset = 5
        api.on("getMe") { Telegram.Reply(429, JSONObject("""{"ok":false,"parameters":{"retry_after":30}}""")) }
        assertEquals(30000L, bot.step())
    }

    @Test fun aRefusedTokenStopsTheLoopUntilANewOne() {
        api.on("getMe") { Telegram.Reply(401, JSONObject("""{"ok":false,"description":"Unauthorized"}""")) }
        assertEquals(-1L, bot.step()); assertEquals("badtoken", bot.status)
    }

    @Test fun conflictWithAWebhookClearsItAgain() {
        store.offset = 5
        api.on("getUpdates") { Telegram.Reply(409, JSONObject("""{"ok":false,"description":"Conflict"}""")) }
        assertEquals(5000L, bot.step())
        bot.step()
        assertEquals(2, api.named("deleteWebhook").size)
    }

    @Test fun offOrNoTokenMakesNoCall() {
        store.enabled = false
        assertEquals(-1L, bot.step()); assertEquals("off", bot.status)
        store.enabled = true; store.token = ""
        assertEquals(-1L, bot.step()); assertTrue(api.calls.isEmpty())
    }

    // ---------------------------------------------------------------- the owner's page: secrets and undo
    private fun post(o: JSONObject): HttpServer.Response {
        val r = HttpServer.Request(); r.method = "POST"; r.path = "/api/telegram"
        val b = o.toString().toByteArray(); r.body = b.inputStream(); r.length = b.size.toLong()
        return bot.config(r)
    }

    @Test fun theTokenIsNeverSentBack() {
        val t = store.token
        val j = bot.json().toString()
        assertFalse(j.contains(t)); assertFalse(j.contains(t.substringAfter(':')))
        assertEquals("123456789:••••9abc", bot.json().getString("masked"))
        val body = String(post(JSONObject().put("do", "scope").put("id", 1).put("scope", "full")).body)
        assertFalse(body.contains(t.substringAfter(':')))
    }

    @Test fun savingATokenChecksItAndTurnsTheBotOn() {
        store.enabled = false; store.token = ""
        assertEquals(400, post(JSONObject().put("do", "token").put("token", "nonsense")).status)
        api.on("getMe") { Telegram.Reply(401, JSONObject()) }
        assertEquals(400, post(JSONObject().put("do", "token").put("token", "123456789:AAH-fake_token_for_tests_0123456789abc")).status)
        assertEquals("", store.token)
        api.on("getMe") { Telegram.Reply(200, JSONObject("""{"ok":true,"result":{"username":"my_bot"}}""")) }
        val r = post(JSONObject().put("do", "token").put("token", " 123456789:AAH-fake_token_for_tests_0123456789abc "))
        assertEquals(200, r.status); assertTrue(store.enabled); assertEquals("@my_bot", store.bot)
        bot.stop()
    }

    @Test fun offKeepsTheTokenAndChatsRemoveForgetsEverything() {
        pairChat()
        post(JSONObject().put("do", "off"))
        assertFalse(store.enabled); assertTrue(store.token.isNotEmpty()); assertEquals(1, bot.chats().size)
        post(JSONObject().put("do", "remove"))
        assertEquals("", store.token); assertTrue(bot.chats().isEmpty()); assertFalse(store.enabled); assertEquals(0L, store.offset)
        assertEquals(400, post(JSONObject().put("do", "on")).status)    // no token to turn on
    }

    @Test fun aSingleChatCanBeRemovedOrRaised() {
        pairChat(42); pairChat(43)
        post(JSONObject().put("do", "scope").put("id", 42).put("scope", "full"))
        assertEquals("full", bot.chats().first { it.id == 42L }.scope); assertEquals("remote", bot.chats().first { it.id == 43L }.scope)
        post(JSONObject().put("do", "forget").put("id", 42))
        assertEquals(listOf(43L), bot.chats().map { it.id })
        assertEquals(400, post(JSONObject().put("do", "scope").put("id", 99).put("scope", "full")).status)
        assertEquals(400, post(JSONObject().put("do", "scope").put("id", 43).put("scope", "root")).status)
        bot.onUpdate(msg(42, "/off", 1)); assertTrue(seen.isEmpty())    // forgotten: a stranger again
    }

    @Test fun makingACodeShowsItOnTheTvAndInTheState() {
        val j = JSONObject(String(post(JSONObject().put("do", "code")).body))
        assertEquals(shown, j.getString("code")); assertEquals(300, j.getInt("code_left"))
        assertFalse(bot.json().optBoolean("on").not())
    }

    @Test fun theLogNeverHoldsTheToken() {
        pairChat()
        bot.onUpdate(msg(42, "/say secret words", 1))
        api.on("getMe") { throw IOException("https://api.telegram.org/bot${store.token}/getMe failed") }
        bot.step()
        val log = WireLog.all.text()
        assertFalse(log.contains(store.token)); assertFalse(log.contains("AAH-fake_token")); assertFalse(log.contains("secret words"))
        assertTrue(log.contains("telegram"))
        assertNotNull(bot.json().optString("error"))
        assertFalse(bot.json().toString().contains("AAH-fake_token"))
    }

    // ---------------------------------------------------------------- alerts, new commands, rules with undo, history
    private fun say(chat: Long, text: String, id: Long = 1, type: String = "private") { now += 1000; bot.onUpdate(msg(chat, text, id, type)) }
    private fun tap(chat: Long, data: String, id: Long = 50, msgId: Long = 9) { now += 1000
        bot.onUpdate(JSONObject("""{"update_id":$id,"callback_query":{"id":"cb$id","data":"$data","message":{"message_id":$msgId,"chat":{"id":$chat}}}}""")) }
    private fun hist(chat: Long? = null, offset: Int = 0): JSONObject {
        val r = HttpServer.Request(); r.method = "GET"; r.path = "/api/telegram"; r.query["history"] = "1"; r.query["offset"] = "$offset"
        if (chat != null) r.query["chat"] = "$chat"
        return JSONObject(String(bot.config(r).body))
    }

    @Test fun alertsNeedAnOptInAndAreDebouncedPerKind() {
        pairChat(42); pairChat(43)
        bot.alert("on"); assertTrue(api.sent(42).none { it.contains("on") && false }); val before = api.sent(42).size
        bot.alert("on"); assertEquals(before, api.sent(42).size)                  // nobody opted in: nothing sent
        tap(42, "l:on")
        assertTrue(bot.chats().first { it.id == 42L }.alerts.contains("on")); assertTrue(bot.chats().first { it.id == 43L }.alerts.isEmpty())
        val n = api.sent(42).size
        bot.alert("on"); assertEquals(n + 1, api.sent(42).size); assertEquals(0, api.sent(43).size - api.sent(43).size)
        bot.alert("on"); assertEquals(n + 1, api.sent(42).size)                   // same kind inside a minute
        bot.alert("off"); assertEquals(n + 1, api.sent(42).size)                  // another kind, not opted in
        tap(42, "l:off", 51); bot.alert("off"); assertEquals(n + 2, api.sent(42).size)
        now += 61_000; bot.alert("on"); assertEquals(n + 3, api.sent(42).size)    // a minute later it speaks again
        tap(42, "l:on", 52); now += 61_000; bot.alert("on"); assertEquals(n + 3, api.sent(42).size)   // switched off again
    }

    @Test fun aLimitAlertCarriesMoreTimeButtonsForFullChatsOnly() {
        pairChat(42, "full"); pairChat(43, "remote")
        tap(42, "l:limit"); tap(43, "l:limit", 51)
        bot.alert("limit", "app" to "Netflix", "why" to "limit", "pkg" to "com.netflix.ninja")
        val kb = api.named("sendMessage").filter { it.optLong("chat_id") == 42L }.last().toString()
        for (d in listOf("u:30:com.netflix.ninja", "u:60:com.netflix.ninja", "u:d:com.netflix.ninja")) assertTrue(d, kb.contains(d))
        assertFalse(api.named("sendMessage").filter { it.optLong("chat_id") == 43L }.last().has("reply_markup"))   // a Remote chat cannot grant
        tap(42, "u:30:com.netflix.ninja", 5)
        assertEquals(JSONObject("""{"do":"unlock","minutes":30,"pkg":"com.netflix.ninja"}""").toString(), rulesPosts().last().toString())
    }

    @Test fun anUnoptedAlertDoesNotStartTheMinute() {
        pairChat(42)
        bot.alert("bed")
        tap(42, "l:bed"); val n = api.sent(42).size
        bot.alert("bed", "range" to "22:00"); assertEquals(n + 1, api.sent(42).size)
    }

    @Test fun alertsConfigFromThePageSetsOneKindOrAll() {
        pairChat(42)
        post(JSONObject().put("do", "alerts").put("id", 42).put("kind", "pair").put("on", true))
        assertEquals(setOf("pair"), bot.chats().first().alerts)
        post(JSONObject().put("do", "alerts").put("id", 42).put("kind", "").put("on", true))
        assertEquals(6, bot.chats().first().alerts.size)
        post(JSONObject().put("do", "alerts").put("id", 42).put("kind", "").put("on", false))
        assertTrue(bot.chats().first().alerts.isEmpty())
        assertEquals(400, post(JSONObject().put("do", "alerts").put("id", 42).put("kind", "nope").put("on", true)).status)
    }

    @Test fun startWithTheCodePairsLikePairAndWrongCodesCountToTheLock() {
        val code = bot.newCode()
        say(44, "/start 000000", 1)
        assertTrue(bot.chats().isEmpty())
        say(42, "/start $code", 2, type = "private")
        assertEquals(listOf(42L), bot.chats().map { it.id }); assertEquals("private", bot.chats()[0].type)
        say(42, "/start", 3); assertTrue(api.sent(42).size >= 2)    // paired and bare: answered like /help
        val again = bot.newCode()
        repeat(5) { say(60 + it.toLong(), "/start 99999$it", 10L + it) }
        assertTrue(bot.lockedLeftS() > 0); assertEquals(0, bot.codeLeftS()); assertFalse(again.isEmpty())
    }

    @Test fun newCommandsAreParsedAndAnswered() {
        for ((t, name, arg) in listOf(Triple("/apps", "apps", ""), Triple("/usage", "usage", ""), Triple("/now", "now", ""), Triple("/channel VTV3", "channel", "VTV3"),
            Triple("/type xin chào", "type", "xin chào"), Triple("/scenes", "scenes", ""), Triple("/bedtime", "bedtime", ""), Triple("/limits", "limits", ""),
            Triple("/schedule@TvBot", "schedule", ""), Triple("/alerts", "alerts", ""))) {
            val c = Telegram.parse(t, "@tvbot")!!; assertEquals(name, c.name); assertEquals(arg, c.arg)
        }
        pairChat(42, "full")
        answer = { r -> when (r.path) {
            "/api/stats" -> HttpServer.Response.json(JSONObject("""{"usage":{"${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))}":{"com.netflix.ninja":5400,"com.google.android.youtube.tv":600}},"labels":{}}"""))
            "/api/playing" -> HttpServer.Response.json(JSONObject("""{"state":"playing","app":"Netflix","pkg":"com.netflix.ninja","title":"Dark"}"""))
            "/api/scenes" -> HttpServer.Response.json(JSONObject().put("scenes", scenes))
            else -> HttpServer.Response.ok() } }
        say(42, "/usage", 1); assertTrue(api.sent(42).last().contains("Netflix") && api.sent(42).last().contains("1"))
        say(42, "/now", 2); assertTrue(api.sent(42).last().contains("Dark"))
        assertTrue(api.named("sendMessage").last().toString().contains("i:shot"))
        say(42, "/apps", 3); assertTrue(api.named("sendMessage").last().toString().contains("a:com.netflix.ninja"))
        say(42, "/scenes", 4); assertTrue(api.named("sendMessage").last().toString().contains("s:s1"))
        seen.clear(); say(42, "/type hello", 5)
        assertEquals(1, seen.count { it.path == "/api/text" || it.path == "/api/type" })
    }

    @Test fun channelOpensThroughTheChannelsLinkAndSaysHowToGetItWhenMissing() {
        pairChat(42, "remote")
        say(42, "/channel VTV3", 1)
        val s = seen.last { it.path == "/api/channels/open" }; assertEquals("VTV3", s.body.optString("q"))
        answer = { HttpServer.Response.error(503, "adb: channels:missing") }
        say(42, "/channel VTV3", 2)
        assertTrue(api.sent(42).last().contains("TV Channels"))
    }

    @Test fun remoteChatsReadRulesButCannotChangeThem() {
        pairChat(42, "remote")
        answer = { r -> if (r.path == "/api/rules" && r.method == "GET") HttpServer.Response.json(JSONObject("""{"bedtime":{"on":true,"ranges":[{"from":"22:00","to":"06:00"}]},"limits":[],"sched":[]}""")) else HttpServer.Response.ok() }
        say(42, "/bedtime", 1)
        val last = api.named("sendMessage").last().toString()
        assertTrue(api.sent(42).last().contains("22:00")); assertFalse(last.contains("r:bed:off")); assertFalse(last.contains("z:"))
        tap(42, "r:bed:off", 2)
        assertTrue(seen.none { it.method == "POST" && it.path == "/api/rules" })  // the tap does nothing for a remote chat
    }

    @Test fun fullChatsChangeBedtimeAndUndoPutsItBack() {
        pairChat(42, "full")
        var on = true
        answer = { r ->
            if (r.path == "/api/rules" && r.method == "GET") HttpServer.Response.json(JSONObject("""{"bedtime":{"on":$on,"ranges":[{"from":"22:00","to":"06:00"}]},"limits":[],"sched":[]}"""))
            else if (r.path == "/api/rules") { val b = r.json(); if (b.optString("do") == "bedtime") on = b.optBoolean("on"); HttpServer.Response.ok() } else HttpServer.Response.ok() }
        say(42, "/bedtime", 1)
        assertTrue(api.named("sendMessage").last().toString().contains("r:bed:off"))
        tap(42, "r:bed:off", 2)
        assertFalse(on)
        val undo = Regex("z:[A-Za-z0-9]+").find(api.calls.filter { it.first == "sendMessage" }.last().second.toString())!!.value
        tap(42, undo, 3)
        assertTrue(on)
    }

    @Test fun blockNowAndUnblockUseTheScheduleEntry() {
        pairChat(42, "full")
        answer = { r -> if (r.path == "/api/rules" && r.method == "GET") HttpServer.Response.json(JSONObject("""{"bedtime":{"on":false,"ranges":[]},"limits":[],"sched":[{"pkg":"com.netflix.ninja","label":"Netflix","block":false,"now":false}]}""")) else HttpServer.Response.ok() }
        tap(42, "r:blk:com.netflix.ninja", 2)
        val p = seen.last { it.method == "POST" && it.path == "/api/rules" }
        assertEquals("sched", p.body.optString("do")); assertEquals("com.netflix.ninja", p.body.optString("pkg")); assertTrue(p.body.optBoolean("block"))
    }

    @Test fun historyKeepsCommandsButNeverTheWordsOfTypeAndSay() {
        pairChat(42, "full")
        say(42, "/type my password 1234", 1); say(42, "/say dinner is ready", 2); say(42, "/vol 5", 3)
        say(42, "/off", 4)
        val j = hist(); val all = j.toString()
        assertFalse(all.contains("1234")); assertFalse(all.contains("password")); assertFalse(all.contains("dinner")); assertFalse(all.contains(store.token))
        val cmds = (0 until j.getJSONArray("items").length()).map { j.getJSONArray("items").getJSONObject(it).getString("cmd") }
        assertTrue(cmds.containsAll(listOf("/type", "/say", "/vol 5", "/off"))); assertEquals("/off", cmds.first())   // newest first
    }

    @Test fun historyIsCappedAtTwoHundredAndForgetsAfterSevenDays() {
        val h = Telegram.History(store, { now })
        repeat(230) { h.add(now + it, null, "/vol $it", "ok") }
        assertEquals(200, h.page(now + 300, null, 0, 1000).getInt("total"))
        assertEquals("/vol 229", h.page(now + 300, null, 0, 1).getJSONArray("items").getJSONObject(0).getString("cmd"))
        assertEquals(20, h.page(now + 300, null, 0, 20).getJSONArray("items").length())
        assertEquals(20, h.page(now + 300, null, 20, 20).getJSONArray("items").length())
        assertEquals(0, h.page(now + 8 * 86_400_000L, null, 0, 20).getInt("total"))                 // every entry is older than a week
        h.add(now + 8 * 86_400_000L, null, "/new", "ok")
        assertEquals(1, h.page(now + 8 * 86_400_000L, null, 0, 20).getInt("total"))                 // the write drops the expired ones
        assertEquals(1, JSONArray(store.history).length())
    }

    @Test fun historyFiltersByChatAndClears() {
        pairChat(42, "full"); pairChat(43, "full")
        say(42, "/vol 5", 1); say(43, "/vol 6", 2); say(43, "/vol 7", 3)
        assertEquals(1, hist(42).getInt("total")); assertEquals(2, hist(43).getInt("total"))
        post(JSONObject().put("do", "clear_history"))
        assertEquals(0, hist().getInt("total"))
    }

    @Test fun historyRecordsRefusedAndAlertsSent() {
        pairChat(42, "remote")
        answer = { HttpServer.Response.error(403, "scope") }
        say(42, "/usage", 1)
        assertEquals("scope", hist(42).getJSONArray("items").getJSONObject(0).getString("res"))
        tap(42, "l:sleep", 2); bot.alert("sleep")
        val first = hist().getJSONArray("items").getJSONObject(0)
        assertEquals("alert sleep", first.getString("cmd")); assertEquals("An", first.getJSONArray("to").getString(0))
    }

    @Test fun theStateTellsWhenTelegramLastAnswered() {
        val j = bot.json(); assertEquals(0L, j.optLong("last_ok")); assertTrue(j.has("now"))
    }

    @Test fun forTvHomeBotNameAndLinkComeWithTheFreshCode() {
        val code = bot.newCode()
        val j = bot.json()
        assertEquals("https://t.me/tvbot", j.optString("bot_link")); assertEquals("https://t.me/tvbot?start=$code", j.optString("link"))
    }

    // ---------------------------------------------------------------- kids mode
    private fun rulesPosts() = seen.filter { it.method == "POST" && it.path == "/api/rules" }.map { it.body }

    @Test fun unlockLockTimeAndMoreAreFullChatCommands() {
        pairChat(42, "full")
        val total = JSONObject("""{"total":{"min":120,"used":45,"extra":0,"days":[]},"unlock_until":0,"now":$now}""")
        answer = { r -> if (r.path == "/api/rules" && r.method == "GET") HttpServer.Response.json(total) else HttpServer.Response.ok() }
        say(42, "/unlock", 1); say(42, "/unlock 90", 2); say(42, "/unlock 0", 3); say(42, "/lock", 4)
        assertEquals(listOf(30, 90, 0), rulesPosts().map { it.optInt("minutes") })
        assertTrue(api.sent(42).any { it.contains("30 minutes") }); assertTrue(api.sent(42).any { it.startsWith("Usage: /unlock") }); assertTrue(api.sent(42).any { it.contains("Locked again") })
        say(42, "/time", 5)
        assertTrue(api.sent(42).last().contains("45 of 120") && api.sent(42).last().contains("75 min left"))
        say(42, "/more 30", 6); say(42, "/more x", 7)
        assertEquals(JSONObject("""{"do":"total_extra","minutes":30}""").toString(), rulesPosts().last().toString())
        assertTrue(api.named("sendMessage").any { it.toString().contains("\"z:") })                 // with an Undo
        assertTrue(api.sent(42).last().startsWith("Usage: /more"))
    }

    @Test fun remoteChatsMayLookAtTheTimeButNotChangeIt() {
        pairChat(42, "remote")
        answer = { r -> if (r.method == "GET") HttpServer.Response.json(JSONObject("""{"total":{"min":0}}""")) else HttpServer.Response.ok() }
        say(42, "/time", 1); say(42, "/unlock 30", 2); say(42, "/lock", 3); say(42, "/more 30", 4)
        assertTrue(api.sent(42)[0].contains("No limit")); assertTrue(rulesPosts().isEmpty())
        assertEquals(3, api.sent(42).count { it.contains("only look") })
    }

    @Test fun askAParentGoesToFullChatsOnceInTwoMinutesAndAButtonGrantsIt() {
        pairChat(42, "full"); pairChat(43, "remote")
        assertTrue(bot.hasParent())
        assertEquals("sent", bot.askParent("com.netflix.ninja", "Netflix", "Living room"))
        assertTrue(api.sent(42)[0].contains("Netflix") && api.sent(42)[0].contains("Living room")); assertTrue(api.sent(43).isEmpty())
        val kb = api.named("sendMessage").last().toString()
        for (d in listOf("u:30:com.netflix.ninja", "u:60:com.netflix.ninja", "u:d:com.netflix.ninja", "u:n")) assertTrue(d, kb.contains(d))
        now += 60_000; assertEquals("wait", bot.askParent("com.netflix.ninja", "Netflix", "Living room"))      // debounced
        assertEquals(1, api.sent(42).size)
        tap(42, "u:30:com.netflix.ninja", 5)
        assertEquals(JSONObject("""{"do":"unlock","minutes":30,"pkg":"com.netflix.ninja"}""").toString(), rulesPosts().last().toString())
        assertTrue(api.named("editMessageReplyMarkup").isNotEmpty())                                         // the buttons go
        now += 61_000; assertEquals("sent", bot.askParent("", "", "Living room"))                           // two minutes passed
        assertTrue(api.sent(42).last().contains("more TV time"))
        tap(42, "u:d:", 6)
        assertTrue(rulesPosts().last().optBoolean("today")); assertFalse(rulesPosts().last().has("pkg"))
        tap(42, "u:n", 7); assertEquals(2, rulesPosts().size)
        assertTrue(bot.json().optJSONArray("chats") != null)
    }

    @Test fun noParentChatMeansNoQuestionAndARemoteChatCannotAnswer() {
        pairChat(43, "remote")
        assertFalse(bot.hasParent()); assertEquals("nochat", bot.askParent("com.netflix.ninja", "Netflix", "TV"))
        tap(43, "u:30:com.netflix.ninja", 5)
        assertTrue(rulesPosts().isEmpty())
    }

    @Test fun unlockAndAskAppearInTheHistory() {
        pairChat(42, "full")
        say(42, "/unlock 20", 1); bot.askParent("com.netflix.ninja", "Netflix", "TV"); tap(42, "u:60:com.netflix.ninja", 5)
        val cmds = bot.config(HttpServer.Request().also { it.method = "GET"; it.query["history"] = "1" }).let { JSONObject(String(it.body)).getJSONArray("items") }
        val names = (0 until cmds.length()).map { cmds.getJSONObject(it).optString("cmd") }
        assertTrue(names.any { it == "/unlock 20" }); assertTrue(names.any { it.startsWith("ask ") }); assertTrue(names.any { it == "tap u 60:com.netflix.ninja" })
    }

    @Test fun threeWrongCodesTellFullChatsOnceInFiveMinutesAndUnlocksAreInTheHistory() {
        pairChat(42, "full"); pairChat(43, "remote")
        bot.wrongCodes("Netflix", "Living room")
        assertTrue(api.sent(42).last().contains("3 times") && api.sent(42).last().contains("Netflix") && api.sent(42).last().contains("Living room")); assertTrue(api.sent(43).isEmpty())
        val n = api.sent(42).size
        now += 60_000; bot.wrongCodes("Netflix", "Living room"); assertEquals(n, api.sent(42).size)
        now += 5 * 60_000; bot.wrongCodes("Netflix", "Living room"); assertEquals(n + 1, api.sent(42).size)
        bot.unlocked("totp", "com.netflix.ninja"); bot.unlocked("recovery", "com.hulu")
        val items = hist().getJSONArray("items"); val cmds = (0 until items.length()).map { items.getJSONObject(it).optString("cmd") }
        assertTrue(cmds.any { it == "unlock totp com.netflix.ninja" } && cmds.any { it == "unlock recovery com.hulu" })
    }

    @Test fun messageTimesFeedTheClockAndASlowTvStillHearsFreshCommands() {
        val dates = ArrayList<Long>()
        var skew = 0L
        val b = Telegram(store, api, hook, { _, _ -> HttpServer.Response.ok() }, { state }, { apps }, { }, { now }, java.util.Random(7), background = false, onDate = { dates.add(it) }, skew = { skew })
        val c = b.newCode(); assertTrue(b.pair(42, "An", c)); now += 5000
        now += 200_000; skew = 200_000                                                                    // the TV is 200 s fast: a message sent just now looks 200 s old
        b.onUpdate(msg(42, "/status", 1, date = (now - skew) / 1000))
        assertEquals(1, dates.size); assertTrue(api.sent(42).any { it.contains("TV") })
        val before = api.sent(42).size; skew = 0
        b.onUpdate(msg(42, "/status", 2, date = (now - 200_000) / 1000 - 1)); assertEquals(before, api.sent(42).size)   // really old: ignored
    }
}
