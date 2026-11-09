package dev.tvremote.core
import dev.tvremote.core.remote.Vidaa
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** A Hisense VIDAA or Roku TV whose remote service is not listening (standby, off) was shown as raw English
 *  "the TV did not answer on port 36669" on a Vietnamese phone: it now gets words in the person's language. */
class NoAnswerWordsTest {
    @Test fun saidInTheLanguage() {
        val e = IOException("${Vidaa.NO_ANSWER} 36669")
        assertEquals(Words.get("vi", "errors.tv_no_answer_port"), Errors.say(e, "vi"))
        assertEquals(Words.get("en", "errors.tv_no_answer_port"), Errors.say(IOException("x", e), "en"))
    }

    /** A "QuickShare" Android box answered SSDP from :38400 and was listed as a Hisense VIDAA TV: only a reply that names
     *  VIDAA counts without a check of its control port. */
    @Test fun mediaPortAloneIsNotNamed() {
        val box = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.146:38400/description.xml\r\nSERVER: Linux UPnP/1.0 QuickShare\r\n"
        assertEquals(dev.tvremote.core.remote.Remote.Kind.VIDAA, dev.tvremote.core.remote.Ssdp.kind(box))
        assertEquals(false, dev.tvremote.core.remote.Ssdp.named(box))
        assertEquals(true, dev.tvremote.core.remote.Ssdp.named("SERVER: VIDAA/5.0 UPnP/1.0"))
    }
}
