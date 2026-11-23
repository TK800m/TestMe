package nl.tapo.deurbel.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket

/** Talks to a fake doorbell media port that follows the Tapo multipart protocol. */
class MediaSessionTest {
    private fun readUntil(i: InputStream, d: String): String {
        val b = ByteArrayOutputStream()
        while (!b.toString().endsWith(d)) { val c = i.read(); if (c < 0) error("eof"); b.write(c) }
        return b.toString()
    }

    @Test fun handshakeAndStream() {
        val pw = "cloudpw"
        val server = ServerSocket(0)
        val payload = ByteArray(188 * 3) { it.toByte() }
        var authOk = false
        val t = Thread {
            server.accept().use { s ->
                val i = BufferedInputStream(s.getInputStream()); val o = s.getOutputStream()
                readUntil(i, "\r\n\r\n")
                o.write(("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Digest realm=\"TP-Link IP-Camera\", qop=\"auth\", " +
                    "nonce=\"abc123\", opaque=\"op1\", encrypt_type=\"3\"\r\n\r\n").toByteArray())
                val req2 = readUntil(i, "\r\n\r\n")
                val auth = req2.lines().first { it.startsWith("Authorization") }
                val cnonce = Regex("cnonce=\"([^\"]+)\"").find(auth)!!.groupValues[1]
                val resp = Regex("response=\"([^\"]+)\"").find(auth)!!.groupValues[1]
                val ha1 = Hashes.md5Hex("admin:TP-Link IP-Camera:${Hashes.sha256HexUpper(pw)}")
                val ha2 = Hashes.md5Hex("POST:/stream")
                authOk = resp == Hashes.md5Hex("$ha1:abc123:00000001:$cnonce:auth:$ha2") && auth.contains("opaque=\"op1\"")
                o.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/mixed;boundary=--device-stream-boundary--\r\n" +
                    "Key-Exchange: cipher=\"AES_128_CBC\" username=\"admin\" padding=\"PKCS7_16\" algorithm=\"MD5\" nonce=\"NONCE9\"\r\n\r\n").toByteArray())
                val head = readUntil(i, "\r\n\r\n")
                val len = Regex("Content-Length: (\\d+)").find(head)!!.groupValues[1].toInt()
                val body = String(ByteArray(len).also { var off = 0; while (off < len) off += i.read(it, off, len - off) })
                val seq = JSONObject(body.trim()).getInt("seq")
                val json = """{"type":"response","seq":$seq,"params":{"error_code":0,"session_id":"7"}}""".toByteArray()
                o.write("----device-stream-boundary--\r\nContent-Type: application/json\r\nContent-Length: ${json.size}\r\n\r\n".toByteArray())
                o.write(json); o.write("\r\n".toByteArray())
                val key = Hashes.md5("NONCE9:${Hashes.sha256HexUpper(pw)}".toByteArray())
                val iv = Hashes.md5("admin:NONCE9".toByteArray())
                val enc = Hashes.aesCbcEncrypt(key, iv, payload)
                o.write("----device-stream-boundary--\r\nContent-Type: video/mp2t\r\nX-If-Encrypt: 1\r\nX-Session-Id: 7\r\nContent-Length: ${enc.size}\r\n\r\n".toByteArray())
                o.write(enc); o.write("\r\n".toByteArray()); o.flush()
                Thread.sleep(300)
            }
        }
        t.start()
        val m = MediaSession("127.0.0.1", pw, preferSha256 = true, port = server.localPort)
        m.start()
        val session = m.open(MediaSession.previewRequest(true))
        assertEquals(7, session)
        val part = m.next(3000)!!
        assertEquals("video/mp2t", part.mimeType)
        assertArrayEquals(payload, part.data)
        m.close(); t.join(); server.close()
        assertEquals(true, authOk)
    }
}
