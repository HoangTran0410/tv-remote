package dev.tvremote.setup.health

import dev.tvremote.setup.keys.KeyFix
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Support's commands from afar only read: anything that chains, writes or changes is refused before ADB sees it. */
class SupportDumpTest {
    @Test fun readsPass() {
        listOf("getprop ro.build.version.sdk", "settings get secure enabled_accessibility_services", "settings list global",
            "dumpsys package dev.tvremote.setup.keys", "dumpsys activity activities", "dumpsys battery", "dumpsys -t 30 battery", "dumpsys -l", "dumpsys package com.android.contacts.x", "pm list packages -3", "pm dump dev.tvremote.setup",
            "appops get dev.tvremote.setup SYSTEM_ALERT_WINDOW", "logcat -d -s KeyFix:I", "logcat -d -t 200", "wm size", "uptime",
            "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN",
            "cat /vendor/usr/keylayout/Vendor_0957_Product_0005.kl", "ls /system/usr/keylayout", "getevent -pl", "timeout 15 getevent -lt -c 40")
            .forEach { assertTrue(it, SupportDump.allowed(it)) }
    }

    @Test fun changesAndChainsAreRefused() {
        listOf("settings put global adb_enabled 0", "pm uninstall dev.tvremote.setup", "pm clear dev.tvremote.setup", "input keyevent 26",
            "am start -a android.intent.action.VIEW", "dumpsys battery set level 5", "dumpsys deviceidle force-idle", "dumpsys jobscheduler run x",
            "getprop; reboot", "getprop | sh", "getprop && reboot", "logcat -d > /sdcard/x", "getprop `reboot`", "getprop \$(reboot)",
            "getprop\nreboot", "rm -rf /sdcard", "cat /sdcard/DCIM/a.jpg", "logcat", "logcat -c", "wm size 100x100", "appops set x y allow",
            "dumpsys clipboard", "dumpsys -t 30 clipboard", "dumpsys -t 1 notification", "dumpsys  clipboard", "dumpsys", "dumpsys -t 30", "dumpsys -t 30 private",
            "dumpsys package x account", "dumpsys notification --noredact", "dumpsys account", "content query --uri content://sms",
            "cat /vendor/usr/keylayout/../../../data/x", "cat /data/system/users/0.xml", "getevent -l", "getevent -lt -c 40",
            "timeout 60 getevent -lt -c 40", "timeout 15 getevent -lt", "sendevent /dev/input/event4 1 2 1")
            .forEach { assertFalse(it, SupportDump.allowed(it)) }
    }
}
