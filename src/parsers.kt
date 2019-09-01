sealed class Result<T> {
    data class Ok<T>(val value: T, val remaining: String) : Result<T>()
    data class Err<T>(val expected: String, val actual: String) : Result<T>()

    fun mapExpected(f: (String) -> String): Result<T> = when (this) {
        is Ok -> this
        is Err -> Err(f(expected), actual)
    }
}

typealias Parser<T> = (String) -> Result<T>

fun theLetterA(input: String): Result<Unit> =
    if (input.isNotEmpty() && input[0] == 'a')
        Result.Ok(Unit, input.substring(1))
    else
        Result.Err("'a'", input)

fun string(s: String): Parser<Unit> = { input ->
    if (input.isNotEmpty() && input.substring(0 until s.length) == s)
        Result.Ok(Unit, input.substring(s.length))
    else
        Result.Err("'$s'", input)
}

fun integer(input: String): Result<Int> {
    if (input.isEmpty() || !input[0].isDigit()) {
        return Result.Err("an integer", input)
    } else {
        var value = 0
        for (i in 0 until input.length) {
            if (input[i].isDigit()) {
                value = (value * 10) + (input[i] - '0')
            } else {
                return Result.Ok(value, input.substring(i))
            }
        }
        return Result.Ok(value, "")
    }
}

fun quotedString(input: String): Result<String> {
    if (input.isEmpty() || input[0] != '"') {
        return Result.Err("a quoted string", input)
    }

    var escaped = false
    var string = ""
    for (i in 1 until input.length) {
        val c = input[i]
        when {
            c == '"' && !escaped ->
                return Result.Ok(string, input.substring(i + 1))
            c == '\\' && !escaped ->
                escaped = true
            else -> {
                if (escaped && c != '\\') string += '\\'
                escaped = false
                string += c
            }
        }
    }

    return Result.Err("a terminated quoted string", input)
}

fun <T, U> Result<T>.map(f: (T) -> U): Result<U> = when (this) {
    is Result.Err -> Result.Err(expected, actual)
    is Result.Ok -> Result.Ok(f(value), remaining)
}

fun <T, U> Result<T>.flatMap(f: (T, String) -> Result<U>): Result<U> = when (this) {
    is Result.Err -> Result.Err(expected, actual)
    is Result.Ok -> f(value, remaining)
}

fun <T, U> Parser<T>.map(f: (T) -> U): Parser<U> = { input ->
    this@map(input).map(f)
}

fun <T, U> Parser<T>.flatMap(f: (T) -> Parser<U>): Parser<U> = { input ->
    this@flatMap(input).flatMap { r1, rest -> f(r1)(rest) }
}

infix fun <T, U> Parser<T>.meaning(value: U): Parser<U> = { input ->
    this@meaning(input).map { value }
}

infix fun <P1, P2> Parser<P1>.then(p2: Parser<P2>): Parser<Pair<P1, P2>> = { input ->
    this@then(input).flatMap { r1, rest1 ->
        p2(rest1).map { r2 -> Pair(r1, r2) }
    }
}

infix fun <P1, P2> Parser<P1>.thenUnrolled(p2: Parser<P2>): Parser<Pair<P1, P2>> = { input ->
    when (val r1 = this@thenUnrolled(input)) {
        is Result.Err -> Result.Err(r1.expected, r1.actual)
        is Result.Ok -> {
            when (val r2 = p2(r1.remaining)) {
                is Result.Err -> Result.Err(r2.expected, r2.actual)
                is Result.Ok -> Result.Ok(Pair(r1.value, r2.value), r2.remaining)
            }
        }
    }
}

infix fun <P> Parser<P>.or(p2: Parser<P>): Parser<P> = { input ->
    when (val r1 = this@or(input)) {
        is Result.Ok -> r1
        is Result.Err -> p2(input).mapExpected { e2 -> "${r1.expected} or $e2" }
    }
}

infix fun <X, T> Parser<X>.before(p: Parser<T>): Parser<T> = (this@before then p).map { it.second }
infix fun <T, X> Parser<T>.followedBy(p: Parser<X>): Parser<T> = (this@followedBy then p).map { it.first }

fun <T, X, Y> Parser<T>.between(p1: Parser<X>, p2: Parser<Y>): Parser<T> =
    p1 before this followedBy p2

operator fun <T> Parser<T>.times(count: Int): Parser<List<T>> = { input ->
    val list = mutableListOf<T>()
    var remaining = input
    var err: Result<List<T>>? = null
    loop@for (i in 1..count) {
        when (val r = this@times(remaining)) {
            is Result.Ok -> {
                list += r.value
                remaining = r.remaining
            }
            is Result.Err -> {
                err = r.map { listOf(it) }.mapExpected { "$count times $it" }
                break@loop
            }
        }
    }
    err ?: Result.Ok(list.toList(), remaining)
}

fun <T> Parser<T>.many(): Parser<List<T>> = { input ->
    val list = mutableListOf<T>()
    var remaining = input
    while (remaining.isNotEmpty()) {
        val r = this@many(remaining)
        if (r is Result.Ok) {
            list += r.value
            remaining = r.remaining
        } else
            break
    }
    Result.Ok(list, remaining)
}

fun <T> Parser<T>.atLeastOne(): Parser<List<T>> = { input ->
    when (val r = this@atLeastOne(input)) {
        is Result.Err ->
            r.map(::listOf).mapExpected { e -> "at least one $e" }
        is Result.Ok -> {
            val list = mutableListOf(r.value)
            var remaining = r.remaining
            while (remaining.isNotEmpty()) {
                val r = this@atLeastOne(remaining)
                if (r is Result.Ok) {
                    list += r.value
                    remaining = r.remaining
                } else
                    break
            }
            Result.Ok(list, remaining)
        }
    }
}

infix fun <T, X> Parser<T>.sepBy(sep: Parser<X>): Parser<List<T>> = { input ->
    val list = mutableListOf<T>()
    var remaining = input
    while (remaining.isNotEmpty()) {
        val r = this@sepBy(remaining)
        if (r is Result.Ok) {
            list += r.value
            remaining = r.remaining
            val s = sep(remaining)
            if (s is Result.Ok)
                remaining = s.remaining
            else
                break
        } else
            break
    }
    Result.Ok(list, remaining)
}

fun whitespace(input: String): Result<Unit> {
    for (i in 0 until input.length) {
        if (!input[i].isWhitespace())
            return Result.Ok(Unit, input.substring(i))
    }
    return Result.Ok(Unit, input)
}

class ParserRef<T> {
    lateinit var p: Parser<T>
    fun set(p: Parser<T>) {
        this.p = p
    }

    fun get(): Parser<T> = { input -> p(input) }
}
