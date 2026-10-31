package dev.tvremote.setup.link

/** TV Setup installed on a phone by mistake (the census sees it): ask once whether they meant the Phone app. Not blocking. */
object OnPhone {
    const val APK = "https://github.com/HoangTran0410/tv-remote/releases/latest/download/tv-remote.apk"
    const val PHONE_PKG = "dev.tvremote.phone"

    /** Ask only on a device that is not a TV, and only until the person said "use it here". */
    fun shouldAsk(isTv: Boolean, stay: Boolean) = !isTv && !stay
}
