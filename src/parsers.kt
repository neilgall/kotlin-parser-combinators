/**
 * Parser Combinators in Kotlin
 *
 * Neil Gall
 * https://github.com/neilgall/kotlin-parser-combinators
 * https://twitter.com/neilgall
 */

sealed class Result<out T> {
    data class Ok<T>(val value: T, val rest: String) : Result<T>()
    data class Err(val expected: String, val input: String) : Result<Nothing>()

    fun <U> map(f: (T) -> U): Result<U> = when(this) {
        is Err -> this
        is Ok -> Ok(f(value), rest)
    }

    fun <U> flatMap(f: (T, String) -> Result<U>): Result<U> = when(this) {
        is Err -> this
        is Ok -> f(value, rest)
    }

    fun mapExpected(f: (String) -> String): Result<T> = when (this) {
        is Ok -> this
        is Err -> Err(f(expected), input)
    }
}

typealias Parser<T> = (String) -> Result<T>

fun dot(input: String): Result<Unit> =
    if (input.startsWith("."))
        Result.Ok(Unit, input.substring(1))
    else
        Result.Err("a dot", input)


fun integer(input: String): Result<Int> {
    if (input.isEmpty() || !input[0].isDigit()) {
        return Result.Err("an integer", input)
    } else {
        var value = 0
        for (i in input.indices) {
            if (input[i].isDigit()) {
                value = (value * 10) + (input[i] - '0')
            } else {
                return Result.Ok(value, input.substring(i))
            }
        }
        return Result.Ok(value, "")
    }
}


fun string(s: String): Parser<Unit> = { input ->
    if (input.startsWith(s))
        Result.Ok(Unit, input.substring(s.length))
    else
        Result.Err("'$s'", input)
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
                if (escaped) string += '\\'
                escaped = false
                string += c
            }
        }
    }

    return Result.Err("a terminated quoted string", input)
}

fun whitespace(input: String): Result<Unit> {
    for (i in input.indices) {
        if (!input[i].isWhitespace())
            return Result.Ok(Unit, input.substring(i))
    }
    return Result.Ok(Unit, input)
}


fun <T1, T2> seq(p1: Parser<T1>, p2: Parser<T2>): Parser<Pair<T1, T2>> = { input ->
    p1(input).flatMap { r1, rest ->
        p2(rest).map { r2 -> Pair(r1, r2) }
    }
}

fun <T> choice(p1: Parser<T>, p2: Parser<T>): Parser<T> = { input ->
    when (val r1 = p1(input)) {
        is Result.Ok -> r1
        is Result.Err -> p2(input).mapExpected { e -> "${r1.expected} or $e" }
    }
}

fun <T, U> Parser<T>.map(f: (T) -> U): Parser<U> = { input ->
    this(input).map(f)
}

fun <T, U> Parser<T>.means(u: U): Parser<U> = map { u }

infix fun <T1, T2> Parser<T1>.then(p2: Parser<T2>): Parser<Pair<T1, T2>> =
    seq(this, p2)

infix fun <T> Parser<T>.or(p2: Parser<T>): Parser<T> =
    choice(this, p2)


fun <X, T> Parser<X>.before(p: Parser<T>): Parser<T> =
    seq(this, p).map { it.second }

fun <T, Y> Parser<T>.followedBy(y: Parser<Y>): Parser<T> =
    seq(this, y).map { it.first }

fun <X, T, Y> Parser<T>.between(x: Parser<X>, y: Parser<Y>): Parser<T> =
    x.before(this).followedBy(y)

fun <T> List<T>.prepend(t: T): List<T> = listOf(t) + this

fun <T> Parser<T>.many(): Parser<List<T>> = { input ->
    when (val r = this(input)) {
        is Result.Err -> Result.Ok(emptyList(), input)
        is Result.Ok -> this.many()(r.rest).map { it.prepend(r.value) }
    }
}

fun <T, X> Parser<T>.sepBy(sep: Parser<X>): Parser<List<T>> = { input ->
    fun tailParse(tail: String): Result<List<T>> =
        when (val s = sep(tail)) {
            is Result.Err -> Result.Ok(emptyList(), tail)
            is Result.Ok -> when (val r = this(s.rest)) {
                is Result.Err -> r
                is Result.Ok -> tailParse(r.rest).map { it.prepend(r.value) }
            }
        }

    when (val r = this(input)) {
        is Result.Err -> Result.Ok(emptyList(), input)
        is Result.Ok -> tailParse(r.rest).map { it.prepend(r.value) }
    }
}


class ParserRef<T>: Parser<T> {
    lateinit var p: Parser<T>
    fun set(p: Parser<T>) { this.p = p }
    override fun invoke(input: String): Result<T> = p(input)
}