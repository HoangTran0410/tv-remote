package dev.tvremote.setup.rules

import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Kids mode: the Authenticator's codes, the wait after wrong codes, what asks for a code, the day's total, the TV clock. */
class KidsTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 24, 15, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun d(s: String) = JSONObject(s)
    private val free = Kids.assemble(listOf("com.vendor.launcher"), listOf("com.vendor.keyboard"), "com.vendor.ime/.Service")
    private val B32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"                      // RFC 6238's "12345678901234567890"

    private fun kids(extra: String = ""): JSONObject {
        val o = JSONObject()
        Rules.apply(o, d("""{"do":"pin","pin":"2580"}"""), now)
        Rules.apply(o, d("""{"do":"kids","mode":"kids","allow":["com.google.android.apps.youtube.mediacorp"]$extra}"""), now)
        return o
    }

    // ---- TOTP: RFC 6238 appendix B (SHA1), 8 digits there; the last 6 of each are the 6-digit code
    @Test fun rfcVectors() {
        val key = Totp.base32(B32)!!
        assertEquals("12345678901234567890", String(key))
        for ((t, code) in listOf(59L to "94287082", 1111111109L to "07081804", 1111111111L to "14050471", 1234567890L to "89005924", 2000000000L to "69279037", 20000000000L to "65353130")) {
            assertEquals(code, Totp.code(key, t / 30, 8))
            assertEquals(code.takeLast(6), Totp.code(key, t / 30))
        }
    }

    @Test fun base32IgnoresSpacesCaseAndPadding() {
        assertEquals("12345678901234567890", String(Totp.base32("gezd gnbv-gy3t qojq GEZDGNBVGY3TQOJQ====")!!))
        assertNull(Totp.base32("GEZ1")); assertNull(Totp.base32(""))
    }

    @Test fun windowIsOneStepEitherSideAndFollowsTheCorrectedClock() {
        val t = 1111111111L * 1000                                           // code 050471 (the 6 last of 14050471)
        assertTrue(Totp.verify(B32, "050471", t))
        assertTrue(Totp.verify(B32, "050471", t + 30_000)); assertTrue(Totp.verify(B32, "050471", t - 30_000))
        assertFalse(Totp.verify(B32, "050471", t + 90_000))
        assertFalse(Totp.verify(B32, "05047", t)); assertFalse(Totp.verify(B32, "05047a", t))
        val tvAheadByThreeMinutes = 180_000L                                  // the TV's clock is 3 min fast; the checker is given now - drift
        assertTrue(Totp.verify(B32, "050471", (t + tvAheadByThreeMinutes) - tvAheadByThreeMinutes))
        assertFalse(Totp.verify(B32, "050471", t + tvAheadByThreeMinutes))
    }

    @Test fun totpSetupNeedsARightCodeAndKeepsTheSecretOutOfTheAnswer() {
        val o = JSONObject()
        val code = Totp.code(Totp.base32(B32)!!, now / 30_000)
        try { Rules.apply(o, d("""{"do":"totp","secret":"$B32","code":"000000","client_now":$now}"""), now); fail() } catch (e: CodeWrong) { assertEquals(0L, e.clientDriftS) }
        assertFalse(Rules.totpOn(o))
        Rules.apply(o, d("""{"do":"totp","secret":"$B32","code":"$code","client_now":$now}"""), now)
        assertTrue(Rules.totpOn(o)); assertTrue(Rules.methods(o).getBoolean("totp"))
        assertTrue(Rules.totpOk(o, code, now, 0)); assertFalse(Rules.totpOk(o, "123456", now, 0))
        Rules.apply(o, d("""{"do":"totp","off":true}"""), now)
        assertFalse(Rules.totpOn(o))
    }

    @Test fun aClientWithAnotherClockIsJudgedByItsOwnTime() {
        val o = JSONObject()
        val phone = now - 200_000L                                            // the TV is 200 s ahead of the phone
        val code = Totp.code(Totp.base32(B32)!!, phone / 30_000)
        Rules.apply(o, d("""{"do":"totp","secret":"$B32","code":"$code","client_now":$phone}"""), now)
        assertTrue(Rules.totpOn(o))
        try { Rules.apply(JSONObject(), d("""{"do":"totp","secret":"$B32","code":"$code"}"""), now, 0L); fail("no client time, TV clock wrong") } catch (e: CodeWrong) { assertNull(e.clientDriftS) }
        Rules.apply(JSONObject(), d("""{"do":"totp","secret":"$B32","code":"$code"}"""), now, 200_000L)   // the TV knows its drift
    }

    // ---- recovery codes
    @Test fun recoveryCodesAreHashedReturnedOnceAndSpentOnUse() {
        val o = JSONObject()
        val code = Totp.code(Totp.base32(B32)!!, now / 30_000)
        val dd = d("""{"do":"totp","secret":"$B32","code":"$code","client_now":$now}""")
        Rules.apply(o, dd, now)
        val codes = dd.getJSONArray("recovery_out")
        assertEquals(8, codes.length()); assertEquals(8, Rules.recoveryLeft(o))
        assertTrue((0 until 8).all { codes.getString(it).matches(Regex("\\d{8}")) })
        assertFalse(o.toString().contains(codes.getString(0)))               // only hashes are kept
        assertTrue(Rules.spendRecovery(o, codes.getString(0)))
        assertFalse(Rules.spendRecovery(o, codes.getString(0)))              // once
        assertEquals(7, Rules.recoveryLeft(o))
        assertFalse(Rules.spendRecovery(o, "12345678"))
        val again = d("""{"do":"recovery_new"}"""); Rules.apply(o, again, now)
        assertFalse(Rules.spendRecovery(o, codes.getString(1)))              // the old set is void
        assertTrue(Rules.spendRecovery(o, again.getJSONArray("recovery_out").getString(0)))
    }

    @Test fun thePinMayBeOffOnlyWithTheAuthenticatorAndRecoveryCodes() {
        val o = kids()
        try { Rules.apply(o, d("""{"do":"kids","methods":{"pin":false}}"""), now); fail() } catch (e: IllegalArgumentException) { }   // no Authenticator: refused or the PIN stays
        assertTrue(Rules.methods(o).getBoolean("pin"))
        val code = Totp.code(Totp.base32(B32)!!, now / 30_000)
        Rules.apply(o, d("""{"do":"totp","secret":"$B32","code":"$code","client_now":$now}"""), now)
        Rules.apply(o, d("""{"do":"kids","methods":{"pin":false,"ask":true}}"""), now)
        assertFalse(Rules.methods(o).getBoolean("pin")); assertTrue(Rules.methods(o).getBoolean("totp")); assertTrue(Rules.methods(o).getBoolean("ask"))
        assertFalse(Rules.pinOk(o, "2580"))                                   // the screen does not take the PIN at all
        assertTrue(Rules.totpOk(o, code, now, 0))
        val codes = d("""{"do":"recovery_new"}""").also { Rules.apply(o, it, now) }.getJSONArray("recovery_out")
        assertTrue(Rules.spendRecovery(o, codes.getString(0)))
        Rules.apply(o, d("""{"do":"kids","methods":{"pin":true}}"""), now); assertTrue(Rules.pinOk(o, "2580"))
        Rules.apply(o, d("""{"do":"kids","methods":{"pin":false}}"""), now)
        Rules.apply(o, d("""{"do":"kids","methods":{"totp":false}}"""), now)       // the Authenticator off takes the PIN back on: never nothing
        assertTrue(Rules.methods(o).getBoolean("pin")); assertFalse(Rules.methods(o).getBoolean("totp")); assertTrue(Rules.pinOk(o, "2580"))
    }

    @Test fun pinOffWithoutRecoveryCodesIsRefusedWithNeedRecovery() {
        val o = kids()
        val code = Totp.code(Totp.base32(B32)!!, now / 30_000)
        Rules.apply(o, d("""{"do":"totp","secret":"$B32","code":"$code","client_now":$now}"""), now)
        o.getJSONObject("lock").remove("recovery")                               // all spent
        try { Rules.apply(o, d("""{"do":"kids","methods":{"pin":false}}"""), now); fail() } catch (e: IllegalArgumentException) { assertEquals("need_recovery", e.message) }
    }

    // ---- wrong codes: 1, 5, 15, 60 minutes
    @Test fun lockoutEscalatesAndAGoodCodeForgets() {
        var s = Lockout.S()
        var t = now
        val waits = ArrayList<Long>()
        repeat(5) {
            repeat(4) { s = Lockout.fail(s, t); assertEquals(0L, Lockout.left(s, t)) }
            s = Lockout.fail(s, t); waits.add(Lockout.left(s, t) / 60_000)
            t = s.until + 1
            assertEquals(0, s.wrong)
        }
        assertEquals(listOf(1L, 5L, 15L, 60L, 60L), waits)
        val waiting = s.copy(until = t + 5000); assertEquals(waiting, Lockout.fail(waiting, t))           // a try made during the wait counts for nothing
        assertEquals(Lockout.S(), Lockout.S())
    }

    // ---- what asks
    @Test fun kidsModeAsksForEverythingButTheAllowedAndTheFreeOnes() {
        val o = kids()
        assertTrue(Rules.needsPin(o, "com.netflix.ninja", now, free))
        assertTrue(Rules.needsPin(o, "com.android.tv.settings", now, free))             // Settings is locked
        assertFalse(Rules.needsPin(o, "com.google.android.apps.youtube.mediacorp", now, free))
        for (p in listOf("com.vendor.launcher", "com.vendor.keyboard", "com.vendor.ime", "dev.tvremote.setup", "dev.tvremote.home", "com.android.systemui",
            "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.leanbackime", "")) assertFalse(p, Rules.needsPin(o, p, now, free))
        assertFalse(Rules.needsPin(o, "com.netflix.ninja", now, Kids.assemble(emptyList(), emptyList(), null) + "com.netflix.ninja"))
    }

    @Test fun settingsStaysLockedEvenWhenItAnswersToHome() {
        val f = Kids.assemble(listOf("com.android.tv.settings", "com.vendor.launcher"), listOf("com.android.settings"), "com.android.tv.settings/.X")
        assertFalse("com.android.tv.settings" in f); assertFalse("com.android.settings" in f); assertTrue("com.vendor.launcher" in f)
    }

    @Test fun noticesNeedACodeOnlyWhenSomethingIsLocked() {
        assertFalse(Rules.hasLock(JSONObject()))
        val o = JSONObject(); Rules.apply(o, d("""{"do":"pin","pin":"2580"}"""), now); assertTrue(Rules.hasLock(o))
        val t = JSONObject(); Rules.apply(t, d("""{"do":"totp","secret":"$B32","code":"${Totp.code(Totp.base32(B32)!!, now / 30_000)}","client_now":$now}"""), now); assertTrue(Rules.hasLock(t))
    }

    @Test fun bedtimeOpensUntilTheRangeEnds() {
        val o = JSONObject(); Rules.apply(o, d("""{"do":"bedtime","on":true,"from":"22:00","to":"06:30"}"""), now)
        val cal = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 30) }
        assertEquals(7 * 60, Rules.bedLeftMin(o, cal.timeInMillis))               // 23:30 to 06:30
        cal.set(Calendar.HOUR_OF_DAY, 5); cal.set(Calendar.MINUTE, 0); assertEquals(90, Rules.bedLeftMin(o, cal.timeInMillis))
        assertEquals(1, Rules.bedLeftMin(o, now))                                   // 15:00: not in bedtime, never zero
        Rules.apply(o, d("""{"do":"unlock","minutes":420}"""), cal.timeInMillis)
        assertTrue(Rules.unlockUntil(o, cal.timeInMillis + 60_000) > 0)           // what Guard checks before it puts the TV to standby, closes an app or counts the total
        assertEquals(0L, Rules.unlockUntil(o, cal.timeInMillis + 421 * 60_000L))
    }

    @Test fun listModeAsksOnlyForTheLockedApps() {
        val o = JSONObject()
        Rules.apply(o, d("""{"do":"pin","pin":"2580"}"""), now); Rules.apply(o, d("""{"do":"lock","pkg":"com.netflix.ninja","on":true}"""), now)
        assertTrue(Rules.needsPin(o, "com.netflix.ninja", now, free)); assertFalse(Rules.needsPin(o, "com.youtube", now, free))
    }

    @Test fun kidsModeNeedsAWayInAndAKnownMode() {
        val o = JSONObject()
        try { Rules.apply(o, d("""{"do":"kids","mode":"kids"}"""), now); fail() } catch (e: IllegalArgumentException) { }
        Rules.apply(o, d("""{"do":"pin","pin":"2580"}"""), now)
        try { Rules.apply(o, d("""{"do":"kids","mode":"x"}"""), now); fail() } catch (e: IllegalArgumentException) { }
        try { Rules.apply(o, d("""{"do":"kids","allow":["bad pkg!"]}"""), now); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun unlockOpensEverythingOrOneAppUntilItsTime() {
        val o = kids()
        Rules.apply(o, d("""{"do":"unlock","minutes":30}"""), now)
        assertFalse(Rules.needsPin(o, "com.netflix.ninja", now + 29 * 60_000L, free)); assertTrue(Rules.needsPin(o, "com.netflix.ninja", now + 31 * 60_000L, free))
        Rules.apply(o, d("""{"do":"unlock","minutes":0}"""), now)
        assertTrue(Rules.needsPin(o, "com.netflix.ninja", now, free))
        Rules.apply(o, d("""{"do":"unlock","minutes":60,"pkg":"com.netflix.ninja"}"""), now)
        assertFalse(Rules.needsPin(o, "com.netflix.ninja", now + 59 * 60_000L, free)); assertTrue(Rules.needsPin(o, "com.hulu", now, free))
        assertTrue(Rules.needsPin(o, "com.netflix.ninja", now + 61 * 60_000L, free))
        Rules.apply(o, d("""{"do":"unlock","today":true,"pkg":"com.hulu"}"""), now)
        assertFalse(Rules.needsPin(o, "com.hulu", now + 8 * 3_600_000L, free)); assertTrue(Rules.needsPin(o, "com.hulu", now + 10 * 3_600_000L, free))
        Rules.apply(o, d("""{"do":"unlock","minutes":15}"""), now); Rules.apply(o, d("""{"do":"unlock","minutes":0}"""), now)
        assertTrue(Rules.needsPin(o, "com.netflix.ninja", now, free)); assertTrue(Rules.needsPin(o, "com.hulu", now, free))   // "Lock now" takes every grant back
    }

    @Test fun rulesSurviveAJsonRoundTrip() {
        val o = kids(""","methods":{"ask":true}""")
        Rules.apply(o, d("""{"do":"total","min":120,"days":[1,2,3],"warn":10}"""), now)
        val back = JSONObject(o.toString())
        assertTrue(Rules.kidsMode(back)); assertEquals(listOf("com.google.android.apps.youtube.mediacorp"), Rules.allow(back))
        assertTrue(Rules.methods(back).getBoolean("ask")); assertTrue(Rules.pinOk(back, "2580"))
        assertEquals(10, back.getJSONObject("total").getInt("warn")); assertEquals(JSONArray("[1,2,3]").toString(), back.getJSONObject("total").getJSONArray("days").toString())
        assertEquals(listOf("com.android.tv.settings").let { Rules.locked(back) }, Rules.locked(back))      // the per-app list is untouched
    }

    // ---- the day's total
    @Test fun totalMathCountsEveryAppButTheFreeOnes() {
        val o = JSONObject(); Rules.apply(o, d("""{"do":"total","min":60}"""), now)
        val used = d("""{"com.netflix.ninja":1800,"com.vendor.launcher":5000,"dev.tvremote.setup":900,"com.hulu":600}""")
        assertEquals(2400L, Rules.totalUsedSec(used, free))
        assertEquals(60 * 60L - 2400L, Rules.totalLeftSec(o, used, free, now))
        Rules.apply(o, d("""{"do":"total_extra","minutes":30}"""), now); Rules.apply(o, d("""{"do":"total_extra","minutes":15}"""), now)
        assertEquals(45, Rules.totalExtra(o, now)); assertEquals(105 * 60L - 2400L, Rules.totalLeftSec(o, used, free, now))
        Rules.apply(o, d("""{"do":"total_extra","minutes":10,"set":true}"""), now); assertEquals(10, Rules.totalExtra(o, now))
        assertEquals(0, Rules.totalExtra(o, now + 86_400_000L))                                              // only today's grant
        assertTrue(Rules.totalLeftSec(o, d("""{"com.netflix.ninja":9000}"""), free, now)!! < 0)              // used up
    }

    @Test fun totalFollowsItsDaysAndTurnsOff() {
        val o = JSONObject()                                                                                 // 2026-10-24 is a Saturday = 6
        Rules.apply(o, d("""{"do":"total","min":90,"days":[1,2,3,4,5]}"""), now)
        assertNull(Rules.totalMin(o, now)); assertEquals(90, Rules.totalMin(o, now + 2 * 86_400_000L))      // Monday
        Rules.apply(o, d("""{"do":"total","min":90}"""), now); assertEquals(90, Rules.totalMin(o, now))
        Rules.apply(o, d("""{"do":"total","min":0}"""), now); assertNull(Rules.totalMin(o, now)); assertNull(Rules.totalLeftSec(o, JSONObject(), free, now))
    }

    // ---- the TV clock
    @Test fun httpDateIsParsed() {
        assertEquals(1_761_315_330_000L, Clock.parseDate("Fri, 24 Oct 2025 14:15:30 GMT"))
        assertNull(Clock.parseDate("yesterday")); assertNull(Clock.parseDate(null))
    }

    @Test fun aJumpOfTheTvClockAfterAReadingIsCancelledOut() {
        val o = Clock.Obs(180_000L, Clock.HTTP, 1_000_000L, 5_000L)                                          // the TV was 3 min ahead
        assertEquals(180_000L, Clock.drift(o, 1_010_000L, 15_000L))                                           // 10 s later on both clocks
        assertEquals(0L, Clock.drift(o, 1_010_000L - 180_000L, 15_000L))                                      // someone set the clock back 3 min: now right
        assertEquals(180_000L, Clock.drift(o, 1, 100L))                                                       // rebooted: kept as it was
        assertEquals(0L, Clock.drift(null, 1, 1))
    }

    @Test fun betterSourcesWinAndTelegramKeepsItsLowestReading() {
        val day = 24 * 3_600_000L
        val http = Clock.Obs(5000, Clock.HTTP, 0, 0)
        assertTrue(Clock.better(null, Clock.Obs(0, Clock.CLIENT, 0, 0), 0, 0))
        assertFalse(Clock.better(http, Clock.Obs(9000, Clock.TELEGRAM, 10, 10), 10, 10))
        assertTrue(Clock.better(http, Clock.Obs(9000, Clock.TELEGRAM, 2 * day, 2 * day), 2 * day, 2 * day))      // the old network reading is stale
        assertTrue(Clock.better(Clock.Obs(9000, Clock.TELEGRAM, 0, 0), Clock.Obs(2000, Clock.TELEGRAM, 10, 10), 10, 10))
        assertFalse(Clock.better(Clock.Obs(2000, Clock.TELEGRAM, 0, 0), Clock.Obs(9000, Clock.TELEGRAM, 10, 10), 10, 10))   // latency only adds
        assertTrue(Clock.better(Clock.Obs(2000, Clock.TELEGRAM, 0, 0), Clock.Obs(0, Clock.HTTP, 10, 10), 10, 10))
        assertTrue(Clock.better(http, Clock.Obs(0, Clock.CLIENT, 10, 10), 10, 10, force = true))                 // the phone that is the Authenticator
    }

    @Test fun anOldTvSetupBlindToTheNewActionsRefusesThemLoudly() {
        try { Rules.apply(JSONObject(), d("""{"do":"nonsense"}"""), now); fail() } catch (e: IllegalArgumentException) { assertNotNull(e.message) }
    }
}
