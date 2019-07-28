sealed class Result<T> {
    data class Ok<T>(val value: T) : Result<T>()
    data class Err<T>(val expected: String, val actual: String) : Result<T>()

    fun mapExpected(f: (String) -> String): Result<T> = when (this) {
        is Ok -> this
        is Err -> Err(f(expected), actual)
    }
}

typealias ParseResult<T> = Result<Pair<T, String>>
typealias Parser<T> = (String) -> ParseResult<T>

fun theLetterA(input: String): ParseResult<Unit> =
    if (input.isNotEmpty() && input[0] == 'a')
        Result.Ok(Pair(Unit, input.substring(1)))
    else
        Result.Err("'a'", input)

fun string(s: String): Parser<Unit> = { input ->
    if (input.isNotEmpty() && input.substring(0 until s.length) == s)
        Result.Ok(Pair(Unit, input.substring(s.length)))
    else
        Result.Err("'$s'", input)
}

fun integer(input: String): ParseResult<Int> {
    if (input.isEmpty() || !input[0].isDigit()) {
        return Result.Err("an integer", input)
    } else {
        var value = 0
        for (i in 0 until input.length) {
            if (input[i].isDigit()) {
                value = (value * 10) + (input[i] - '0')
            } else {
                return Result.Ok(Pair(value, input.substring(i)))
            }
        }
        return Result.Ok(Pair(value, ""))
    }
}

fun quotedString(input: String): ParseResult<String> {
    if (input.isEmpty() || input[0] != '"') {
        return Result.Err("a quoted string", input)
    }

    var escaped = false
    var string = ""
    for (i in 1 until input.length) {
        when (val c = input[i]) {
            '"' -> {
                if (!escaped) return Result.Ok(Pair(string, input.substring(i + 1)))
                string += c
                escaped = false
            }
            '\\' -> {
                if (escaped) string += '\\'
                escaped = !escaped
            }
            else -> {
                if (escaped) string += '\\'
                escaped = false
                string += c
            }
        }
    }

    return Result.Err("a terminated quoted string",input)
}

fun <T, U> ParseResult<T>.map(f: (T) -> U): ParseResult<U> = when (this) {
    is Result.Err -> Result.Err(expected, actual)
    is Result.Ok -> Result.Ok(Pair(f(value.first), value.second))
}

fun <T, U> ParseResult<T>.flatMap(f: (T, String) -> ParseResult<U>): ParseResult<U> = when (this) {
    is Result.Err -> Result.Err(expected, actual)
    is Result.Ok -> f(value.first, value.second)
}

fun <T, U> Parser<T>.map(f: (T) -> U): Parser<U> = { input ->
    this@map(input).map(f)
}

fun <T, U> Parser<T>.retn(value: U): Parser<U> = { input ->
    this@retn(input).map { value }
}

fun <P1, P2> seq(p1: Parser<P1>, p2: Parser<P2>): Parser<Pair<P1, P2>> = { input ->
    p1(input).flatMap { r1, rest1 ->
        p2(rest1).map { r2 -> Pair(r1, r2) }
    }
}

fun <P> choice(p1: Parser<P>, p2: Parser<P>): Parser<P> = { input ->
    when (val r1 = p1(input)) {
        is Result.Ok -> r1
        is Result.Err -> p2(input).mapExpected { e2 -> "${r1.expected} or $e2" }
    }
}

fun <X, T> Parser<X>.before(p: Parser<T>): Parser<T> = seq(this@before, p).map { it.second }
fun <T, X> Parser<T>.then(p: Parser<X>): Parser<T> = seq(this@then, p).map { it.first }

fun <T, X, Y> Parser<T>.between(p1: Parser<X>, p2: Parser<Y>): Parser<T> =
    p1.before(this).then(p2)

fun <T> Parser<T>.many(): Parser<List<T>> = { input ->
    val list = mutableListOf<T>()
    var remaining = input
    while (remaining.isNotEmpty()) {
        val r = this@many(remaining)
        if (r is Result.Ok) {
            list += r.value.first
            remaining = r.value.second
        } else
            break
    }
    Result.Ok(Pair(list, remaining))
}

fun <T, X> Parser<T>.sepBy(sep: Parser<X>): Parser<List<T>> = { input ->
    val list = mutableListOf<T>()
    var remaining = input
    while (remaining.isNotEmpty()) {
        val r = this@sepBy(remaining)
        if (r is Result.Ok) {
            list += r.value.first
            remaining = r.value.second
            val s = sep(remaining)
            if (s is Result.Ok)
                remaining = s.value.second
            else
                break
        } else
            break
    }
    Result.Ok(Pair(list, remaining))
}

fun whitespace(input: String): ParseResult<Unit> {
    for (i in 0 until input.length) {
        if (!input[i].isWhitespace())
            return Result.Ok(Pair(Unit, input.substring(i)))
    }
    return Result.Ok(Pair(Unit, input))
}

class ParserRef<T> {
    lateinit var p: Parser<T>
    fun set(p: Parser<T>) { this.p = p }
    fun get(): Parser<T> = { input -> p(input) }
}

infix fun <T> Parser<T>.or(p: Parser<T>): Parser<T> = choice(this, p)
infix fun <T, U> Parser<T>.and(p: Parser<U>): Parser<Pair<T, U>> = seq(this, p)