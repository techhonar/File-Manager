package com.filemanager.app.data.editor

/** What a stretch of code is, for colouring it. */
enum class TokenKind { KEYWORD, STRING, COMMENT, NUMBER, TAG, ATTRIBUTE, HEADING, EMPHASIS, CODE }

data class Token(val start: Int, val end: Int, val kind: TokenKind)

/**
 * Colouring for code, by the file's extension.
 *
 * Patterns rather than a parser: enough to pick out comments, strings,
 * numbers and keywords as an editor on a phone needs, cheap enough to run on
 * every keystroke. Where a language is more than its patterns - a string
 * inside a comment, say - the earlier kind in the list wins.
 */
enum class Language(private val rules: List<Pair<TokenKind, String>>, ignoreCase: Boolean = false) {
    C_FAMILY(
        listOf(
            TokenKind.COMMENT to """//[^\n]*|/\*[\s\S]*?\*/""",
            TokenKind.STRING to """"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\]|\\.)*`""",
            TokenKind.ATTRIBUTE to """@[A-Za-z_][\w.]*""",
            TokenKind.NUMBER to NUMBER,
            TokenKind.KEYWORD to words(
                "abstract as async await break case catch class const continue default defer delete do " +
                    "else enum export extends false final finally fn for fun func function go if impl " +
                    "implements import in interface internal is let loop match mod module mut namespace new " +
                    "null object open operator override package private protected pub public return self " +
                    "sealed static struct super switch this throw throws trait true try type typeof " +
                    "undefined use val var void when where while with yield " +
                    "int long short byte char float double boolean bool string auto unsigned signed " +
                    "include define ifdef ifndef endif nil lateinit data inline suspend",
            ),
        ),
    ),
    PYTHON(
        listOf(
            TokenKind.STRING to """""${'"'}[\s\S]*?""${'"'}|'''[\s\S]*?'''""",
            TokenKind.COMMENT to """#[^\n]*""",
            TokenKind.STRING to """"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'""",
            TokenKind.ATTRIBUTE to """@[A-Za-z_][\w.]*""",
            TokenKind.NUMBER to NUMBER,
            TokenKind.KEYWORD to words(
                "and as assert async await begin break class continue def del do elif else elsif end " +
                    "ensure except False finally for from global if import in is lambda module next nil " +
                    "None nonlocal not or pass raise rescue return self then True try unless until while " +
                    "with yield",
            ),
        ),
    ),
    SHELL(
        listOf(
            TokenKind.COMMENT to """#[^\n]*|(?i:^[ \t]*rem\b[^\n]*)|^[ \t]*::[^\n]*""",
            TokenKind.STRING to """"(?:[^"\\]|\\.)*"|'[^']*'""",
            TokenKind.ATTRIBUTE to """\$\{?[\w@#?*!-]+\}?|%\w+%""",
            TokenKind.NUMBER to NUMBER,
            TokenKind.KEYWORD to words(
                "if then else elif fi for while until do done case esac in function return local export " +
                    "readonly echo exit set unset source alias break continue shift true false " +
                    "param foreach switch goto call",
            ),
        ),
    ),
    CONFIG(
        listOf(
            TokenKind.COMMENT to """#[^\n]*|^[ \t]*;[^\n]*""",
            TokenKind.KEYWORD to """^[ \t]*\[[^\]\n]*\][ \t]*$""",
            TokenKind.ATTRIBUTE to """^[ \t]*(?:-[ \t]*)?[\w."'-]+(?=[ \t]*[=:])""",
            TokenKind.STRING to """"(?:[^"\\\n]|\\.)*"|'[^'\n]*'""",
            TokenKind.NUMBER to NUMBER,
            TokenKind.KEYWORD to words("true false yes no on off null"),
        ),
    ),
    JSON(
        listOf(
            TokenKind.ATTRIBUTE to """"(?:[^"\\\n]|\\.)*"(?=\s*:)""",
            TokenKind.STRING to """"(?:[^"\\\n]|\\.)*"""",
            TokenKind.NUMBER to """-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b""",
            TokenKind.KEYWORD to words("true false null"),
        ),
    ),
    XML(
        listOf(
            TokenKind.COMMENT to """<!--[\s\S]*?-->""",
            TokenKind.TAG to """</?[\w:.-]+|/?>|<\?[\w:.-]*|\?>""",
            TokenKind.ATTRIBUTE to """\b[\w:.-]+(?==)""",
            TokenKind.STRING to """"[^"\n]*"|'[^'\n]*'""",
        ),
    ),
    MARKDOWN(
        listOf(
            TokenKind.CODE to """```[\s\S]*?```|`[^`\n]+`""",
            TokenKind.HEADING to """^#{1,6}[ \t][^\n]*""",
            TokenKind.COMMENT to """^>[^\n]*""",
            TokenKind.EMPHASIS to """\*\*[^*\n]+\*\*|__[^_\n]+__|\*[^*\n]+\*|\b_[^_\n]+_\b""",
            TokenKind.ATTRIBUTE to """\[[^\]\n]*\]\([^)\n]*\)""",
            TokenKind.KEYWORD to """^[ \t]*(?:[-*+]|\d+\.)(?=[ \t])""",
        ),
    ),
    SQL(
        listOf(
            TokenKind.COMMENT to """--[^\n]*|/\*[\s\S]*?\*/""",
            TokenKind.STRING to """'(?:[^']|'')*'|"[^"\n]*"""",
            TokenKind.NUMBER to NUMBER,
            TokenKind.KEYWORD to words(
                "select from where and or not insert into values update set delete create table drop " +
                    "alter add index view join inner left right outer full on as order by group having " +
                    "limit offset distinct union all is null like in between case when then else end " +
                    "primary key foreign references default unique exists count sum avg min max",
            ),
        ),
        ignoreCase = true,
    ),
    CSS(
        listOf(
            TokenKind.COMMENT to """/\*[\s\S]*?\*/""",
            TokenKind.STRING to """"[^"\n]*"|'[^'\n]*'""",
            TokenKind.KEYWORD to """@[\w-]+|!important""",
            TokenKind.ATTRIBUTE to """[\w-]+(?=\s*:[^:])""",
            TokenKind.NUMBER to """#[0-9a-fA-F]{3,8}\b|-?\b\d+(?:\.\d+)?(?:px|em|rem|%|vh|vw|s|ms|deg|fr)?\b""",
        ),
    ),
    ;

    private val pattern: Regex = Regex(
        rules.joinToString("|") { "(${it.second})" },
        buildSet {
            add(RegexOption.MULTILINE)
            if (ignoreCase) add(RegexOption.IGNORE_CASE)
        },
    )

    /**
     * The coloured stretches of [text], in order. Stops at [maxTokens]: the
     * rest of a huge file stays plain rather than slowing every keystroke.
     */
    fun tokens(text: CharSequence, maxTokens: Int = 50_000): List<Token> {
        val tokens = ArrayList<Token>()
        for (match in pattern.findAll(text)) {
            if (match.range.isEmpty()) continue
            // The first group that took part is the rule that matched.
            val rule = (1..rules.size).firstOrNull { match.groups[it] != null } ?: continue
            tokens += Token(match.range.first, match.range.last + 1, rules[rule - 1].first)
            if (tokens.size >= maxTokens) break
        }
        return tokens
    }

    companion object {
        /** The language a file of this name is written in, if it is one coloured. */
        fun of(fileName: String): Language? = when (fileName.substringAfterLast('.', "").lowercase()) {
            "kt", "kts", "java", "gradle", "js", "mjs", "ts", "c", "h", "cc", "cpp", "hpp", "cs",
            "swift", "go", "rs", "php" -> C_FAMILY
            "py", "rb" -> PYTHON
            "sh", "bash", "bat", "ps1" -> SHELL
            "yml", "yaml", "toml", "ini", "cfg", "conf", "properties", "env", "gitignore" -> CONFIG
            "json" -> JSON
            "xml" -> XML
            "md", "markdown" -> MARKDOWN
            "sql" -> SQL
            "css" -> CSS
            else -> null
        }
    }
}

private const val NUMBER = """\b(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?)[fFdDlLuU]*\b"""

private fun words(list: String) = """\b(?:""" + list.trim().split(Regex("\\s+")).joinToString("|") + """)\b"""
