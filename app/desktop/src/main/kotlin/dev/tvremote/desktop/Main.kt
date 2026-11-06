package dev.tvremote.desktop

import dev.tvremote.core.Words
import dev.tvremote.core.web.HttpServer
import java.io.File
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch

/**
 * The computer's server on the shared Kotlin core: the same page as the two apps, and no Python, adb or ffmpeg. ADB and the
 * remote protocol are spoken by Adb.kt and Atv.kt, the picture goes to the page as H.264 as TV Setup sends it.
 *
 *   ./gradlew :desktop:run --args="[--tv 192.168.1.88] [--port 8765] [--window]"   (inside the tv-remote folder: its tvs.json)
 *   ./gradlew :desktop:packageApp                                                  (the app with its own Java, see build.gradle.kts)
 *
 * The packaged app (system property tvr.res: the page and the files it needs, inside the app) shows the page in a window of
 * its own (Window.kt; the browser where the system has no web view, or with --browser) and keeps its TVs and keys in the
 * user's app data folder. Closing the window quits it. TVS_FILE: another list of TVs (tests); TVR_KEYS: another folder for
 * this computer's keys (tests: a key no TV knows yet).
 */
fun main(args: Array<String>) {
    val opt = args.toList().zipWithNext().filter { it.first.startsWith("--") }.associate { it.first.substring(2) to it.second }
    val port = opt["port"]?.toIntOrNull() ?: 8765
    val res = System.getProperty("tvr.res")?.let { File(it) }
    val packaged = res != null
    val repo = res ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "app/shared/web/index.html").isFile } ?: run { System.err.println("run it inside the tv-remote folder"); kotlin.system.exitProcess(2) }
    val window = (packaged || "--window" in args) && "--browser" !in args
    val data = if (packaged) App.data().also { it.mkdirs() } else repo
    if (packaged) File(data, "server.log").let { System.setOut(PrintStream(it.outputStream(), true, Charsets.UTF_8)); System.setErr(System.out) }   // nobody sees a terminal
    // A throw on a thread nobody watches (a TV's session, a scan) must not take the server down: log it (server.log when packaged) and
    // keep the last one in guard-last.txt. The page shows its own errors (js/errbox.js); the Android apps show a dialog (shared/src/guard).
    Thread.setDefaultUncaughtExceptionHandler { th, t ->
        val text = dev.tvremote.guard.GuardLogic.report(java.time.Instant.now().toString(), "desktop " + (System.getProperty("tvr.version") ?: "dev"),
            System.getProperty("os.name") + " " + System.getProperty("os.version"), System.getProperty("os.arch"), th.name, t)
        System.err.println(text)
        try { File(data, "guard-last.txt").writeText(text) } catch (_: Exception) { }
        dev.tvremote.core.Census.crash("kotlin", t.javaClass.name, t.message.orEmpty(), dev.tvremote.guard.GuardLogic.stack(t))   // with the stats switch, off this thread
    }
    val url = "http://127.0.0.1:$port"
    val passFile = File(if (packaged) data else File(repo, ".cache/desktop").also { it.mkdirs() }, if (port == 8765) "pass" else "pass-$port")   // a test server on its own port keeps its own
    if (packaged && App.answers(url, Pass.read(passFile))) {          // already running (Windows, Linux: opened twice): one more window onto it
        val link = "$url/?k=" + Pass.read(passFile)
        if (!(window && Window.show(repo, link, Pass.read(passFile)))) App.browse(link)
        return
    }
    val keys = File(System.getenv("TVR_KEYS") ?: (if (packaged) File(data, "keys") else File(repo, ".cache/keys")).path)
    Keys.prepare(keys, if (packaged) null else repo)
    dev.tvremote.core.adb.AdbPort.load(keys)                       // ports typed with an address ("ip:5877") before
    val saved = Saved(File(System.getenv("TVS_FILE") ?: File(data, "tvs.json").path))
    val first = opt["tv"]?.trim()?.also { if (!Server.IP.matches(it)) { System.err.println("--tv: not an IPv4 address: $it"); kotlin.system.exitProcess(2) } }
    val done = CountDownLatch(1)
    val pass = Pass(passFile)
    dev.tvremote.core.Downloader.dir = File(data, if (packaged) "cache" else ".cache/desktop")   // the signed Downloader codes are kept here
    val server = Server(repo, keys, pass, File(data, if (packaged) "cache" else ".cache/desktop").also { it.mkdirs() }, saved, first, port + 1) { done.countDown() }
    val http = HttpServer(server)
    val bound = try { http.start(port, true) } catch (e: java.io.IOException) {
        System.err.println("port $port is taken: is TV Remote already running? ($e)")
        if (packaged) App.alert(dev.tvremote.i18n.Langs.CODES.joinToString("\n\n") { Words.get(it, "pc.port_taken", "port" to port) })   // no page to ask: both languages
        return
    }
    pass.save()
    server.startHook()
    val link = pass.link("http://127.0.0.1:$bound")
    println("Remote: $link  (TV ${(first ?: saved.current).ifEmpty { "not chosen yet" }})")
    Runtime.getRuntime().addShutdownHook(Thread { http.stop(); server.close() })   // Ctrl+C, logging out: the TV's sessions end too
    if (packaged) Thread({ Thread.sleep(2500); Mover.offer(server.updater.bundle, server.version) { done.countDown() } }, "move").apply { isDaemon = true }.start()
    if (window) {
        Thread({
            done.await(); Window.close()                              // the page's "Tắt" closes the window too
            // a window that does not close (Windows, v0.43.0's CI: the port stayed open 60 s) must not keep the app running
            // after Tắt: say where it is stuck, then quit anyway
            Thread.sleep(5000)
            println("Window did not close in 5 s; quitting. Threads:")
            Thread.getAllStackTraces().forEach { (t, st) -> println("  ${t.name} ${t.state}"); st.take(12).forEach { println("    at $it") } }
            server.updater.quitting(); System.exit(0)
        }, "quit").apply { isDaemon = true }.start()
        if (Window.show(repo, link, pass.key)) { println("Window closed."); server.updater.quitting(); System.exit(0) }
    }
    if (packaged) App.browse(link)
    done.await()                                                      // the page's "Tắt" button
    println("Stopping.")
    server.updater.quitting()
    System.exit(0)
}

/** What only the packaged app needs: where its data goes, the browser, a message box. */
object App {
    private val os = System.getProperty("os.name").lowercase()
    private val home = System.getProperty("user.home")

    fun data(): File = when {
        "mac" in os -> File(home, "Library/Application Support/TV Remote")
        "win" in os -> File(System.getenv("APPDATA") ?: home, "TV Remote")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "tv-remote")
    }

    fun answers(url: String, pass: String) = try {
        (URL("$url/api/about").openConnection() as HttpURLConnection).run {
            setRequestProperty(Pass.HEADER, pass); connectTimeout = 1000; readTimeout = 2000; responseCode == 200 }
    } catch (e: Exception) {
        false
    }

    fun browse(url: String) {
        try {
            when {
                "mac" in os -> ProcessBuilder("open", url).start()
                "win" in os -> ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
                else -> ProcessBuilder("xdg-open", url).start()
            }
        } catch (e: Exception) {
            println("open $url in a browser ($e)")
        }
    }

    /** A message box from the system, for what the page cannot say (the server did not start). */
    fun alert(text: String) {
        val t = text.replace("\"", "'")
        val cmd = when {
            "mac" in os -> listOf("osascript", "-e", "display dialog \"$t\" buttons {\"OK\"} default button 1 with title \"TV Remote\" with icon caution")
            "win" in os -> listOf("powershell", "-NoProfile", "-Command",
                "Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show('${t.replace("'", "''")}', 'TV Remote')")
            else -> listOf("zenity", "--warning", "--title=TV Remote", "--text=$t")   // GNOME; elsewhere only server.log has it
        }
        try { ProcessBuilder(cmd).start().waitFor() } catch (e: Exception) { }
    }
}
