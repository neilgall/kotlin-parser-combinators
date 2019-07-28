import io.kotlintest.matchers.shouldBe
import io.kotlintest.specs.StringSpec

sealed class JSON {
    object Null: JSON()
    data class Bool(val value: Boolean): JSON()
    data class Number(val value: Int): JSON()
    data class String(val value: kotlin.String): JSON()
    data class Array(val value: List<JSON>): JSON()
    data class Object(val value: Map<kotlin.String, JSON>): JSON()
}

class ParsersTests: StringSpec({

    "theLetterA" {
        theLetterA("aaa") shouldBe Result.Ok(Pair(Unit, "aa"))
        theLetterA("baa") shouldBe Result.Err<Pair<Unit, String>>("'a'", "baa")
    }

    "string" {
        val p = string("foo")
        p("foobar") shouldBe Result.Ok(Pair(Unit, "bar"))
        p("barfoo") shouldBe Result.Err<Pair<Unit, String>>("'foo'", "barfoo")
    }

    "integer" {
        integer("123") shouldBe Result.Ok(Pair(123, ""))
        integer("123foo") shouldBe Result.Ok(Pair(123, "foo"))
        integer("foo") shouldBe Result.Err<Pair<Int, String>>("an integer", "foo")
        integer("") shouldBe Result.Err<Pair<Int, String>>("an integer", "")
    }

    "quotedString" {
        quotedString("foo") shouldBe Result.Err<Pair<String, String>>("a quoted string", "foo")
        quotedString("\"foo\"") shouldBe Result.Ok(Pair("foo", ""))
        quotedString("\"foo\"bar") shouldBe Result.Ok(Pair("foo", "bar"))
        quotedString("\"\"") shouldBe Result.Ok(Pair("", ""))
        quotedString("") shouldBe Result.Err<Pair<String, String>>("a quoted string", "")
    }

    "seq" {
        seq(::theLetterA, ::theLetterA)("aab") shouldBe Result.Ok(Pair(Pair(Unit, Unit), "b"))
    }

    "choice" {
        val p = choice(
            string("a").retn(1),
            string("b").retn(2)
        )
        p("a") shouldBe Result.Ok(Pair(1, ""))
        p("b") shouldBe Result.Ok(Pair(2, ""))
        p("c") shouldBe Result.Err<Pair<Int, String>>("'a' or 'b'", "c")
    }

    "before" {
        val foo = string("foo").retn(1)
        val bar = string("bar").retn(2)
        foo.before(bar)("foobar") shouldBe Result.Ok(Pair(2, ""))
    }

    "then" {
        val foo = string("foo").retn(1)
        val bar = string("bar").retn(2)
        foo.then(bar)("foobar") shouldBe Result.Ok(Pair(1, ""))
    }

    "between" {
        val p = ::integer.between(string("["), string("]"))
        p("[123]") shouldBe Result.Ok(Pair(123, ""))
        p("[123]foo") shouldBe Result.Ok(Pair(123, "foo"))
    }

    "many" {
        ::theLetterA.many()("aaa") shouldBe Result.Ok(Pair(listOf(Unit, Unit, Unit), ""))
        ::theLetterA.many()("aaab") shouldBe Result.Ok(Pair(listOf(Unit, Unit, Unit), "b"))
        ::theLetterA.many()("") shouldBe Result.Ok(Pair(listOf<Unit>(), ""))
        ::theLetterA.many()("bbb") shouldBe Result.Ok(Pair(listOf<Unit>(), "bbb"))
    }

    "sepBy" {
        ::integer.sepBy(string(","))("1,2,3,4") shouldBe Result.Ok(Pair(listOf(1,2,3,4), ""))
        ::integer.sepBy(string(","))("1,2,3,4x") shouldBe Result.Ok(Pair(listOf(1,2,3,4), "x"))
    }

    "json" {
        val jsonParserRef = ParserRef<JSON>()

        val jsonNull: Parser<JSON> = string("null").retn(JSON.Null)
        val jsonTrue: Parser<JSON> = string("true").retn(JSON.Bool(true))
        val jsonFalse: Parser<JSON> = string("false").retn(JSON.Bool(false))
        val jsonNumber: Parser<JSON> = ::integer.map(JSON::Number)
        val jsonString: Parser<JSON> = ::quotedString.map(JSON::String)

        fun token(s: String): Parser<Unit> = string(s).between(::whitespace, ::whitespace)

        val jsonArray: Parser<JSON> = jsonParserRef.get()
            .sepBy(token(","))
            .between(token("["), token("]"))
            .map(JSON::Array)

        val jsonObject: Parser<JSON> =
            (::quotedString.then(token(":")) and jsonParserRef.get())
            .sepBy(token(","))
            .between(token("{"), token("}"))
            .map { pairs -> JSON.Object(pairs.toMap()) }

        val json = (jsonNull or jsonTrue or jsonFalse or jsonNumber or jsonString or jsonArray or jsonObject)
            .apply(jsonParserRef::set)

        json("""{
            "foo": "bar",
            "number": 123,
            "array": [1,2,3],
            "objs": [{"a": true}, {"b": false}],
            "nothing": null
        }""") shouldBe Result.Ok(Pair(
            JSON.Object(mapOf(
                "foo" to JSON.String("bar"),
                "number" to JSON.Number(123),
                "array" to JSON.Array(listOf(JSON.Number(1), JSON.Number(2), JSON.Number(3))),
                "objs" to JSON.Array(listOf(
                    JSON.Object(mapOf("a" to JSON.Bool(true))),
                    JSON.Object(mapOf("b" to JSON.Bool(false)))
                )),
                "nothing" to JSON.Null
            )),
            ""
        ))
    }
})