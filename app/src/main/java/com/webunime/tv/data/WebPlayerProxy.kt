package com.webunime.tv.data

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * Port ringan dari reverse-proxy web (plugins/embed-proxy.js) untuk WebView app.
 *
 * Bedanya dengan web: kita TIDAK menulis ulang URL ke path proxy. WebView tetap
 * membuka URL asli, lalu setiap request ke host player/CDN di-intercept di
 * [intercept] untuk menambah header spoofing (Referer/Origin/X-Embed) dan
 * membersihkan HTML + menyuntik shim JS. Domain iklan diblok di level jaringan.
 *
 * Ini menyamakan perilaku pemutaran Cast / TurboVIP / Hydrax dengan versi web.
 */
object WebPlayerProxy {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Host player / CDN yang kita kelola (spoof header + sanitasi HTML). */
    private val managedHost = Regex(
        "(playeriframe|turbovid|emturbo|turboviplay|turbosplayer|gn1r5n|hownetwork|" +
            "abyss|iamcdn|short\\.icu|abysscdn|morphify|tiktokcdn|sptvp|" +
            "googleusercontent|storage\\.googleapis\\.com|img-place)",
        RegexOption.IGNORE_CASE
    )

    /** Domain iklan / pop-under yang diblok total. */
    private val adHost = Regex(
        "(doubleclick|exoclick|exosrv|propeller|propellerads|adsterra|popads|popcash|" +
            "clickadu|onclckds|hilltopads|adnxs|adservice\\.google|taboola|outbrain|mgid|" +
            "revcontent|bidgear|adskeeper|juicyads|trafficjunky|clickaine|onclickperformance|" +
            "adsco\\.re|clickunder|decafeligiblyhad|uyeouyeo|histats|adtng|a-ads|" +
            "admaven|mgcash|monetag|hyperss|tsyndicate)",
        RegexOption.IGNORE_CASE
    )

    fun isAdRequest(url: String): Boolean = adHost.containsMatchIn(url)
    fun isManaged(url: String): Boolean = managedHost.containsMatchIn(url)

    /**
     * Host Hydrax/abyss punya proteksi anti-direct-access (cek window.top===self)
     * dan anti-tamper. HTML-nya tidak boleh disanitasi; harus dimuat di dalam iframe.
     */
    fun isAbyss(url: String): Boolean {
        val h = hostOf(url)
        return h.contains("abyss") || h.contains("iamcdn") || h.contains("short.icu")
    }

    /**
     * Wrapper HTML agar Hydrax berjalan di dalam iframe (bukan dokumen top).
     * Script bridge: iframe lintas-origin tidak bisa memanggil @JavascriptInterface
     * dari frame anak (targetSdk>=17), jadi kualitas dikirim via postMessage ke
     * parent → WebunimePlayback.
     */
    fun abyssWrapperHtml(embedUrl: String): String = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
        iframe{border:0;outline:0;width:100vw;height:100vh;display:block}</style></head>
        <body>
        <iframe id="wuEmbed" src="$embedUrl" allow="autoplay; fullscreen; encrypted-media"
        allowfullscreen scrolling="no"></iframe>
        <script>
        (function(){
          window.addEventListener("message", function(e){
            var d = e && e.data;
            if (!d || typeof d !== "object") return;
            if (d.type === "__wuQualities") {
              try { WebunimePlayback.onQualities(JSON.stringify(d)); } catch (ex) {}
            }
          });
          window.__wuRequestQualities = function(){
            try {
              var f = document.getElementById("wuEmbed");
              if (f && f.contentWindow) f.contentWindow.postMessage("__wuGetQualities", "*");
            } catch (ex) {}
          };
          window.__wuSetQuality = function(idx){
            try {
              var f = document.getElementById("wuEmbed");
              if (f && f.contentWindow) f.contentWindow.postMessage({type:"__wuSetQuality",index:idx}, "*");
            } catch (ex) {}
          };
        })();
        </script>
        </body></html>
    """.trimIndent()

    /** Base URL wrapper: seolah embed berasal dari playeriframe.sbs. */
    const val ABYSS_WRAPPER_BASE = "https://playeriframe.sbs/"

    /** Response kosong untuk memblok iklan/popup di level jaringan. */
    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()

    private fun pickReferer(host: String): String = when {
        host.contains("playeriframe") -> "https://tv12.lk21official.cc/"
        host.contains("gn1r5n") -> "https://gn1r5n.org/"
        host.contains("hownetwork") -> "https://playeriframe.sbs/"
        host.contains("abyss") || host.contains("iamcdn") || host.contains("short.icu") ->
            "https://abyssplayer.com/"
        host.contains("turbovid") || host.contains("emturbo") ||
            host.contains("turboviplay") || host.contains("turbosplayer") ||
            host.contains("tiktokcdn") || host.contains("sptvp") ||
            host.contains("googleusercontent") -> "https://turbovidhls.com/"
        else -> "https://playeriframe.sbs/"
    }

    /** Header upstream ala buildUpstreamHeaders() web. */
    private fun buildHeaders(url: String, request: WebResourceRequest?): Map<String, String> {
        val host = hostOf(url)
        val headers = linkedMapOf(
            "User-Agent" to UA,
            "Accept" to (request?.requestHeaders?.get("Accept") ?: "*/*"),
            "Referer" to pickReferer(host),
            "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
        )

        request?.requestHeaders?.get("Range")?.let { headers["Range"] = it }

        // Cast (gn1r5n): validasi embed lewat X-Embed-* (bukan Origin browser)
        if (host.contains("gn1r5n")) {
            headers["Origin"] = "https://gn1r5n.org"
            headers["Referer"] = "https://playeriframe.sbs/"
            headers["X-Embed-Origin"] = "playeriframe.sbs"
            headers["X-Embed-Referer"] = "https://playeriframe.sbs/"
            headers["X-Embed-Parent"] = "https://playeriframe.sbs/"
        }
        if (host.contains("abyss") || host.contains("iamcdn") || host.contains("short.icu")) {
            headers["Origin"] = "https://abyssplayer.com"
            headers["Referer"] = "https://abyssplayer.com/"
        }
        // GCS Hydrax: pakai Referer abyssplayer, bukan asing
        if (host.contains("storage.googleapis.com")) {
            headers["Referer"] = "https://abyssplayer.com/"
            headers["Origin"] = "https://abyssplayer.com"
        }

        CookieManager.getInstance().getCookie(url)?.let { if (it.isNotBlank()) headers["Cookie"] = it }
        return headers
    }

    /**
     * Intercept request WebView. Kembalikan null agar WebView menangani sendiri.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!url.startsWith("http")) return null
        if (isAdRequest(url)) return blockedResponse()
        if (!isManaged(url)) return null
        // POST tak punya body di WebResourceRequest → biarkan WebView menangani
        if (!request.method.equals("GET", ignoreCase = true)) return null

        return runCatching {
            val reqBuilder = Request.Builder().url(url)
            buildHeaders(url, request).forEach { (k, v) -> reqBuilder.header(k, v) }
            val response = client.newCall(reqBuilder.build()).execute()

            val contentType = response.header("Content-Type") ?: "application/octet-stream"
            val isHtml = contentType.contains("text/html", ignoreCase = true)

            val respHeaders = linkedMapOf<String, String>("Access-Control-Allow-Origin" to "*")
            response.header("Content-Range")?.let { respHeaders["Content-Range"] = it }
            response.header("Accept-Ranges")?.let { respHeaders["Accept-Ranges"] = it }

            if (isHtml) {
                val html = response.body?.string().orEmpty()
                response.close()
                // Sanitasi penuh untuk semua host (termasuk Hydrax/abyss), persis
                // seperti versi web: spoof fuckAdBlock, hapus pesan "AdBlock/Sandbox",
                // ganti handler overlay → jwplayer().play(), kosongkan popups.
                // Anti-embed "What are you doing here?" sudah aman karena abyss
                // dimuat di dalam iframe (top !== self).
                val outHtml = sanitizeHtml(html, url)
                WebResourceResponse(
                    "text/html",
                    "utf-8",
                    200,
                    "OK",
                    respHeaders,
                    ByteArrayInputStream(outHtml.toByteArray(Charsets.UTF_8))
                )
            } else {
                val (mime, charset) = splitContentType(contentType)
                val code = if (response.code in 100..599) response.code else 200
                val stream = response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                WebResourceResponse(
                    mime,
                    charset,
                    code,
                    response.message.ifBlank { "OK" },
                    respHeaders,
                    stream
                )
            }
        }.getOrNull()
    }

    private fun splitContentType(ct: String): Pair<String, String?> {
        val parts = ct.split(";")
        val mime = parts.firstOrNull()?.trim()?.ifBlank { "application/octet-stream" }
            ?: "application/octet-stream"
        val charset = parts.drop(1)
            .map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter("=")?.trim()
        return mime to charset
    }

    // ---- Sanitasi HTML (port dari sanitizeHtml() web, tanpa URL rewriting) ----

    private fun sanitizeHtml(htmlIn: String, url: String): String {
        var out = htmlIn

        out = out.replace(
            Regex("""<meta[^>]+http-equiv=["']?Content-Security-Policy["']?[^>]*>""", RegexOption.IGNORE_CASE),
            ""
        )
        out = out.replace(
            Regex("""if\s*\(\s*window\.self\s*===\s*window\.top\s*\)\s*\{[\s\S]*?\}""", RegexOption.IGNORE_CASE),
            "/* top-check disabled */"
        )
        out = out.replace(Regex("""top\.location\s*==\s*self\.location"""), "false")
        out = out.replace(Regex("""self\.location\s*==\s*top\.location"""), "false")
        out = out.replace(
            Regex("""function\s+devtoolIsOpening\s*\(\s*\)\s*\{[\s\S]*?\}\s*devtoolIsOpening\s*\(\s*\)\s*;?""", RegexOption.IGNORE_CASE),
            "/* anti-devtools disabled */"
        )
        // Cast SPA: path harus mengandung /e/
        out = out.replace(
            Regex("""path\.indexOf\(\s*['"]/e/['"]\s*\)\s*!==\s*0"""),
            "path.indexOf('/e/') < 0"
        )
        // Overlay iklan klik-untuk-mulai
        out = out.replace(
            Regex("""<a\b[^>]*\bid=["']uyeouyeo["'][^>]*>[\s\S]*?</a>""", RegexOption.IGNORE_CASE),
            ""
        )
        // Hydrax: matikan deteksi extension + ganti handler overlay → langsung play
        out = out.replace(
            Regex("""const\s+isUseExtension\s*=\s*[^;]+;"""),
            "const isUseExtension = false;"
        )
        out = replaceHydraxOverlayHandler(
            out,
            "try{if(overlay){overlay.onclick=null;overlay.ontouchend=null;overlay.remove();}}catch(e){}" +
                "try{if(typeof jwplayer!=\"undefined\"&&typeof jwplayer().play==\"function\")jwplayer().play();}catch(e){}"
        )
        out = out.replace(Regex("""jwplayer\s*\(\s*\)\s*\.\s*remove\s*\(\s*\)""", RegexOption.IGNORE_CASE), "void 0")
        out = out.replace(Regex("""track\.window\s*>=\s*2"""), "false")
        out = out.replace(Regex("""track\.window\s*>\s*1"""), "false")
        out = out.replace(
            Regex("""window\.abyssConfig\s*=\s*\{popups:\s*\[[^\]]*\]\}"""),
            "window.abyssConfig={popups:[]}"
        )
        out = out.replace(Regex("""urls\s*=\s*\[[^\]]*decafeligiblyhad[^\]]*\]""", RegexOption.IGNORE_CASE), "urls=[]")
        out = out.replace(
            Regex("""Due to certain reasons\s*\(AdBlock/Sandbox\)[\s\S]{0,280}?try again\.""", RegexOption.IGNORE_CASE),
            ""
        )
        // Beacon Cloudflare
        out = out.replace(Regex("""<script[^>]*cloudflareinsights[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
        out = out.replace(Regex("""/cdn-cgi/rum[^"'\s]*""", RegexOption.IGNORE_CASE), "#")
        out = out.replace(
            Regex("""<script[^>]*/cdn-cgi/challenge-platform[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE),
            ""
        )

        val shim = clientShim(url)
        val headMatch = Regex("""<head[^>]*>""", RegexOption.IGNORE_CASE).find(out)
        out = if (headMatch != null) {
            val insertAt = headMatch.range.last + 1
            out.substring(0, insertAt) + "\n" + shim + out.substring(insertAt)
        } else {
            "$shim\n$out"
        }
        return out
    }

    /** Ganti arrow fn `const name = () => { ... }` dengan brace-matching. */
    private fun replaceConstArrowFn(html: String, name: String, body: String): String {
        val re = Regex("""const\s+$name\s*=\s*\(\s*\)\s*=>\s*\{""")
        val m = re.find(html) ?: return html
        var i = m.range.last + 1
        var depth = 1
        while (i < html.length && depth > 0) {
            when (html[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        var end = i
        if (end < html.length && html[end] == ';') end++
        return html.substring(0, m.range.first) + "const $name = () => {$body};" + html.substring(end)
    }

    /** Hydrax mengacak nama handler overlay (sbM / vSRe / …). */
    private fun replaceHydraxOverlayHandler(html: String, body: String): String {
        val re = Regex("""const\s+(\w+)\s*=\s*\(\s*\)\s*=>\s*\{\s*const\s+url\s*=\s*urls\.shift\(\)""")
        val m = re.find(html) ?: return replaceConstArrowFn(html, "sbM", body)
        return replaceConstArrowFn(html, m.groupValues[1], body)
    }

    /**
     * Shim klien: spoof referrer (Cast/Turbo), blokir popup, paksa play
     * (Hydrax/Cast/Turbo). Tanpa URL rewriting (request di-handle di intercept).
     */
    private fun clientShim(url: String): String {
        val host = hostOf(url)
        val isAbyss = host.contains("abyss") || host.contains("iamcdn") || host.contains("short.icu")
        val isCast = host.contains("gn1r5n")
        val isTurbo = host.contains("turbo")

        return """
<script data-webunime-shim>
(function(){
  var IS_ABYSS=$isAbyss, IS_CAST=$isCast, IS_TURBO=$isTurbo;

  // ---- Kontrol play/pause eksplisit untuk remote TV (tombol OK) ----
  // Membaca status asli player lalu pause()/play(). Flag __wuUserPaused
  // mencegah loop "paksa play" di bawah melanjutkan pemutaran setelah dijeda.
  window.__wuUserPaused=false;
  function __wuVideo(){ try{return document.querySelector("video");}catch(e){return null;} }
  function __wuJw(){ try{ if(typeof jwplayer==="function"){ var p=jwplayer(); if(p&&typeof p.getState==="function") return p; } }catch(e){} return null; }
  function __wuIsPlaying(){ var v=__wuVideo(); if(v) return !v.paused && !v.ended; var jp=__wuJw(); if(jp){ var s=jp.getState(); return s==="playing"||s==="buffering"; } return false; }
  window.__wuPlay=function(){ window.__wuUserPaused=false; try{var jp=__wuJw(); if(jp) jp.play();}catch(e){} try{var v=__wuVideo(); if(v){ v.muted=false; v.play(); }}catch(e){} try{WebunimePlayback.onPlay();}catch(e){} try{ if(typeof window.__wuHidePlayerUi==="function") setTimeout(window.__wuHidePlayerUi, 1200); }catch(e){} };
  window.__wuPause=function(){ window.__wuUserPaused=true; try{var jp=__wuJw(); if(jp) jp.pause();}catch(e){} try{var v=__wuVideo(); if(v) v.pause();}catch(e){} try{ if(typeof window.__wuShowPlayerUi==="function") window.__wuShowPlayerUi(); }catch(e){} try{WebunimePlayback.onPause();}catch(e){} };
  window.__wuToggle=function(){ if(__wuIsPlaying()) window.__wuPause(); else window.__wuPlay(); };
  try{ window.addEventListener("message", function(e){ var d=e&&e.data; if(d==="__wuToggle") window.__wuToggle(); else if(d==="__wuPlay") window.__wuPlay(); else if(d==="__wuPause") window.__wuPause(); else if(d==="__wuGetQualities"){ try{window.__wuReportQualities();}catch(ex){} } else if(d==="__wuShowUi"){ try{ if(typeof window.__wuShowPlayerUi==="function") window.__wuShowPlayerUi(); }catch(ex){} } else if(d&&typeof d==="object"&&d.type==="__wuSetQuality"){ try{window.__wuSetQuality(d.index);}catch(ex){} } }); }catch(e){}

  // ---- Kualitas / resolusi (JWPlayer) untuk remote TV ----
  function __wuJwAny(){
    try{ if(typeof jwplayer==="function"){ var p=jwplayer("video_player"); if(p&&typeof p.getQualityLevels==="function") return p; } }catch(e){}
    return __wuJw();
  }
  window.__wuCollectQualities=function(){
    var jp=__wuJwAny();
    if(!jp||typeof jp.getQualityLevels!=="function") return {levels:[],current:-1};
    var levels=jp.getQualityLevels()||[];
    var cur=typeof jp.getCurrentQuality==="function"?jp.getCurrentQuality():-1;
    var out=[];
    for(var i=0;i<levels.length;i++){
      var l=levels[i]||{};
      var label=l.label||(l.height?String(l.height)+"p":(l.width?String(l.width)+"w":("Quality "+(i+1))));
      out.push({i:i,label:String(label),active:i===cur});
    }
    return {levels:out,current:cur};
  };
  window.__wuReportQualities=function(){
    var data=window.__wuCollectQualities();
    data.type="__wuQualities";
    // Frame anak (Hydrax iframe): HANYA postMessage ke parent.
    // Jangan panggil WebunimePlayback di sini — parent bridge yang memanggil,
    // supaya dialog kualitas tidak muncul 2x.
    try{
      if(window.parent && window.parent!==window){
        window.parent.postMessage(data,"*");
        return;
      }
    }catch(e){}
    try{ WebunimePlayback.onQualities(JSON.stringify(data)); }catch(e){}
  };
  window.__wuSetQuality=function(idx){
    try{
      var jp=__wuJwAny();
      if(jp&&typeof jp.setCurrentQuality==="function") jp.setCurrentQuality(Number(idx));
    }catch(e){}
  };
  // Deteksi <video> → beri tahu app (agar tombol OK beralih ke mode toggle),
  // dan sinkronkan bar judul (hilang saat play, muncul saat pause).
  // Selain event, status paused juga di-POLL agar terlaporkan walau video sudah
  // terlanjur diputar sebelum listener terpasang (kasus Cast auto-resume).
  (function(){ var n=0; var last=null; var sv=setInterval(function(){ n++; var v=__wuVideo();
    if(v && !v.__wuTB){ v.__wuTB=true; try{ v.addEventListener("play",function(){try{WebunimePlayback.onPlay();}catch(e){}}); v.addEventListener("playing",function(){try{WebunimePlayback.onPlay();}catch(e){}}); v.addEventListener("pause",function(){try{WebunimePlayback.onPause();}catch(e){}}); }catch(e){} }
    if(v){ var p=v.paused; if(last!==p){ last=p; try{ if(p) WebunimePlayback.onPause(); else WebunimePlayback.onPlay(); }catch(e){} } }
    if(n>240) clearInterval(sv); }, 500); })();
  // TV: sembunyikan tombol play besar / poster JWPlayer yang menutupi layar
  // HANYA saat video benar-benar sedang diputar. Saat pause dibiarkan tampil
  // sebagai indikator. Kontrol play/pause tetap lewat tombol OK.
  (function(){ var c=0; var hv=setInterval(function(){ c++; try{ var v=__wuVideo(); if(v && !v.paused && v.readyState>=2){ var sel=[".jw-display",".jw-display-icon-container",".jw-display-icon-display",".jw-preview","#overlay",".vjs-big-play-button"]; for(var i=0;i<sel.length;i++){ var els=document.querySelectorAll(sel[i]); for(var j=0;j<els.length;j++){ try{els[j].style.setProperty("display","none","important");}catch(e){} } } } }catch(e){} if(c>240) clearInterval(hv); }, 400); })();

  if (IS_CAST || IS_TURBO) {
    try { Object.defineProperty(Document.prototype,"referrer",{configurable:true,get:function(){return "https://playeriframe.sbs/";}}); } catch(e){}
    try { Object.defineProperty(location,"ancestorOrigins",{configurable:true,get:function(){return {length:1,0:"https://playeriframe.sbs/",item:function(){return "https://playeriframe.sbs/";}};}}); } catch(e){}
  }

  if (IS_CAST) {
    var ofetch = window.fetch ? window.fetch.bind(window) : null;
    if (ofetch) {
      window.fetch = function(input, init){
        try { init = init ? Object.assign({}, init) : {}; var h = new Headers(init.headers||{}); h.set("X-Embed-Origin","playeriframe.sbs"); h.set("X-Embed-Referer","https://playeriframe.sbs/"); h.set("X-Embed-Parent","https://playeriframe.sbs/"); init.headers = h; } catch(e){}
        return ofetch(input, init);
      };
    }
    var oset = XMLHttpRequest.prototype.setRequestHeader;
    var oopen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(){ this.__wuCast = true; return oopen.apply(this, arguments); };
    XMLHttpRequest.prototype.setRequestHeader = function(k,v){ try { if(this.__wuCast && /^X-Embed-/i.test(String(k))){ if(/Origin/i.test(k)) v="playeriframe.sbs"; if(/Referer/i.test(k)) v="https://playeriframe.sbs/"; if(/Parent/i.test(k)) v="https://playeriframe.sbs/"; } } catch(e){} return oset.call(this,k,v); };
    try { Object.defineProperty(window,"frameElement",{configurable:true,get:function(){return null;}}); } catch(e){}
  }

  function stripOuterAd(){ try { var a=document.getElementById("uyeouyeo"); if(a) a.remove(); } catch(e){} }
  document.addEventListener("DOMContentLoaded", stripOuterAd);
  setTimeout(stripOuterAd, 500);

  (function blockPopups(){
    var fakeWin={closed:false,close:function(){this.closed=true;},focus:function(){},blur:function(){},opener:null,location:{href:"about:blank",replace:function(){},assign:function(){}},document:{write:function(){},close:function(){}},postMessage:function(){}};
    function fakeOpen(){ try{fakeWin.closed=false;}catch(e){} setTimeout(function(){try{fakeWin.closed=true;}catch(e){}},1200); return fakeWin; }
    try { window.open = fakeOpen; } catch(e){}
    try { Object.defineProperty(window,"open",{configurable:true,writable:true,value:fakeOpen}); } catch(e){}
    function isBlankNav(a){ if(!a) return false; var tgt=(a.getAttribute("target")||"").toLowerCase(); if(tgt==="_blank"||tgt==="_new") return true; var mark=(a.id||"")+" "+(a.className||"")+" "+(a.getAttribute("href")||""); return /uyeouyeo|popup|clickunder|decafeligiblyhad|doubleclick|exoclick|propeller|adsterra/i.test(mark); }
    document.addEventListener("click", function(ev){ var t=ev.target; if(!t) return; var a=t.closest?t.closest("a"):null; if(!isBlankNav(a)) return; ev.preventDefault(); ev.stopPropagation(); if(ev.stopImmediatePropagation) ev.stopImmediatePropagation(); try{fakeOpen();}catch(e){} }, true);
    try { var oClick=HTMLAnchorElement.prototype.click; HTMLAnchorElement.prototype.click=function(){ if(isBlankNav(this)){ try{fakeOpen();}catch(e){} return; } return oClick.apply(this, arguments); }; } catch(e){}
  })();

  if (IS_ABYSS) {
    try {
      Object.defineProperty(window,"fuckAdBlock",{configurable:true,get:function(){return {onDetected:function(){},onNotDetected:function(cb){try{cb&&cb();}catch(e){}}};},set:function(){}});
      Object.defineProperty(window,"FuckAdBlock",{configurable:true,get:function(){return function(){};},set:function(){}});
    } catch(e){}
    (function guardJwRemove(){ var tries=0; var iv=setInterval(function(){ tries++; try { if(typeof window.jwplayer==="function" && !window.jwplayer.__wuGuard){ var orig=window.jwplayer; function wrap(){ var p=orig.apply(this, arguments); try{ if(p&&typeof p.remove==="function") p.remove=function(){return p;}; }catch(e){} return p; } wrap.__wuGuard=true; try{ Object.keys(orig).forEach(function(k){ try{ wrap[k]=orig[k]; }catch(e){} }); }catch(e){} window.jwplayer=wrap; clearInterval(iv); } } catch(e){} if(tries>40) clearInterval(iv); }, 100); })();
    var tries=0; var iv=setInterval(function(){ tries++; try { if(window.abyssConfig) window.abyssConfig.popups=[]; var overlay=document.getElementById("overlay"); if(overlay && tries===6 && !window.__wuUserPaused){ try{overlay.click();}catch(e){} } if(!overlay && typeof window.jwplayer==="function"){ if(!window.__wuUserPaused){ try{window.jwplayer().play();}catch(e){} } clearInterval(iv); } } catch(e){} if(tries>40) clearInterval(iv); }, 250);
  }

  if (IS_CAST) {
    var castArmed=false;
    function castTryPlay(){ try { var btn=document.querySelector("button, .vjs-big-play-button, .jw-icon-display, [class*=play], [aria-label*=Play], [aria-label*=play]"); var vid=document.querySelector("video"); try{if(btn) btn.click();}catch(e){} try{ if(vid){ vid.muted=false; vid.volume=1; vid.play(); } }catch(e){} try{ if(typeof jwplayer==="function") jwplayer().play(); }catch(e){} } catch(e){} }
    document.addEventListener("pointerdown", function(){ if(castArmed) return; castArmed=true; castTryPlay(); setTimeout(castTryPlay, 120); }, true);
    // Klik OK dari remote (D-pad) menghasilkan event "click" tepercaya pada
    // tombol verifikasi Cast → lewati gerbang "verify you're a human" & play.
    document.addEventListener("click", function(){ castArmed=true; castTryPlay(); setTimeout(castTryPlay, 200); setTimeout(castTryPlay, 600); }, true);
    // Auto-klik dialog "Resume watching?" (muncul PASCA-verifikasi, bukan anti-bot)
    // karena tombolnya bukan elemen fokus D-pad standar.
    function castDismissResume(){ try{ var bs=document.querySelectorAll("button,[role=button]"); var resume=null, other=null; for(var i=0;i<bs.length;i++){ var t=(bs[i].textContent||"").trim().toLowerCase(); if(t==="resume"){ resume=bs[i]; } else if(t==="start over"){ other=bs[i]; } } var b=resume||other; if(b){ b.click(); return true; } }catch(e){} return false; }
    var ct=0; var civ=setInterval(function(){ ct++; try { var vid=document.querySelector("video"); if(vid && !vid.paused && vid.readyState>=2){ clearInterval(civ); return; } if(!window.__wuUserPaused){ castDismissResume(); if(ct===3||ct===8||ct===14||ct===22) castTryPlay(); } } catch(e){} if(ct>45) clearInterval(civ); }, 400);
  }

  if (IS_TURBO) {
    // Hapus frame/outline kuning + pastikan full-bleed hitam
    (function injectTurboCss(){
      try{
        var s=document.createElement("style");
        s.setAttribute("data-webunime-turbo-css","1");
        s.textContent=[
          "html,body{margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important;}",
          "html,body,*,*:before,*:after{outline:none!important;outline-color:transparent!important;}",
          "#video_player,.jwplayer,.jw-wrapper,.jw-aspect,.jw-media,.jw-preview,video,",
          ".player,.vjs-tech,#player,.container,#container{border:0!important;box-shadow:none!important;",
          "outline:0!important;background:#000!important;}",
          ".jwplayer.jw-flag-focus,.jw-flag-focus,.jwplayer:focus,*:focus{",
          "outline:0!important;border-color:transparent!important;box-shadow:none!important;}",
          /* frame kuning sering muncul sebagai border kuning / outline kuning */
          "[style*='yellow'],[style*='#ff0'],[style*='#FF0'],[style*='rgb(255, 255, 0)']{",
          "border:0!important;outline:0!important;box-shadow:none!important;}"
        ].join("");
        (document.head||document.documentElement).appendChild(s);
      }catch(e){}
    })();
    // Auto-hide chrome JWPlayer saat playing; muncul lagi saat gesture singkat
    window.__wuShowPlayerUi=function(){
      try{ var jp=__wuJwAny(); if(jp&&typeof jp.setControls==="function") jp.setControls(true); }catch(e){}
      try{ document.querySelectorAll(".jw-controls,.jw-controlbar").forEach(function(el){ el.style.removeProperty("display"); el.style.removeProperty("opacity"); }); }catch(e){}
      clearTimeout(window.__wuHideUiT);
      window.__wuHideUiT=setTimeout(function(){ try{window.__wuHidePlayerUi();}catch(e){} }, 3500);
    };
    window.__wuHidePlayerUi=function(){
      if(window.__wuUserPaused) return;
      try{ var jp=__wuJwAny(); if(jp&&typeof jp.setControls==="function") jp.setControls(false); }catch(e){}
      try{ document.querySelectorAll(".jw-controls,.jw-controlbar,.jw-display").forEach(function(el){ el.style.setProperty("display","none","important"); }); }catch(e){}
    };
    var tt=0; var tiv=setInterval(function(){ tt++; try {
      if(typeof enablePlay!=="undefined") enablePlay="yes";
      if(typeof checkDomain!=="undefined") checkDomain=true;
      if(typeof iframePlay!=="undefined") iframePlay=false;
      var pre=document.querySelector(".preloader"); var ready=false;
      try { if(typeof jwplayer==="function"){ var jp=jwplayer("video_player"); if(jp&&typeof jp.getState==="function"){ var st=jp.getState(); if(st&&st!=="idle") ready=true; } } } catch(e){}
      if(!ready && !window.__wuUserPaused && typeof loadPlayer==="function" && typeof urlPlay==="string" && urlPlay){ try{loadPlayer(urlPlay);}catch(e){} if(pre){ try{pre.style.display="none";}catch(e){} } }
      if(ready || (document.querySelector("video") && document.querySelector("video").readyState>=2)){ if(pre) pre.style.display="none"; if(!window.__wuUserPaused){ try{ if(typeof jwplayer==="function") jwplayer("video_player").play(); }catch(e){} setTimeout(function(){try{window.__wuHidePlayerUi();}catch(e){}}, 2000); } clearInterval(tiv); return; }
      if(typeof play==="function" && tt>6 && !window.__wuUserPaused){ try{play();}catch(e){} }
    } catch(e){} if(tt>40) clearInterval(tiv); }, 500);
  }

  // Auto-hide kontrol JWPlayer juga untuk Hydrax saat playing
  if (IS_ABYSS) {
    window.__wuHidePlayerUi=function(){
      if(window.__wuUserPaused) return;
      try{ var jp=__wuJwAny(); if(jp&&typeof jp.setControls==="function") jp.setControls(false); }catch(e){}
      try{ document.querySelectorAll(".jw-controls,.jw-controlbar,.jw-display").forEach(function(el){ el.style.setProperty("display","none","important"); }); }catch(e){}
    };
    window.__wuShowPlayerUi=function(){
      try{ var jp=__wuJwAny(); if(jp&&typeof jp.setControls==="function") jp.setControls(true); }catch(e){}
      clearTimeout(window.__wuHideUiT);
      window.__wuHideUiT=setTimeout(function(){ try{window.__wuHidePlayerUi();}catch(e){} }, 3500);
    };
  }
})();
</script>
""".trimIndent()
    }
}
