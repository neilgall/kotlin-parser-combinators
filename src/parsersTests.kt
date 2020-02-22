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

class ParserTests : StringSpec({

    "dot" {
        dot(".foo") shouldBe Result.Ok(Unit, "foo")
        dot("foo") shouldBe Result.Err("a dot", "foo")
    }

    "integer" {
        integer("123foo") shouldBe Result.Ok(123, "foo")
        integer("foo") shouldBe Result.Err("an integer", "foo")
    }

    "string" {
        val p = string("foo")
        p("foobar") shouldBe Result.Ok(Unit, "bar")
        p("boofar") shouldBe Result.Err("'foo'", "boofar")
    }

    "seq" {
        val p = seq(::integer, string("foo"))
        p("123foo") shouldBe Result.Ok(Pair(123, Unit), "")
    }

    "then" {
        val p = ::integer then string("foo")
        p("123foo") shouldBe Result.Ok(Pair(123, Unit), "")
    }

    "choice" {
        val p = choice(string("foo").means(1), string("bar").means(2))
        p("foobar") shouldBe Result.Ok(1, "bar")
        p("barfoo") shouldBe Result.Ok(2, "foo")
        p("xyz") shouldBe Result.Err("'foo' or 'bar'", "xyz")
    }

    "or" {
        val p = string("foo").means(1) or string("bar").means(2)
        p("foobar") shouldBe Result.Ok(1, "bar")
        p("barfoo") shouldBe Result.Ok(2, "foo")
        p("xyz") shouldBe Result.Err("'foo' or 'bar'", "xyz")
    }

    "before" {
        val p = string("*").before(::integer)
        p("*123") shouldBe Result.Ok(123, "")
    }

    "followedBy" {
        val p = ::integer.followedBy(string("%"))
        p("123%") shouldBe Result.Ok(123, "")
    }

    "between" {
        val p = ::integer.between(string("<"), string(">"))
        p("<123>") shouldBe Result.Ok(123, "")
    }

    "many" {
        val p = ::dot.means(1).many().map { it.sum() }
        p("...foo") shouldBe Result.Ok(3, "foo")
        p("foo") shouldBe Result.Ok(0, "foo")
    }

    "sepBy" {
        val p = ::integer.sepBy(string(","))
        p("1,2,3") shouldBe Result.Ok(listOf(1, 2, 3), "")
        p("1,2,foo") shouldBe Result.Err("an integer", "foo")
        p("foo") shouldBe Result.Ok(emptyList<Int>(), "foo")
    }

    "json list of ints" {
        val p = ::integer.sepBy(string(",")).between(string("["), string("]"))
        p("[1,2,3,4]") shouldBe Result.Ok(listOf(1, 2, 3, 4), "")
    }

    "json" {
        val jsonRef = ParserRef<JSON>()

        val jsonNull = string("null").means(JSON.Null)
        val jsonTrue = string("true").means(JSON.Bool(true))
        val jsonFalse = string("false").means(JSON.Bool(false))
        val jsonNum = ::integer.map(JSON::Number)
        val jsonStr = ::quotedString.map(JSON::String)

        fun token(s: String): Parser<Unit> =
            string(s).between(::whitespace, ::whitespace)

        val jsonArray = jsonRef
            .sepBy(token(","))
            .between(token("["), token("]"))
            .map(JSON::Array)

        val jsonPair = ::quotedString.followedBy(token(":")) then jsonRef

        val jsonObject = jsonPair
            .sepBy(token(","))
            .between(token("{"), token("}"))
            .map { pairs -> JSON.Object(pairs.toMap()) }

        val json = (jsonNull or jsonTrue or jsonFalse or jsonNum or
                jsonStr or jsonArray or jsonObject)

        jsonRef.set(json)

        json(
            """
            { 
                "foo": "bar",
                "number": 123,
                "array": [ 1,2,3 ],
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
})
