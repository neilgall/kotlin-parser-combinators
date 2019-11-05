sealed class Result<In, Out> {
    data class Ok<In, Out>(val value: Out, val remaining: In) : Result<In, Out>()
    data class Err<In, Out>(val expected: String, val actual: In) : Result<In, Out>()

    fun mapExpected(f: (String) -> String): Result<In, Out> = when (this) {
        is Ok -> this
        is Err -> Err(f(expected), actual)
    }
}

typealias Parser<In, Out> = (In) -> Result<In, Out>

fun theLetterA(input: String): Result<String, Unit> =
    if (input.isNotEmpty() && input[0] == 'a')
        Result.Ok(Unit, input.substring(1))
    else
        Result.Err("'a'", input)

fun string(s: String): Parser<String, Unit> = { input ->
    if (input.isNotEmpty() && input.substring(0 until s.length) == s)
        Result.Ok(Unit, input.substring(s.length))
    else
        Result.Err("'$s'", input)
}

fun integer(input: String): Result<String, Int> {
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

fun quotedString(input: String): Result<String, String> {
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

fun <In, Out, Out2> Result<In, Out>.map(f: (Out) -> Out2): Result<In, Out2> = when (this) {
    is Result.Err -> Result.Err(expected, actual)
    is Result.Ok -> Result.Ok(f(value), remaining)
}

fun <In, Out, Out2> Result<In, Out>.flatMap(f: (Out, In) -> Result<In, Out2>): Result<In, Out2> = when (this) {
    is Result.Err -> Result.Err(expected, actual)
    is Result.Ok -> f(value, remaining)
}

fun <In, Out, Out2> Parser<In, Out>.map(f: (Out) -> Out2): Parser<In, Out2> = { input ->
    this@map(input).map(f)
}

fun <In, Out, Out2> Parser<In, Out>.flatMap(f: (Out) -> Parser<In, Out2>): Parser<In, Out2> = { input ->
    this@flatMap(input).flatMap { r1, rest -> f(r1)(rest) }
}

infix fun <In, X, Out> Parser<In, X>.meaning(value: Out): Parser<In, Out> = { input ->
    this@meaning(input).map { value }
}

infix fun <In, P1, P2> Parser<In, P1>.then(p2: Parser<In, P2>): Parser<In, Pair<P1, P2>> = { input ->
    this@then(input).flatMap { r1, rest1 ->
        p2(rest1).map { r2 -> Pair(r1, r2) }
    }
}

infix fun <In, P1, P2> Parser<In, P1>.thenUnrolled(p2: Parser<In, P2>): Parser<In, Pair<P1, P2>> = { input ->
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

infix fun <In, Out> Parser<In, Out>.or(p2: Parser<In, Out>): Parser<In, Out> = { input ->
    when (val r1 = this@or(input)) {
        is Result.Ok -> r1
        is Result.Err -> p2(input).mapExpected { e2 -> "${r1.expected} or $e2" }
    }
}

infix fun <In, X, Out> Parser<In, X>.before(p: Parser<In, Out>): Parser<In, Out> =
    (this@before then p).map { it.second }

infix fun <In, Out, X> Parser<In, Out>.followedBy(p: Parser<In, X>): Parser<In, Out> =
    (this@followedBy then p).map { it.first }

fun <In, Out, X, Y> Parser<In, Out>.between(p1: Parser<In, X>, p2: Parser<In, Y>): Parser<In, Out> =
    p1 before this followedBy p2

operator fun <In, Out> Parser<In, Out>.times(count: Int): Parser<In, List<Out>> = { input ->
    val list = mutableListOf<Out>()
    var remaining = input
    var err: Result<In, List<Out>>? = null
    loop@ for (i in 1..count) {
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

private fun <In, Out> Parser<In, Out>.many(notEmpty: (In) -> Boolean): Parser<In, List<Out>> = { input ->
    val list = mutableListOf<Out>()
    var remaining = input
    while (notEmpty(remaining)) {
        val r = this@many(remaining)
        if (r is Result.Ok) {
            list += r.value
            remaining = r.remaining
        } else
            break
    }
    Result.Ok(list, remaining)
}

fun <In, Out> Parser<In, Out>.many(): Parser<In, List<Out>> where In : CharSequence =
    many(CharSequence::isNotEmpty)

@JvmName("collectionMany")
fun <In, Out> Parser<In, Out>.many(): Parser<In, List<Out>> where In : Collection<*> =
    many { it.isNotEmpty() }


private fun <In, Out> Parser<In, Out>.atLeastOne(notEmpty: (In) -> Boolean): Parser<In, List<Out>> = { input ->
    when (val r = this@atLeastOne(input)) {
        is Result.Err ->
            r.map(::listOf).mapExpected { e -> "at least one $e" }
        is Result.Ok -> {
            val list = mutableListOf(r.value)
            var remaining = r.remaining
            while (notEmpty(remaining)) {
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

fun <In, Out> Parser<In, Out>.atLeastOne(): Parser<In, List<Out>> where In : CharSequence =
    atLeastOne(CharSequence::isNotEmpty)

@JvmName("collectionAtLeastOne")
fun <In, Out> Parser<In, Out>.atLeastOne(): Parser<In, List<Out>> where In : Collection<*> =
    atLeastOne { it.isNotEmpty() }


private fun <In, Out, Sep> Parser<In, Out>.separatedBy(sep: Parser<In, Sep>, notEmpty: (In) -> Boolean)
        : Parser<In, List<Out>> = { input ->
    val list = mutableListOf<Out>()
    var remaining = input
    while (notEmpty(remaining)) {
        val r = this@separatedBy(remaining)
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

infix fun <In, Out, Sep> Parser<In, Out>.separatedBy(sep: Parser<In, Sep>): Parser<In, List<Out>> where In: CharSequence =
    separatedBy(sep, CharSequence::isNotEmpty)

@JvmName("collectionSeparatedBy")
infix fun <In, Out, Sep> Parser<In, Out>.separatedBy(sep: Parser<In, Sep>): Parser<In, List<Out>> where In: Collection<*> =
    separatedBy(sep) { it.isNotEmpty() }


fun whitespace(input: String): Result<String, Unit> {
    for (i in input.indices) {
        if (!input[i].isWhitespace())
            return Result.Ok(Unit, input.substring(i))
    }
    return Result.Ok(Unit, input)
}

fun nonWhitespace(input: String): Result<String, String> {
    for (i in input.indices) {
        if (input[i].isWhitespace())
            return Result.Ok(input.substring(0, i), input.substring(i))
    }
    return Result.Ok("", input)
}

class ParserRef<In, Out> {
    lateinit var p: Parser<In, Out>
    fun set(p: Parser<In, Out>) {
        this.p = p
    }

    fun get(): Parser<In, Out> = { input -> p(input) }
}
