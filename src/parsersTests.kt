import io.kotlintest.matchers.shouldBe
import io.kotlintest.specs.StringSpec

sealed class JSON {
    object Null : JSON()
    data class Bool(val value: Boolean) : JSON()
    data class Number(val value: Int) : JSON()
    data class String(val value: kotlin.String) : JSON()
    data class Array(val value: List<JSON>) : JSON()
    data class Object(val value: Map<kotlin.String, JSON>) : JSON()
}

class ParsersTests : StringSpec({

    "theLetterA" {
        theLetterA("aaa") shouldBe Result.Ok(Unit, "aa")
        theLetterA("baa") shouldBe Result.Err<String, Pair<Unit, String>>("'a'", "baa")
    }

    "string" {
        val p = string("foo")
        p("foobar") shouldBe Result.Ok(Unit, "bar")
        p("barfoo") shouldBe Result.Err<String, Pair<Unit, String>>("'foo'", "barfoo")
    }

    "integer" {
        integer("123") shouldBe Result.Ok(123, "")
        integer("123foo") shouldBe Result.Ok(123, "foo")
        integer("foo") shouldBe Result.Err<String, Pair<Int, String>>("an integer", "foo")
        integer("") shouldBe Result.Err<String, Pair<Int, String>>("an integer", "")
    }

    "quotedString" {
        quotedString("foo") shouldBe Result.Err<String, Pair<String, String>>("a quoted string", "foo")
        quotedString("\"foo\"") shouldBe Result.Ok("foo", "")
        quotedString("\"foo\"bar") shouldBe Result.Ok("foo", "bar")
        quotedString("\"\"") shouldBe Result.Ok("", "")
        quotedString("") shouldBe Result.Err<String, Pair<String, String>>("a quoted string", "")
    }

    "then" {
        val p = ::theLetterA then ::theLetterA
        p("aab") shouldBe Result.Ok(Pair(Unit, Unit), "b")
    }

    "choice" {
        val p = string("a").meaning(1) or string("b").meaning(2)
        p("a") shouldBe Result.Ok(1, "")
        p("b") shouldBe Result.Ok(2, "")
        p("c") shouldBe Result.Err<String, Pair<Int, String>>("'a' or 'b'", "c")
    }

    "before" {
        val foo = string("foo") meaning 1
        val bar = string("bar") meaning 2
        (foo before bar)("foobar") shouldBe Result.Ok(2, "")
    }

    "followedBy" {
        val foo = string("foo") meaning 1
        val bar = string("bar") meaning 2
        (foo followedBy bar)("foobar") shouldBe Result.Ok(1, "")
    }

    "between" {
        val p = ::integer.between(string("["), string("]"))
        p("[123]") shouldBe Result.Ok(123, "")
        p("[123]foo") shouldBe Result.Ok(123, "foo")
    }

    "many" {
        ::theLetterA.many()("aaa") shouldBe Result.Ok(listOf(Unit, Unit, Unit), "")
        ::theLetterA.many()("aaab") shouldBe Result.Ok(listOf(Unit, Unit, Unit), "b")
        ::theLetterA.many()("") shouldBe Result.Ok(listOf<Unit>(), "")
        ::theLetterA.many()("bbb") shouldBe Result.Ok(listOf<Unit>(), "bbb")
    }

    "sepBy" {
        (::integer separatedBy string(","))("1,2,3,4") shouldBe Result.Ok(listOf(1, 2, 3, 4), "")
        (::integer separatedBy string(","))("1,2,3,4x") shouldBe Result.Ok(listOf(1, 2, 3, 4), "x")
    }

    "flatMap" {
        val counted = (::integer followedBy string(":")).flatMap { count ->
            (::integer followedBy ::whitespace) * count
        }

        counted("3:4 5 6") shouldBe Result.Ok(listOf(4,5,6), "")
        counted("3:4 5 6 7") shouldBe Result.Ok(listOf(4,5,6), "7")
        counted("3:4 5 foo") shouldBe Result.Err<String, List<Int>>("3 times an integer", "foo")
    }

    "json" {
        val jsonParserRef = ParserRef<String, JSON>()

        val jsonNull: Parser<String, JSON> = string("null") meaning JSON.Null
        val jsonTrue: Parser<String, JSON> = string("true") meaning JSON.Bool(true)
        val jsonFalse: Parser<String, JSON> = string("false") meaning JSON.Bool(false)
        val jsonNumber: Parser<String, JSON> = ::integer.map(JSON::Number)
        val jsonString: Parser<String, JSON> = ::quotedString.map(JSON::String)

        fun token(s: String): Parser<String, Unit> = string(s).between(::whitespace, ::whitespace)

        val jsonArray: Parser<String, JSON> = (jsonParserRef.get() separatedBy token(","))
            .between(token("["), token("]"))
            .map(JSON::Array)

        val jsonKeyValuePair = ::quotedString.followedBy(token(":")) then jsonParserRef.get()

        val jsonObject: Parser<String, JSON> = (jsonKeyValuePair separatedBy token(","))
                .between(token("{"), token("}"))
                .map { pairs -> JSON.Object(pairs.toMap()) }

        val json = (jsonNull or jsonTrue or jsonFalse or jsonNumber or jsonString or jsonArray or jsonObject)
            .apply(jsonParserRef::set)

        json(
            """{
            "foo": "bar",
            "number": 123,
            "array": [1,2,3],
            "objs": [{"a": true}, {"b": false}],
            "nothing": null
        }"""
        ) shouldBe Result.Ok(
            JSON.Object(
                mapOf(
                    "foo" to JSON.String("bar"),
                    "number" to JSON.Number(123),
                    "array" to JSON.Array(listOf(JSON.Number(1), JSON.Number(2), JSON.Number(3))),
                    "objs" to JSON.Array(
                        listOf(
                            JSON.Object(mapOf("a" to JSON.Bool(true))),
                            JSON.Object(mapOf("b" to JSON.Bool(false)))
                        )
                    ),
                    "nothing" to JSON.Null
                )
            ),
            ""
        )
    }

    "tokeniser" {
        val tokens: Parser<String, List<String>> = ::nonWhitespace separatedBy ::whitespace

        tokens("foo bar   a \t b 123\n\n") == listOf("foo", "bar", "a", "b", "123")
    }
})