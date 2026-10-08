package com.jstutor.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------------

data class Lesson(
    val id: String,
    val kind: String,
    val level: String,
    val title: String,
    val text: String,
    val example: String,
    val task: String,
    val starter: String,
    val check: String,
    val hint: String,
    val solution: String,
    val steps: List<String>
)

private fun buildLesson(h: Map<String, String>, s: Map<String, StringBuilder>): Lesson {
    fun sec(k: String): String = (s[k]?.toString() ?: "").trim('\n', '\r').trimEnd()
    val example = sec("example")
    val starter = sec("starter")
    return Lesson(
        id = h["id"] ?: "",
        kind = h["kind"] ?: "lesson",
        level = h["level"] ?: "",
        title = h["title"] ?: "",
        text = sec("text"),
        example = example,
        task = sec("task"),
        starter = if (starter.isEmpty()) example else starter,
        check = sec("check").replace("\n", " ").trim(),
        hint = sec("hint"),
        solution = sec("solution"),
        steps = sec("steps").lines().map { it.trim() }.filter { it.isNotEmpty() }
    )
}

fun parseContent(raw: String): List<Lesson> {
    val out = ArrayList<Lesson>()
    var head = HashMap<String, String>()
    var sect = HashMap<String, StringBuilder>()
    var cur: StringBuilder? = null
    var started = false
    for (line in raw.lines()) {
        if (line.startsWith("=== ")) {
            if (started) out.add(buildLesson(head, sect))
            started = true
            head = HashMap()
            sect = HashMap()
            cur = null
            head["kind"] = line.substring(4).trim().lowercase()
        } else if (line.startsWith("--- ")) {
            val sb = StringBuilder()
            sect[line.substring(4).trim()] = sb
            cur = sb
        } else {
            val c = cur
            if (c != null) {
                c.append(line).append('\n')
            } else {
                val i = line.indexOf(':')
                if (i > 0) head[line.substring(0, i).trim()] = line.substring(i + 1).trim()
            }
        }
    }
    if (started) out.add(buildLesson(head, sect))
    return out
}

// ---------------------------------------------------------------------------
// App data and saved progress (stored only on the device)
// ---------------------------------------------------------------------------

object AppData {
    var lessons: List<Lesson> = emptyList()
    var projects: List<Lesson> = emptyList()
    var done by mutableStateOf<Set<String>>(emptySet())
    var theme by mutableStateOf(0) // 0 auto, 1 light, 2 dark
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("jstutor", Context.MODE_PRIVATE)
        prefs = p
        val all = try {
            parseContent(ctx.assets.open("lessons.txt").bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            emptyList()
        }
        lessons = all.filter { it.kind == "lesson" }
        projects = all.filter { it.kind == "project" }
        done = HashSet(p.getStringSet("done", null) ?: emptySet<String>())
        theme = p.getInt("theme", 0)
    }

    fun complete(id: String) {
        if (id in done) return
        val next = HashSet(done)
        next.add(id)
        done = next
        prefs?.edit()?.putStringSet("done", HashSet(next))?.apply()
    }

    fun cycleTheme() {
        theme = (theme + 1) % 3
        prefs?.edit()?.putInt("theme", theme)?.apply()
    }

    fun loadCode(key: String, fallback: String): String =
        prefs?.getString("code_$key", null) ?: fallback

    fun saveCode(key: String, code: String) {
        prefs?.edit()?.putString("code_$key", code)?.apply()
    }

    fun current(): Lesson? = lessons.firstOrNull { it.id !in done } ?: lessons.lastOrNull()

    fun percent(): Int =
        if (lessons.isEmpty()) 0 else lessons.count { it.id in done } * 100 / lessons.size

    fun unlocked(l: Lesson): Boolean {
        val i = lessons.indexOf(l)
        return i <= 0 || l.id in done || lessons[i - 1].id in done
    }

    fun levels(): List<String> = lessons.map { it.level }.distinct()
}

// ---------------------------------------------------------------------------
// JavaScript sandbox: a hidden WebView with no network, no file access,
// no storage and a single text-only bridge back to the app.
// ---------------------------------------------------------------------------

class Bridge {
    @JavascriptInterface
    fun emit(id: String, type: String, text: String) {
        JsEngine.dispatch(id, type, text)
    }
}

@SuppressLint("StaticFieldLeak")
object JsEngine {
    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var web: WebView? = null
    private var ready = false
    private var runId = 0
    private var listener: ((String, String) -> Unit)? = null
    private var pending: (() -> Unit)? = null
    private var timeout: Runnable? = null

    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        create()
    }

    fun dispatch(id: String, type: String, text: String) {
        main.post {
            if (id == runId.toString()) listener?.invoke(type, text)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create() {
        val ctx = appCtx ?: return
        ready = false
        val w = WebView(ctx)
        w.settings.javaScriptEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.blockNetworkLoads = true
        w.settings.domStorageEnabled = false
        w.settings.setGeolocationEnabled(false)
        w.addJavascriptInterface(Bridge(), "AndroidBridge")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view !== web) return
                ready = true
                val p = pending
                pending = null
                p?.invoke()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                if (view === web) restart()
                return true
            }
        }
        web = w
        w.loadDataWithBaseURL("about:blank", HARNESS, "text/html", "utf-8", null)
    }

    private fun restart() {
        val old = web
        web = null
        ready = false
        try {
            old?.destroy()
        } catch (e: Exception) {
        }
        create()
    }

    private fun kill() {
        val w = web
        var terminated = false
        if (w != null && Build.VERSION.SDK_INT >= 29) {
            try {
                terminated = w.webViewRenderProcess?.terminate() ?: false
            } catch (e: Exception) {
            }
        }
        if (!terminated) restart()
    }

    /**
     * Runs learner code. Events sent to onEvent:
     * log, warn, errlog, error, check, val, timeout, restart, end
     */
    fun run(code: String, extra: String, quiet: Boolean, onEvent: (String, String) -> Unit) {
        val id = ++runId
        listener = onEvent
        val old = timeout
        if (old != null) main.removeCallbacks(old)
        val js = "window.__run(" + id + "," + JSONObject.quote(code) + "," + JSONObject.quote(extra) + "," + quiet + ")"
        val start: () -> Unit = {
            val w = web
            if (w == null) {
                onEvent("restart", "")
                onEvent("end", "")
            } else {
                val t = Runnable {
                    if (id == runId) {
                        runId++
                        listener = null
                        onEvent("timeout", "")
                        onEvent("end", "")
                        kill()
                    }
                }
                timeout = t
                main.postDelayed(t, 4000)
                w.evaluateJavascript(js) { res ->
                    if (id == runId) {
                        main.removeCallbacks(t)
                        if (res == null || !res.contains("ok")) {
                            runId++
                            listener = null
                            onEvent("restart", "")
                            onEvent("end", "")
                            restart()
                        }
                    }
                }
            }
        }
        if (ready) start() else pending = start
    }
}

private const val HARNESS = """<!DOCTYPE html><html><head><meta charset="utf-8"></head><body><script>
(function () {
  var B = window.AndroidBridge;
  var AF = Object.getPrototypeOf(async function () {}).constructor;
  var runId = 0, count = 0, quiet = false, timers = [];
  var logs = [];
  var st = window.setTimeout.bind(window);
  var si = window.setInterval.bind(window);

  function send(type, text) {
    try { B.emit(String(runId), String(type), String(text)); } catch (e) {}
  }

  function fmt(v, top, seen) {
    seen = seen || [];
    var t = typeof v;
    if (t === 'string') return top ? v : JSON.stringify(v);
    if (t === 'undefined') return 'undefined';
    if (v === null) return 'null';
    if (t === 'function') return '[Function' + (v.name ? ': ' + v.name : '') + ']';
    if (t === 'bigint') return String(v) + 'n';
    if (t !== 'object') return String(v);
    if (v instanceof Error) return v.name + ': ' + v.message;
    if (v.nodeType) return v.outerHTML || v.nodeName;
    if (seen.indexOf(v) >= 0) return '[Circular]';
    if (seen.length > 50) return '...';
    seen.push(v);
    var out;
    if (Array.isArray(v)) {
      var items = [];
      for (var i = 0; i < v.length; i++) items.push(fmt(v[i], false, seen));
      out = '[' + items.join(', ') + ']';
    } else if (v instanceof Map) {
      out = 'Map ' + fmt(Array.from(v.entries()), false, seen);
    } else if (v instanceof Set) {
      out = 'Set ' + fmt(Array.from(v.values()), false, seen);
    } else if (v instanceof Date) {
      out = v.toString();
    } else if (v instanceof Promise) {
      out = 'Promise';
    } else {
      var keys = Object.keys(v);
      var cname = (v.constructor && v.constructor.name && v.constructor.name !== 'Object') ? v.constructor.name + ' ' : '';
      var parts = [];
      for (var k = 0; k < keys.length; k++) parts.push(keys[k] + ': ' + fmt(v[keys[k]], false, seen));
      out = keys.length ? cname + '{ ' + parts.join(', ') + ' }' : cname + '{}';
    }
    seen.pop();
    return out;
  }

  function fail(e) {
    var name = (e && e.name) ? e.name : 'Error';
    var msg = (e && e.message !== undefined) ? e.message : String(e);
    var line = '';
    try {
      var m = /<anonymous>:(\d+):\d+/.exec(String(e && e.stack));
      if (m) {
        var n = parseInt(m[1], 10) - 2;
        if (n > 0) line = '@@line=' + n;
      }
    } catch (x) {}
    send('error', name + ': ' + msg + line);
  }

  function logger(type) {
    return function () {
      var parts = [];
      for (var i = 0; i < arguments.length; i++) parts.push(fmt(arguments[i], true));
      var line = parts.join(' ');
      if (logs.length < 2000) logs.push(line);
      if (quiet) return;
      count++;
      if (count === 301) send('warn', 'Output stopped after 300 lines.');
      if (count > 300) return;
      send(type, line);
    };
  }

  window.console = {
    log: logger('log'), info: logger('log'), debug: logger('log'), table: logger('log'),
    warn: logger('warn'), error: logger('errlog')
  };

  window.setTimeout = function (f, ms) {
    var args = Array.prototype.slice.call(arguments, 2);
    var id = st(function () {
      try { if (typeof f === 'function') f.apply(null, args); } catch (e) { fail(e); }
    }, ms);
    timers.push(id);
    return id;
  };
  window.setInterval = function (f, ms) {
    var args = Array.prototype.slice.call(arguments, 2);
    var id = si(function () {
      try { if (typeof f === 'function') f.apply(null, args); } catch (e) { fail(e); }
    }, Math.max(Number(ms) || 0, 50));
    timers.push(id);
    return id;
  };

  window.alert = function (m) { console.log('[alert] ' + m); };
  window.prompt = function () { return null; };
  window.confirm = function () { return false; };
  window.open = function () { return null; };
  window.XMLHttpRequest = undefined;
  window.WebSocket = undefined;

  window.addEventListener('unhandledrejection', function (ev) { fail(ev.reason); ev.preventDefault(); });
  window.onerror = function (msg) { send('error', String(msg).replace('Uncaught ', '')); return true; };

  // Offline practice server: fetch never touches the network.
  var MOVIES = [
    { title: 'Inception', year: 2010, rating: 8.8 },
    { title: 'Interstellar', year: 2014, rating: 8.7 },
    { title: 'Dangal', year: 2016, rating: 8.3 },
    { title: '3 Idiots', year: 2009, rating: 8.4 },
    { title: 'The Matrix', year: 1999, rating: 8.7 },
    { title: 'Spirited Away', year: 2001, rating: 8.6 }
  ];
  var USERS = [
    { id: 1, name: 'Asha', email: 'asha@example.com' },
    { id: 2, name: 'Ravi', email: 'ravi@example.com' },
    { id: 3, name: 'Meera', email: 'meera@example.com' }
  ];
  var POSTS = [
    { id: 1, userId: 1, title: 'Learning JavaScript', likes: 12 },
    { id: 2, userId: 2, title: 'My first function', likes: 7 },
    { id: 3, userId: 1, title: 'Arrays are useful', likes: 21 }
  ];
  window.fetch = function (url) {
    var u = String(url).toLowerCase();
    var data;
    if (u.indexOf('weather') >= 0) {
      var city = 'Delhi';
      var cm = /[?&]city=([^&]*)/.exec(String(url));
      if (cm) city = decodeURIComponent(cm[1]);
      data = { city: city, temp: 20 + (city.length * 3) % 15, condition: city.length % 2 ? 'Sunny' : 'Cloudy', humidity: 40 + city.length };
    } else if (u.indexOf('movie') >= 0) {
      var list = MOVIES;
      var qm = /[?&]q=([^&]*)/.exec(String(url));
      if (qm) {
        var term = decodeURIComponent(qm[1]).toLowerCase();
        list = MOVIES.filter(function (m) { return m.title.toLowerCase().indexOf(term) >= 0; });
      }
      data = { results: list };
    } else if (u.indexOf('user') >= 0) {
      data = USERS;
    } else if (u.indexOf('post') >= 0) {
      data = POSTS;
    } else {
      data = { message: 'Hello from the practice server', url: String(url) };
    }
    return new Promise(function (resolve, reject) {
      st(function () {
        if (u.indexOf('offline') >= 0) { reject(new TypeError('Failed to fetch')); return; }
        var ok = u.indexOf('missing') < 0;
        var body = ok ? data : { error: 'Not found' };
        resolve({
          ok: ok,
          status: ok ? 200 : 404,
          json: function () { return Promise.resolve(JSON.parse(JSON.stringify(body))); },
          text: function () { return Promise.resolve(JSON.stringify(body)); }
        });
      }, 400);
    });
  };

  window.__emit = send;
  window.__fmt = function (v) { return fmt(v, false); };
  window.__logs = logs;
  window.__code = '';

  window.__run = function (id, code, extra, q) {
    runId = id;
    for (var i = 0; i < timers.length; i++) { clearTimeout(timers[i]); clearInterval(timers[i]); }
    timers = [];
    logs.length = 0;
    count = 0;
    quiet = !!q;
    window.__code = code;
    try { document.body.innerHTML = ''; } catch (e) {}
    var fn;
    try {
      fn = new AF(code + '\n' + extra);
    } catch (e) {
      fail(e);
      B.emit(String(id), 'end', '');
      return 'ok';
    }
    fn().then(
      function () { B.emit(String(id), 'end', ''); },
      function (e) { if (id === runId) fail(e); B.emit(String(id), 'end', ''); }
    );
    return 'ok';
  };
})();
</script></body></html>"""

// ---------------------------------------------------------------------------
// Tutor: explains errors and code in plain language (works offline)
// ---------------------------------------------------------------------------

data class Help(val title: String, val hint: String, val explain: String)

object Tutor {
    private val builtins = listOf(
        "console", "Math", "JSON", "Number", "String", "Boolean", "Array", "Object", "Date",
        "parseInt", "parseFloat", "document", "window", "setTimeout", "setInterval", "fetch",
        "Promise", "undefined", "true", "false", "null", "alert", "isNaN"
    )
    private val keywords = listOf(
        "let", "const", "var", "function", "return", "if", "else", "for", "while", "class",
        "new", "async", "await", "try", "catch", "throw", "break", "continue", "typeof"
    )
    private val methods = listOf(
        "log", "push", "pop", "shift", "unshift", "map", "filter", "forEach", "reduce", "find",
        "includes", "indexOf", "join", "slice", "splice", "sort", "toUpperCase", "toLowerCase",
        "trim", "split", "toFixed", "parse", "stringify", "floor", "round", "random", "ceil",
        "max", "min", "then", "catch", "keys", "values", "getElementById", "querySelector",
        "createElement", "addEventListener", "appendChild", "click"
    )
    private val methodInfo = mapOf(
        "map" to "goes through each item and builds a new array from the results",
        "filter" to "goes through each item and keeps only the ones that pass the test",
        "forEach" to "runs the given code once for each item",
        "reduce" to "combines all items into one value, step by step",
        "find" to "returns the first item that passes the test",
        "push" to "adds an item to the end of the array",
        "pop" to "removes the last item of the array",
        "includes" to "answers true or false: is this value inside?",
        "indexOf" to "gives the position of a value, or -1 if it is missing",
        "join" to "glues all items into one string",
        "slice" to "copies a part without changing the original",
        "sort" to "puts the items in order",
        "split" to "cuts a string into an array of pieces",
        "trim" to "removes spaces from both ends of a string",
        "toUpperCase" to "gives the text in CAPITAL letters",
        "toLowerCase" to "gives the text in small letters",
        "toFixed" to "formats a number with a fixed count of decimals",
        "parse" to "turns JSON text into a real JavaScript value",
        "stringify" to "turns a JavaScript value into JSON text",
        "then" to "runs the given function when the promise is ready",
        "catch" to "runs the given function if something went wrong",
        "json" to "reads the response and turns it into a JavaScript value",
        "floor" to "rounds a number down",
        "round" to "rounds a number to the nearest whole number",
        "random" to "gives a random number from 0 up to (not including) 1",
        "addEventListener" to "registers a function to run when the event happens",
        "createElement" to "creates a new page element",
        "appendChild" to "puts an element inside another element",
        "getElementById" to "finds the page element with this id",
        "querySelector" to "finds the first page element matching the selector"
    )

    fun declaredNames(code: String): List<String> {
        val out = LinkedHashSet<String>()
        Regex("""\b(?:let|const|var|function|class)\s+([A-Za-z_][A-Za-z0-9_]*)""")
            .findAll(code).forEach { out.add(it.groupValues[1]) }
        Regex("""function\s*[A-Za-z0-9_]*\s*\(([^)]*)\)""").findAll(code).forEach { m ->
            m.groupValues[1].split(",").map { it.trim() }
                .filter { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
                .forEach { out.add(it) }
        }
        return out.toList()
    }

    private fun dist(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    private fun closest(word: String, options: List<String>): String? {
        var best: String? = null
        var bestD = 99
        for (o in options) {
            if (o == word) continue
            val d = if (o.equals(word, ignoreCase = true)) 0 else dist(o.lowercase(), word.lowercase())
            if (d < bestD) {
                bestD = d
                best = o
            }
        }
        val limit = if (word.length <= 3) 1 else 2
        return if (bestD <= limit) best else null
    }

    /** Looks for unclosed quotes and brackets. Returns a plain-language message or null. */
    private fun brackets(code: String): String? {
        val stack = ArrayList<Pair<Char, Int>>()
        var line = 1
        var i = 0
        var quote = ' '
        while (i < code.length) {
            val c = code[i]
            val next = if (i + 1 < code.length) code[i + 1] else ' '
            if (quote != ' ') {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == quote) {
                    quote = ' '
                } else if (c == '\n') {
                    if (quote != '`') return "On line $line a piece of text starts with a quote but never ends. Every opening quote needs a matching closing quote on the same line."
                    line++
                }
                i++
                continue
            }
            if (c == '\n') {
                line++
                i++
                continue
            }
            if (c == '/' && next == '/') {
                while (i < code.length && code[i] != '\n') i++
                continue
            }
            if (c == '/' && next == '*') {
                val end = code.indexOf("*/", i + 2)
                if (end < 0) return "A comment that starts with /* on line $line is never closed with */."
                line += code.substring(i, end).count { it == '\n' }
                i = end + 2
                continue
            }
            if (c == '"' || c == '\'' || c == '`') {
                quote = c
                i++
                continue
            }
            if (c == '(' || c == '{' || c == '[') {
                stack.add(Pair(c, line))
            } else if (c == ')' || c == '}' || c == ']') {
                val open = if (c == ')') '(' else if (c == '}') '{' else '['
                if (stack.isEmpty()) return "There is an extra $c on line $line with no matching $open before it."
                val top = stack.removeAt(stack.size - 1)
                if (top.first != open) return "On line $line you close with $c, but the last thing you opened was ${top.first} on line ${top.second}. Brackets must be closed in the reverse order they were opened."
            }
            i++
        }
        if (quote != ' ') return "A piece of text starts with $quote but never ends. Add the closing $quote."
        if (stack.isNotEmpty()) {
            val top = stack[stack.size - 1]
            val close = if (top.first == '(') ')' else if (top.first == '{') '}' else ']'
            return "The ${top.first} opened on line ${top.second} is never closed. Add a matching $close."
        }
        return null
    }

    private fun keywordTypo(code: String): String? {
        for (line in code.lines()) {
            val first = Regex("""^\s*([A-Za-z]+)\s+[A-Za-z_]""").find(line)?.groupValues?.get(1) ?: continue
            if (first in keywords) continue
            val k = closest(first, listOf("let", "const", "function", "return", "class")) ?: continue
            return "`$first` is not a JavaScript word. It looks like you meant `$k`. Keywords must be spelled exactly, in small letters."
        }
        return null
    }

    fun explainError(raw: String, code: String): Help {
        val kind = raw.substringBefore(":").trim()
        val msg = raw.substringAfter(":", raw).trim()
        val id = "[A-Za-z_][A-Za-z0-9_]*"

        val notDefined = Regex("^($id) is not defined").find(msg)
        if (notDefined != null) {
            val word = notDefined.groupValues[1]
            val names = declaredNames(code)
            if (word in names) {
                return Help(
                    "Out of reach", "Where was `$word` created: inside a { } block or function?",
                    "`$word` does exist, but only inside the { } block or function where it was created. Outside that block JavaScript cannot see it. This rule is called scope. If you need it outside, create it outside."
                )
            }
            val guess = closest(word, names + builtins)
            if (guess != null) {
                return Help(
                    "Name not found", "Look closely at how `$word` is spelled.",
                    "JavaScript cannot find anything called `$word`. It looks like you meant `$guess`. Names must match exactly, letter for letter, including capital letters."
                )
            }
            val kw = closest(word, keywords)
            if (kw != null) {
                return Help(
                    "Name not found", "Is `$word` a misspelled keyword?",
                    "JavaScript does not know the word `$word`. It looks like you meant the keyword `$kw`."
                )
            }
            return Help(
                "Name not found", "Is `$word` meant to be text, or a variable?",
                "JavaScript read `$word` as the name of a variable, but no variable with that name has been created. If you meant plain text, wrap it in quotes: \"$word\". If you meant a variable, create it first with let or const."
            )
        }

        val tdz = Regex("Cannot access '($id)' before initialization").find(msg)
        if (tdz != null) {
            val word = tdz.groupValues[1]
            return Help(
                "Used too early", "Which line creates `$word`, and which line uses it?",
                "You used `$word` on a line above the one that creates it. JavaScript reads from top to bottom, so a let or const variable must be created before it is used. Move the line that creates `$word` higher up."
            )
        }

        val notFn = Regex("^(.+) is not a function").find(msg)
        if (notFn != null) {
            val expr = notFn.groupValues[1]
            val last = expr.substringAfterLast(".")
            val guess = closest(last, methods)
            if (guess != null) {
                val fixed = if (expr.contains(".")) expr.substringBeforeLast(".") + "." + guess else guess
                return Help(
                    "Not a function", "Check the spelling and capital letters in `$expr`.",
                    "Writing ( ) after something means: run this function. But `$expr` does not exist as a function. It looks like you meant `$fixed`. JavaScript is case-sensitive, so capital and small letters matter."
                )
            }
            return Help(
                "Not a function", "What does `$expr` really contain?",
                "Writing ( ) after something means: run this function. But `$expr` is not a function, so it cannot be run. Check the spelling, and check what value it really holds by printing it with console.log."
            )
        }

        val readProp = Regex("Cannot (read|set) properties of (undefined|null)(?: \\((?:reading|setting) '(.+)'\\))?").find(msg)
        if (readProp != null) {
            val empty = readProp.groupValues[2]
            val prop = readProp.groupValues[3]
            val what = if (prop.isEmpty()) "a property" else "`.$prop`"
            return Help(
                "Nothing there", "Print the thing that comes just before the dot.",
                "You asked for $what on something that is $empty, which means there is no value there at all. The problem is the part just before the dot or bracket. Maybe it was never given a value, the name is slightly wrong, or an array position does not exist."
            )
        }

        if (msg.contains("Assignment to constant variable")) {
            return Help(
                "const cannot change", "Was the variable created with const?",
                "You tried to give a new value to a variable created with const. const means the variable can never be reassigned. If the value needs to change, create it with let instead."
            )
        }

        val declared = Regex("Identifier '($id)' has already been declared").find(msg)
        if (declared != null) {
            val word = declared.groupValues[1]
            return Help(
                "Created twice", "How many times do you write let or const before `$word`?",
                "`$word` is created more than once. let and const are only for creating a variable the first time. To change it later, write just `$word = newValue` without let or const. Or pick a different name."
            )
        }

        if (msg.contains("Missing initializer in const declaration")) {
            return Help(
                "const needs a value", "What should the const contain?",
                "A const must get its value on the same line where it is created, because it can never be changed afterwards. Write something like: const total = 0;"
            )
        }

        if (msg.contains("Maximum call stack size exceeded")) {
            return Help(
                "Never-ending calls", "Does your function call itself?",
                "A function kept calling itself (or two functions kept calling each other) without ever stopping, until JavaScript ran out of room. A function that calls itself needs a condition where it stops and returns."
            )
        }

        if (msg.contains("is not iterable")) {
            return Help(
                "Cannot loop over this", "Is the value really an array?",
                "You tried to loop over, or spread, something that is not a list. for...of works on arrays and strings, not on plain objects or numbers. Print the value with console.log to see what it really is."
            )
        }

        if (msg.contains("await is only valid")) {
            return Help(
                "await needs async", "Look at the function that contains await.",
                "await can only be used inside a function marked with async. Add the word async before the function that contains this await."
            )
        }

        if (kind == "SyntaxError") {
            val b = brackets(code)
            val typo = keywordTypo(code)
            if (msg.contains("Unexpected end of input")) {
                return Help(
                    "Something is not closed", "Count your opening and closing brackets.",
                    b ?: "JavaScript reached the end of your code while still waiting for something to be closed. Check that every ( has a ), every { has a } and every [ has a ]."
                )
            }
            if (msg.contains("missing ) after argument list")) {
                return Help(
                    "Problem inside ( )", "Look inside the round brackets of a function call.",
                    b ?: "Inside the ( ) of a function call JavaScript found something it did not expect. Usually a closing ) is missing, a quote is missing around text, or a comma is missing between two values."
                )
            }
            if (msg.contains("Invalid or unexpected token")) {
                return Help(
                    "Broken text or symbol", "Check your quotes.",
                    b ?: "JavaScript found a character it cannot understand. The most common cause is text with an opening quote but no closing quote, or curly quotes copied from another app. Use straight quotes like \" or '."
                )
            }
            if (msg.contains("Unexpected identifier") || msg.contains("Unexpected string") || msg.contains("Unexpected number")) {
                return Help(
                    "Something is missing between two things", "Is a comma, + or keyword missing or misspelled?",
                    typo ?: b ?: "JavaScript found two things next to each other with nothing joining them. Usually a comma, a + or an = is missing, text is missing its quotes, or a keyword like let or function is misspelled."
                )
            }
            val token = Regex("Unexpected token '(.+?)'").find(msg)
            if (token != null) {
                val tk = token.groupValues[1]
                return Help(
                    "Unexpected symbol", "Look just before the `$tk`.",
                    b ?: typo ?: "JavaScript did not expect `$tk` at that point. The mistake is usually just before it: a missing bracket, comma or value, or one bracket too many."
                )
            }
            return Help(
                "Grammar problem", "Check brackets, quotes and commas.",
                b ?: typo ?: "JavaScript could not understand how the code is written, so it did not run any of it. The message was: $msg. Check brackets, quotes and commas near where you last typed."
            )
        }

        return Help(
            "Error", "Read the message slowly, word by word.",
            "JavaScript stopped with this message: $msg. The first word ($kind) is the kind of error. Find the line it happened on, then print the values used on that line with console.log to see which one is not what you expected."
        )
    }

    /** Code that runs but probably does not do what the learner expects. */
    fun smells(code: String, logs: List<String>): String? {
        val strNum = Regex("""\b(?:let|const|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(["'])(-?\d+(?:\.\d+)?)\2""")
        for (m in strNum.findAll(code)) {
            val v = m.groupValues[1]
            val num = m.groupValues[3]
            val after = Regex("\\b" + v + "\\s*\\+\\s*(\\d+)").find(code)
            val before = Regex("(\\d+)\\s*\\+\\s*" + v + "\\b").find(code)
            if (after != null || before != null) {
                val joined = if (after != null) num + after.groupValues[1] else (before?.groupValues?.get(1) ?: "") + num
                return "`$v` contains text, not a number, because \"$num\" is written inside quotes. JavaScript therefore treats \"$num\" differently from $num: with text, + joins instead of adding, which is why you get \"$joined\". Remove the quotes, or convert the text with Number($v)."
            }
        }
        if (Regex("""\bif\s*\(\s*[A-Za-z_][A-Za-z0-9_.]*\s*=\s*[^=\s]""").containsMatchIn(code) ||
            Regex("""\bif\s*\(\s*[A-Za-z_][A-Za-z0-9_.]*\s*=\s+[^=]""").containsMatchIn(code)
        ) {
            return "Inside if ( ) you used a single =. A single = stores a value, it does not compare. To ask \"are these equal?\" use ===."
        }
        if (logs.any { it == "NaN" || it.endsWith(" NaN") }) {
            return "NaN means Not a Number. It appears when maths is done with something that is not a number, for example text multiplied by a number, or a value that is undefined."
        }
        if (logs.any { it == "undefined" }) {
            return "undefined was printed. That means the value was never set: a variable without a value, a function that does not return anything, or an object key or array position that does not exist."
        }
        if (logs.any { it.contains("[object Object]") }) {
            return "[object Object] appears when an object is joined to text with +. Print the object on its own, pick one of its properties, or use JSON.stringify(yourObject)."
        }
        return null
    }

    fun idea(code: String): String {
        val tip = smells(code, emptyList())
        return when {
            code.isBlank() -> "Start small. Type console.log(\"Hello\"); and press Run."
            tip != null -> tip
            !code.contains("console.log") -> "Nothing will be shown unless you print it. Wrap a value in console.log( ) to see it."
            else -> "Before you press Run, say out loud what you think will be printed. Then change one value and predict again. Predicting is the fastest way to learn."
        }
    }

    fun checkExtra(expr: String): String {
        val e = if (expr.isBlank()) "true" else expr
        return ";try { __emit('check', (function () { return !!(" + e + "); })() ? 'true' : 'false'); } catch (__e) { __emit('check', 'false'); }"
    }

    fun valuesExtra(code: String): String {
        val names = LinkedHashSet<String>()
        Regex("""\b(?:let|const|var)\s+([A-Za-z_][A-Za-z0-9_]*)""").findAll(code).forEach { names.add(it.groupValues[1]) }
        return names.take(8).joinToString("") {
            ";try { if (typeof $it !== 'function') __emit('val', '$it = ' + __fmt($it)); } catch (__e) {}"
        }
    }

    private fun describeValue(v: String): String {
        val t = v.trim()
        val call = Regex("""^([A-Za-z_][A-Za-z0-9_.]*)\.([A-Za-z]+)\(""").find(t)
        return when {
            t.isEmpty() -> "is created with no value yet (undefined)"
            t.startsWith("await ") -> "waits for the result to arrive, then stores it"
            t.startsWith("\"") || t.startsWith("'") || t.startsWith("`") -> "stores text (a string)"
            t.matches(Regex("""-?\d+(\.\d+)?""")) -> "stores the number $t"
            t == "true" || t == "false" -> "stores the boolean $t"
            t == "null" -> "stores null, meaning deliberately empty"
            t.startsWith("[") -> "stores an array (an ordered list)"
            t.startsWith("{") -> "stores an object (named values grouped together)"
            t.startsWith("function") || t.startsWith("async") ||
                Regex("""^(\([^)]*\)|[A-Za-z_][A-Za-z0-9_]*)\s*=>""").containsMatchIn(t) -> "stores a function, so it can be called later"
            t.startsWith("new ") -> "stores a new " + t.removePrefix("new ").substringBefore("(").trim() + " object"
            call != null -> "stores the result of ${call.groupValues[1]}.${call.groupValues[2]}()"
            Regex("""^[A-Za-z_][A-Za-z0-9_]*\(""").containsMatchIn(t) -> "stores what the function " + t.substringBefore("(") + "() gives back"
            else -> "stores the result of: $t"
        }
    }

    fun explainCode(code: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        fun add(a: String, b: String) {
            if (seen.add(a)) out.add(Pair(a, b))
        }

        val id = "[A-Za-z_][A-Za-z0-9_]*"
        val declR = Regex("(let|const|var)\\s+($id)\\s*(?:=\\s*(.*))?")
        val forOfR = Regex("for\\s*\\(\\s*(?:const|let|var)\\s+($id)\\s+(of|in)\\s+(.*)\\)\\s*\\{?")
        val forR = Regex("for\\s*\\((.*);(.*);(.*)\\)\\s*\\{?")
        val whileR = Regex("while\\s*\\((.*)\\)\\s*\\{?")
        val elseIfR = Regex("\\}?\\s*else\\s+if\\s*\\((.*)\\)\\s*\\{?")
        val ifR = Regex("if\\s*\\((.*)\\)\\s*\\{?")
        val funcR = Regex("(async\\s+)?function\\s+($id)\\s*\\(([^)]*)\\)\\s*\\{?")
        val logR = Regex("console\\.log\\((.*)\\)")
        val assignR = Regex("($id(?:\\.$id|\\[[^\\]]*\\])*)\\s*(=|\\+=|-=|\\*=|/=)\\s*([^=].*)")
        val callR = Regex("(?:await\\s+)?($id(?:\\.$id)*)\\((.*)\\).*")
        val methodR = Regex("\\.($id)\\(")
        val arrowR = Regex("(\\(?[A-Za-z_, ]*\\)?)\\s*=>\\s*([^{].*)")

        for (rawLine in code.lines()) {
            var line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("//")) continue
            line = line.removeSuffix(";").trim()
            if (line.all { it in "{}()[];," }) continue

            val decl = declR.matchEntire(line)
            val forOf = forOfR.matchEntire(line)
            val forC = forR.matchEntire(line)
            val whileM = whileR.matchEntire(line)
            val elseIf = elseIfR.matchEntire(line)
            val ifM = ifR.matchEntire(line)
            val func = funcR.matchEntire(line)
            val log = logR.matchEntire(line)
            val assign = assignR.matchEntire(line)
            val call = callR.matchEntire(line)

            when {
                decl != null -> {
                    val kw = decl.groupValues[1]
                    val word = when (kw) {
                        "const" -> "creates a variable that cannot be reassigned later"
                        "let" -> "creates a variable whose value can change later"
                        else -> "the older way to create a variable; prefer let or const"
                    }
                    add(kw, word)
                    add(decl.groupValues[2], describeValue(decl.groupValues[3]))
                }
                func != null -> {
                    val params = func.groupValues[3].trim()
                    val inputs = if (params.isEmpty()) "It takes no inputs." else "Its inputs are: $params."
                    add("function " + func.groupValues[2] + "()", "defines a reusable block of code named " + func.groupValues[2] + ". " + inputs + " It only runs when it is called.")
                }
                forOf != null -> {
                    val what = if (forOf.groupValues[2] == "of") "each item" else "each key"
                    add("for (... " + forOf.groupValues[2] + " ...)", "goes through $what of " + forOf.groupValues[3].trim() + ", one at a time, calling it " + forOf.groupValues[1])
                }
                forC != null -> add(
                    "for (...)",
                    "a loop. Start: " + forC.groupValues[1].trim() + ". Keep going while: " + forC.groupValues[2].trim() + ". After each round: " + forC.groupValues[3].trim()
                )
                whileM != null -> add("while (...)", "repeats the block as long as this stays true: " + whileM.groupValues[1].trim())
                elseIf != null -> add("else if (...)", "if the checks above were false, tries this one: " + elseIf.groupValues[1].trim())
                ifM != null -> add("if (...)", "runs the block only when this is true: " + ifM.groupValues[1].trim())
                line.startsWith("else") || line.startsWith("} else") -> add("else", "runs when the conditions above were false")
                line.startsWith("return") -> add(line, "sends this value back to the place where the function was called, and ends the function")
                log != null -> add(line, "prints " + log.groupValues[1] + " to the output")
                line.startsWith("try") -> add("try", "runs the block, watching for errors")
                line.startsWith("catch") || line.startsWith("} catch") -> add("catch", "runs only if the try block had an error, instead of crashing")
                line.startsWith("throw") -> add(line, "stops here and reports an error on purpose")
                line.startsWith("class ") -> add(line.removeSuffix("{").trim(), "defines a blueprint for creating objects")
                line.startsWith("constructor") -> add("constructor()", "runs automatically when a new object is made from the class")
                line.endsWith("++") -> add(line, "adds 1 to " + line.removeSuffix("++"))
                line.endsWith("--") -> add(line, "subtracts 1 from " + line.removeSuffix("--"))
                assign != null -> {
                    val op = assign.groupValues[2]
                    val target = assign.groupValues[1]
                    val meaning = when (op) {
                        "=" -> "replaces the value of $target with " + assign.groupValues[3]
                        "+=" -> "adds " + assign.groupValues[3] + " to $target"
                        "-=" -> "subtracts " + assign.groupValues[3] + " from $target"
                        else -> "updates $target using " + assign.groupValues[3]
                    }
                    add(line, meaning)
                }
                call != null -> add(call.groupValues[1] + "()", "calls (runs) " + call.groupValues[1])
                else -> {
                }
            }

            for (m in methodR.findAll(line)) {
                val name = m.groupValues[1]
                val info = methodInfo[name]
                if (info != null && name != "log") add(".$name()", info)
            }

            val arrow = arrowR.find(line)
            if (arrow != null) {
                val p = arrow.groupValues[1].trim().trim('(', ')').trim()
                var body = arrow.groupValues[2].trim().removeSuffix(";")
                while (body.endsWith(")") && body.count { it == ')' } > body.count { it == '(' }) {
                    body = body.dropLast(1)
                }
                if (p.isNotEmpty() && !p.contains(",")) {
                    add(p, "the current item (the input of the small function)")
                    add(body, "what the small function gives back for each $p")
                }
                add("=>", "a short way to write a function: inputs on the left, result on the right")
            }

            if (line.contains("===")) add("===", "checks whether two values are exactly equal")
            if (line.contains("!==")) add("!==", "checks whether two values are different")
            if (line.contains("&&")) add("&&", "AND: true only when both sides are true")
            if (line.contains("||")) add("||", "OR: true when at least one side is true")
            if (line.contains(" % ")) add("%", "the remainder left after dividing")
            if (line.contains("\${")) add("\${ }", "inserts a value into text written with backticks")
            if (line.contains("await ")) add("await", "pauses here until the promise is ready")
        }
        if (out.isEmpty()) out.add(Pair("Nothing to explain yet", "Write a line of code, then tap Explain."))
        return out
    }
}
