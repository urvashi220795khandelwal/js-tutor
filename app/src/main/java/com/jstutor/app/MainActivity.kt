package com.jstutor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppData.init(this)
        JsEngine.init(this)
        setContent { Root() }
    }
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF7DF1E),
    onPrimary = Color(0xFF1B1B1B),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF1B1E23),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF252A31),
    onSurfaceVariant = Color(0xFFB4BAC4),
    secondaryContainer = Color(0xFF2E2A14),
    onSecondaryContainer = Color(0xFFF3E9B5)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6B5B00),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F7F4),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFECECE6),
    onSurfaceVariant = Color(0xFF4A4A44),
    secondaryContainer = Color(0xFFFFF4C2),
    onSecondaryContainer = Color(0xFF3A3000)
)

private val Good = Color(0xFF2E9E5B)
private val Amber = Color(0xFFC98A00)

private const val TIMEOUT_TEXT =
    "Your code ran for more than 4 seconds, so it was stopped. This usually means a loop never ends. Check that the loop's condition eventually becomes false, for example that the counter really changes each round."

private val SYMBOLS = listOf(
    "(", ")", "{", "}", "[", "]", ";", "=", "\"", "'", ".", ",", "+", "-", "*", "/",
    "<", ">", "!", "&", "|", ":", "_", "`", "\$", "tab"
)

@Composable
fun Root() {
    val dark = when (AppData.theme) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        var tab by rememberSaveable { mutableStateOf(0) }
        var open by rememberSaveable { mutableStateOf("") }
        BackHandler(enabled = open.isNotEmpty() || tab != 0) {
            if (open.isNotEmpty()) open = "" else tab = 0
        }
        val lesson = AppData.lessons.firstOrNull { it.id == open }
        val project = AppData.projects.firstOrNull { it.id == open }
        Scaffold(
            bottomBar = {
                if (open.isEmpty()) {
                    NavigationBar {
                        val items = listOf(
                            Pair("🏠", "Home"), Pair("📖", "Learn"), Pair("⌨", "Code"),
                            Pair("🧩", "Projects"), Pair("📈", "Progress")
                        )
                        items.forEachIndexed { i, item ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Text(item.first, fontSize = 18.sp) },
                                label = { Text(item.second, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when {
                    lesson != null -> key(lesson.id) {
                        LessonScreen(lesson, onBack = { open = "" }, onOpen = { open = it })
                    }
                    project != null -> key(project.id) {
                        ProjectScreen(project, onBack = { open = "" })
                    }
                    tab == 0 -> HomeScreen(
                        onContinue = { open = AppData.current()?.id ?: "" },
                        onEditor = { tab = 2 }
                    )
                    tab == 1 -> LearnScreen(onOpen = { open = it })
                    tab == 2 -> PlaygroundScreen()
                    tab == 3 -> ProjectsScreen(onOpen = { open = it })
                    else -> ProgressScreen()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Small shared pieces
// ---------------------------------------------------------------------------

@Composable
fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)
    ) { content() }
}

@Composable
fun Bar(fraction: Float) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.surfaceVariant)
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.primary))
    }
}

@Composable
fun Label(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )
}

@Composable
fun RichText(text: String) {
    val c = MaterialTheme.colorScheme
    val parts = text.split("`")
    Text(
        buildAnnotatedString {
            parts.forEachIndexed { i, p ->
                if (i % 2 == 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = c.primary, fontWeight = FontWeight.Bold)) {
                        append(p)
                    }
                } else {
                    append(p)
                }
            }
        },
        fontSize = 16.sp,
        lineHeight = 24.sp
    )
}

@Composable
fun CodeBlock(code: String) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceVariant)
            .horizontalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Text(code, fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp, color = c.onSurface)
    }
}

@Composable
fun SymbolKey(label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = c.onSurface)
    }
}

@Composable
fun CodeEditor(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, fill: Boolean, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceVariant).padding(12.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 17.sp,
                lineHeight = 25.sp,
                color = c.onSurface
            ),
            cursorBrush = SolidColor(c.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrect = false,
                keyboardType = KeyboardType.Ascii
            )
        )
        if (value.text.isEmpty()) {
            Text("// write JavaScript here", fontFamily = FontFamily.Monospace, fontSize = 17.sp, color = c.onSurfaceVariant)
        }
    }
}

data class OutLine(val kind: String, val text: String)

@Composable
fun OutputPanel(lines: List<OutLine>) {
    if (lines.isEmpty()) return
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(c.surface).padding(12.dp)
    ) {
        Text("Output", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVariant)
        lines.forEach { line ->
            val color = when (line.kind) {
                "error" -> c.error
                "warn" -> Amber
                "ok" -> Good
                "info" -> c.onSurfaceVariant
                else -> c.onSurface
            }
            Text(line.text, fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp, color = color)
        }
    }
}

@Composable
fun HelpPanel(title: String, items: List<Pair<String, String>>) {
    if (items.isEmpty()) return
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(c.secondaryContainer).padding(12.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.onSecondaryContainer)
        items.forEach { item ->
            val mono = item.first == "Solution" || item.first == "Result"
            Text(
                item.first,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = c.onSecondaryContainer,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                (if (mono) "" else "→ ") + item.second,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = c.onSecondaryContainer
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The workbench: editor + Run / Check / Explain / Hint / Solution / Clear
// ---------------------------------------------------------------------------

@Composable
fun Workbench(
    slot: String,
    initial: String,
    checkExpr: String,
    hintText: String,
    solution: String,
    clearTo: String,
    fill: Boolean,
    modifier: Modifier = Modifier
) {
    var field by remember(slot) { mutableStateOf(TextFieldValue(AppData.loadCode(slot, initial))) }
    val output = remember(slot) { mutableStateListOf<OutLine>() }
    var help by remember(slot) { mutableStateOf(listOf<Pair<String, String>>()) }
    var helpTitle by remember(slot) { mutableStateOf("") }
    var lastError by remember(slot) { mutableStateOf("") }

    fun execute(checking: Boolean) {
        val src = field.text
        AppData.saveCode(slot, src)
        output.clear()
        help = emptyList()
        helpTitle = ""
        lastError = ""
        var verdict = ""

        fun handle(type: String, text: String) {
            when (type) {
                "log" -> {
                    output.add(OutLine("log", text))
                }
                "warn" -> {
                    output.add(OutLine("warn", text))
                }
                "errlog" -> {
                    output.add(OutLine("error", text))
                }
                "error" -> {
                    val parts = text.split("@@line=")
                    val msg = parts[0]
                    val ln = parts.getOrNull(1)
                    output.add(OutLine("error", if (ln != null) "$msg  (line $ln)" else msg))
                    if (lastError.isEmpty()) {
                        lastError = msg
                        val h = Tutor.explainError(msg, src)
                        helpTitle = h.title
                        help = listOf(Pair("What happened", h.explain))
                    }
                }
                "timeout" -> {
                    lastError = "timeout"
                    output.add(OutLine("error", "Stopped: the code took too long."))
                    helpTitle = "Endless loop?"
                    help = listOf(Pair("What happened", TIMEOUT_TEXT))
                }
                "restart" -> {
                    lastError = "restart"
                    output.add(OutLine("warn", "The sandbox was reset. Press Run again."))
                }
                "check" -> {
                    verdict = text
                }
                "end" -> {
                    if (checking) {
                        if (verdict == "true") {
                            output.add(OutLine("ok", "✓ Correct! Well done."))
                            AppData.complete(slot)
                        } else if (lastError.isEmpty()) {
                            output.add(OutLine("warn", "Not quite yet. Read the challenge again, or tap Hint."))
                        } else {
                            output.add(OutLine("warn", "Fix the error first, then tap Check again."))
                        }
                    } else if (lastError.isEmpty()) {
                        val tip = Tutor.smells(src, output.map { it.text })
                        if (tip != null) {
                            helpTitle = "Take a closer look"
                            help = listOf(Pair("Notice", tip))
                        } else if (output.isEmpty()) {
                            output.add(OutLine("info", "The code ran with no errors, but nothing was printed. Use console.log( ) to see a value."))
                        }
                    }
                }
                else -> {
                }
            }
        }

        val extra = if (checking) Tutor.checkExtra(checkExpr) else ""
        JsEngine.run(src, extra, false) { type, text -> handle(type, text) }
    }

    fun explain() {
        val src = field.text
        helpTitle = "Explain"
        if (src.isBlank()) {
            help = listOf(Pair("Nothing yet", "Write some code first, then tap Explain."))
            return
        }
        val base = Tutor.explainCode(src)
        help = base
        var err = ""
        val vals = ArrayList<String>()

        fun handle(type: String, text: String) {
            when (type) {
                "error" -> {
                    if (err.isEmpty()) err = text.split("@@line=")[0]
                }
                "timeout" -> {
                    err = "timeout"
                }
                "val" -> {
                    vals.add(text)
                }
                "end" -> {
                    val all = ArrayList<Pair<String, String>>()
                    if (err == "timeout") {
                        all.add(Pair("Problem", TIMEOUT_TEXT))
                    } else if (err.isNotEmpty()) {
                        all.add(Pair("Problem", Tutor.explainError(err, src).explain))
                    }
                    all.addAll(base)
                    val smell = Tutor.smells(src, emptyList())
                    if (smell != null) all.add(Pair("Watch out", smell))
                    if (vals.isNotEmpty()) all.add(Pair("Result", vals.joinToString("\n")))
                    help = all
                }
                else -> {
                }
            }
        }

        JsEngine.run(src, Tutor.valuesExtra(src), true) { type, text -> handle(type, text) }
    }

    fun showHint() {
        val src = field.text
        helpTitle = "Hint"
        help = when {
            lastError == "timeout" -> listOf(Pair("Clue", "Look at your loop. What makes it stop?"))
            lastError.isNotEmpty() && lastError != "restart" -> listOf(Pair("Clue", Tutor.explainError(lastError, src).hint))
            hintText.isNotBlank() -> listOf(Pair("Clue", hintText))
            else -> listOf(Pair("Idea", Tutor.idea(src)))
        }
    }

    fun insert(s: String) {
        val t = field.text
        val a = field.selection.min.coerceIn(0, t.length)
        val b = field.selection.max.coerceIn(0, t.length)
        val next = t.substring(0, a) + s + t.substring(b)
        field = TextFieldValue(next, TextRange(a + s.length))
        AppData.saveCode(slot, next)
    }

    Column(modifier) {
        CodeEditor(
            value = field,
            onChange = {
                field = it
                AppData.saveCode(slot, it.text)
            },
            fill = fill,
            modifier = if (fill) Modifier.weight(1f) else Modifier.heightIn(min = 150.dp)
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            SYMBOLS.forEach { s ->
                SymbolKey(s) { insert(if (s == "tab") "  " else s) }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { execute(false) }) { Text("▶ Run") }
            if (checkExpr.isNotEmpty()) {
                Button(onClick = { execute(true) }) { Text("Check") }
            }
            FilledTonalButton(onClick = { explain() }) { Text("Explain") }
            FilledTonalButton(onClick = { showHint() }) { Text("Hint") }
            if (solution.isNotEmpty()) {
                OutlinedButton(onClick = {
                    helpTitle = "Solution"
                    help = listOf(Pair("Solution", solution), Pair("Tip", "Type it yourself instead of copying. Your fingers learn too."))
                }) { Text("Solution") }
            }
            OutlinedButton(onClick = {
                field = TextFieldValue(clearTo)
                AppData.saveCode(slot, clearTo)
                output.clear()
                help = emptyList()
                lastError = ""
            }) { Text(if (clearTo.isEmpty()) "Clear" else "Reset") }
        }
        if (output.isNotEmpty() || help.isNotEmpty()) {
            val m = if (fill) {
                Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxWidth()
            }
            Column(m) {
                OutputPanel(output)
                HelpPanel(helpTitle, help)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------

@Composable
fun HomeScreen(onContinue: () -> Unit, onEditor: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val cur = AppData.current()
    val pct = AppData.percent()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "JavaScript Tutor",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { AppData.cycleTheme() }) {
                Text(
                    when (AppData.theme) {
                        1 -> "☀ Light"
                        2 -> "🌙 Dark"
                        else -> "Auto"
                    }
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Panel {
            Text("Continue Learning", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            Text("Current:", color = c.onSurfaceVariant, fontSize = 14.sp)
            Text(cur?.title ?: "No lessons found", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Progress: $pct%", color = c.onSurfaceVariant, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.background)) {
                Box(Modifier.fillMaxWidth((pct / 100f).coerceIn(0f, 1f)).fillMaxHeight().background(c.primary))
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onContinue, enabled = cur != null, modifier = Modifier.fillMaxWidth()) {
                Text("Continue", fontSize = 17.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Panel {
            Text("Quick Code", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text("Write any JavaScript and run it instantly.", color = c.onSurfaceVariant, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onEditor, modifier = Modifier.fillMaxWidth()) {
                Text("Open Editor", fontSize = 17.sp)
            }
        }
    }
}

@Composable
fun LearnScreen(onOpen: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(AppData.current()?.level ?: "") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Learn", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("One short lesson at a time.", color = c.onSurfaceVariant, fontSize = 14.sp)
        AppData.levels().forEach { level ->
            val items = AppData.lessons.filter { it.level == level }
            val doneCount = items.count { it.id in AppData.done }
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceVariant)
                    .clickable { expanded = if (expanded == level) "" else level }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(level, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Text("$doneCount / ${items.size}", color = c.onSurfaceVariant)
            }
            if (expanded == level) {
                items.forEach { l ->
                    val isDone = l.id in AppData.done
                    val canOpen = AppData.unlocked(l)
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = canOpen) { onOpen(l.id) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            l.title,
                            fontSize = 17.sp,
                            color = if (canOpen) c.onBackground else c.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (isDone) "✓" else if (canOpen) "Start" else "Locked",
                            color = if (isDone) Good else if (canOpen) c.primary else c.onSurfaceVariant.copy(alpha = 0.6f),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun LessonScreen(lesson: Lesson, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val index = AppData.lessons.indexOf(lesson)
    val next = AppData.lessons.getOrNull(index + 1)
    val solved = lesson.id in AppData.done
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(lesson.level + " · Lesson " + (index + 1), color = c.onSurfaceVariant, fontSize = 13.sp)
        Text(lesson.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        RichText(lesson.text)
        if (lesson.example.isNotEmpty()) {
            Label("EXAMPLE")
            CodeBlock(lesson.example)
        }
        Label("CHALLENGE")
        Panel { RichText(lesson.task) }
        Label("TRY IT")
        Workbench(
            slot = lesson.id,
            initial = lesson.starter,
            checkExpr = lesson.check,
            hintText = lesson.hint,
            solution = lesson.solution,
            clearTo = lesson.starter,
            fill = false
        )
        if (solved) {
            Spacer(Modifier.height(16.dp))
            Text("✓ Lesson completed", color = Good, fontWeight = FontWeight.Bold)
            if (next != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onOpen(next.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Next: " + next.title)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun PlaygroundScreen() {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Code", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Workbench(
            slot = "playground",
            initial = "let name = \"John\";\n\nconsole.log(name);",
            checkExpr = "",
            hintText = "",
            solution = "",
            clearTo = "",
            fill = true,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun ProjectsScreen(onOpen: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Projects", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Build something real, one step at a time.", color = c.onSurfaceVariant, fontSize = 14.sp)
        AppData.projects.map { it.level }.distinct().forEach { level ->
            Label(level.uppercase())
            AppData.projects.filter { it.level == level }.forEach { p ->
                Row(
                    Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(c.surfaceVariant).clickable { onOpen(p.id) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(p.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Open", color = c.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ProjectScreen(project: Lesson, onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(project.level + " project", color = c.onSurfaceVariant, fontSize = 13.sp)
        Text(project.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        RichText(project.text)
        if (project.steps.isNotEmpty()) {
            Label("STEPS")
            Panel {
                project.steps.forEachIndexed { i, s ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text("${i + 1}. ", fontWeight = FontWeight.Bold, color = c.primary)
                        RichText(s)
                    }
                }
            }
        }
        Label("BUILD IT")
        Workbench(
            slot = project.id,
            initial = project.starter,
            checkExpr = "",
            hintText = project.hint,
            solution = "",
            clearTo = project.starter,
            fill = false
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun ProgressScreen() {
    val c = MaterialTheme.colorScheme
    val pct = AppData.percent()
    val cur = AppData.current()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("JavaScript Progress", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Bar(pct / 100f) }
            Text("  $pct%", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        AppData.levels().forEach { level ->
            val items = AppData.lessons.filter { it.level == level }
            val doneCount = items.count { it.id in AppData.done }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(level, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Text("$doneCount / ${items.size}", color = c.onSurfaceVariant)
            }
            if (cur != null && cur.level == level) {
                items.forEach { l ->
                    val isDone = l.id in AppData.done
                    val status = if (isDone) "✓" else if (l.id == cur.id) "In progress" else "Locked"
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(l.title, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(
                            status,
                            color = if (isDone) Good else if (l.id == cur.id) c.primary else c.onSurfaceVariant.copy(alpha = 0.6f),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
