// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package compiler

import cats.data.NonEmptyChain
import cats.implicits._
import munit.CatsEffectSuite

import edu.gemini.grackle._
import edu.gemini.grackle.syntax._
import Query._, Predicate._, Value._, UntypedOperation._
import QueryCompiler._, ComponentElaborator.TrivialJoin

final class CompilerSuite extends CatsEffectSuite {
  test("simple query") {
    val query = """
      query {
        character(id: "1000") {
          name
        }
      }
    """

    val expected =
      Select("character", List(Binding("id", StringValue("1000"))),
        Select("name", Nil)
      )

    val res = QueryParser.parseText(query)
    assertEquals(res, Result.Success(UntypedQuery(expected, Nil)))
  }

  test("simple mutation") {
    val query = """
      mutation {
        update_character(id: "1000", name: "Luke") {
          character {
            name
          }
        }
      }
    """

    val expected =
      Select("update_character", List(Binding("id", StringValue("1000")), Binding("name", StringValue("Luke"))),
        Select("character", Nil,
          Select("name", Nil)
        )
      )

    val res = QueryParser.parseText(query)
    assertEquals(res, Result.Success(UntypedMutation(expected, Nil)))
  }

  test("simple subscription") {
    val query = """
      subscription {
        character(id: "1000") {
          name
        }
      }
    """

    val expected =
      Select("character", List(Binding("id", StringValue("1000"))),
        Select("name", Nil)
      )

    val res = QueryParser.parseText(query)
    assertEquals(res, Result.Success(UntypedSubscription(expected, Nil)))
  }

  test("simple nested query") {
    val query = """
      query {
        character(id: "1000") {
          name
          friends {
            name
          }
        }
      }
    """

    val expected =
      Select(
        "character", List(Binding("id", StringValue("1000"))),
        Select("name", Nil) ~
          Select(
            "friends", Nil,
            Select("name", Nil)
          )
      )

    val res = QueryParser.parseText(query)
    assertEquals(res, Result.Success(UntypedQuery(expected, Nil)))
  }

  test("shorthand query") {
    val query = """
      {
        hero(episode: NEWHOPE) {
          name
          friends {
            name
            friends {
              name
            }
          }
        }
      }
    """

    val expected =
      Select(
        "hero", List(Binding("episode", UntypedEnumValue("NEWHOPE"))),
        Select("name", Nil, Empty) ~
        Select("friends", Nil,
          Select("name", Nil, Empty) ~
          Select("friends", Nil,
            Select("name", Nil, Empty)
          )
        )
      )

    val res = QueryParser.parseText(query)
    assertEquals(res, Result.Success(UntypedQuery(expected, Nil)))
  }

  test("field alias") {
    val query = """
      {
        user(id: 4) {
          id
          name
          smallPic: profilePic(size: 64)
          bigPic: profilePic(size: 1024)
        }
      }
    """

    val expected =
      Select("user", List(Binding("id", IntValue(4))),
        Group(List(
          Select("id", Nil, Empty),
          Select("name", Nil, Empty),
          Rename("smallPic", Select("profilePic", List(Binding("size", IntValue(64))), Empty)),
          Rename("bigPic", Select("profilePic", List(Binding("size", IntValue(1024))), Empty))
        ))
      )

    val res = QueryParser.parseText(query)
    assertEquals(res, Result.Success(UntypedQuery(expected, Nil)))
  }

  test("introspection query") {
    val query = """
      query IntrospectionQuery {
        __schema {
          queryType {
            name
          }
          mutationType {
            name
          }
          subscriptionType {
            name
          }
        }
      }
    """

    val expected =
      Select(
        "__schema", Nil,
        Select("queryType", Nil, Select("name", Nil, Empty)) ~
        Select("mutationType", Nil, Select("name", Nil, Empty)) ~
        Select("subscriptionType", Nil, Select("name", Nil, Empty))
      )

    val res = QueryParser.parseText(query)
    assertEquals(res, Result.Success(UntypedQuery(expected, Nil)))
  }

  test("simple selector elaborated query") {
    val query = """
      query {
        character(id: "1000") {
          name
          friends {
            name
          }
        }
      }
    """

    val expected =
      Select(
        "character", Nil,
        Unique(
          Filter(Eql(AtomicMapping.CharacterType / "id", Const("1000")),
          Select("name", Nil) ~
              Select(
                "friends", Nil,
                Select("name", Nil)
              )
          )
        )
    )

    val res = AtomicMapping.compiler.compile(query)

    assertEquals(res.map(_.query), Result.Success(expected))
  }

  test("invalid: object subselection set empty") {
    val query = """
      query {
        character(id: "1000")
      }
    """

    val expected = Problem("Non-leaf field 'character' of Query must have a non-empty subselection set")

    val res = AtomicMapping.compiler.compile(query)

    assertEquals(res, Result.Failure(NonEmptyChain(expected)))
  }

  test("invalid: object subselection set invalid") {
    val query = """
      query {
        character(id: "1000") {
          foo
        }
      }
    """

    val expected = Problem("Unknown field 'foo' in select")

    val res = AtomicMapping.compiler.compile(query)

    assertEquals(res, Result.Failure(NonEmptyChain(expected)))
  }

  test("invalid: leaf subselection set not empty (1)") {
    val query = """
      query {
        character(id: "1000") {
          name {
            __typename
          }
        }
      }
    """

    val expected = Problem("Leaf field 'name' of Character must have an empty subselection set")

    val res = AtomicMapping.compiler.compile(query)

    assertEquals(res, Result.Failure(NonEmptyChain(expected)))
  }

  test("invalid: leaf subselection set not empty (2)") {
    val query = """
      query {
        character(id: "1000") {
          name {
            foo
          }
        }
      }
    """

    val expected = Problem("Leaf field 'name' of Character must have an empty subselection set")

    val res = AtomicMapping.compiler.compile(query)

    assertEquals(res, Result.Failure(NonEmptyChain(expected)))
  }

  test("invalid: bogus field argument") {
    val query = """
      query {
        character(id: "1000", quux: 23) {
          name
        }
      }
    """

    val expected = Problem("Unknown argument(s) 'quux' in field character of type Query")

    val res = AtomicMapping.compiler.compile(query)

    assertEquals(res, Result.Failure(NonEmptyChain(expected)))
  }

  test("simple component elaborated query") {
    val query = """
      query {
        componenta {
          fielda1
          fielda2 {
            componentb {
              fieldb1
              fieldb2 {
                componentc {
                  fieldc1
                }
              }
            }
          }
        }
      }
    """

    val expected =
      Wrap("componenta",
        Component(ComponentA, TrivialJoin,
          Select("componenta", Nil,
            Select("fielda1", Nil) ~
            Select("fielda2", Nil,
              Wrap("componentb",
                Component(ComponentB, TrivialJoin,
                  Select("componentb", Nil,
                    Select("fieldb1", Nil) ~
                    Select("fieldb2", Nil,
                      Wrap("componentc",
                        Component(ComponentC, TrivialJoin,
                          Select("componentc", Nil,
                            Select("fieldc1", Nil)
                          )
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )

    val res = ComposedMapping.compiler.compile(query)

    assertEquals(res.map(_.query), Result.Success(expected))
  }

  test("malformed query (1)") {
    val query = """
      query {
        character(id: "1000" {
          name
        }
      }
    """

    val res = QueryParser.parseText(query)

    val error =
      """Parse error at line 2 column 29
        |        character(id: "1000" {
        |                             ^""".stripMargin

    assertEquals(res, Result.failure(error))
  }

  test("malformed query (2)") {
    val query = ""

    val res = QueryParser.parseText(query)

    assertEquals(res, Result.failure("At least one operation required"))
  }

  test("malformed query (3)") {
    val query = """
      query {
        character(id: "1000") {
          name
        }
    """

    val res = QueryParser.parseText(query)

    val error =
      "Parse error at line 5 column 4\n    \n    ^"

    assertEquals(res, Result.failure(error))
  }
}

object AtomicMapping extends TestMapping {
  val schema =
    schema"""
      type Query {
        character(id: String!): Character
      }
      type Character {
        id: String!
        name: String
        friends: [Character!]
      }
    """

  val QueryType = schema.ref("Query")
  val CharacterType = schema.ref("Character")

  override val selectElaborator = new SelectElaborator(Map(
    QueryType -> {
      case Select("character", List(Binding("id", StringValue(id))), child) =>
        Select("character", Nil, Unique(Filter(Eql(CharacterType / "id", Const(id)), child))).success
    }
  ))
}
trait DummyComponent extends TestMapping {
  val schema = schema"type Query { dummy: Int }"
}

object ComponentA extends DummyComponent
object ComponentB extends DummyComponent
object ComponentC extends DummyComponent

object ComposedMapping extends TestMapping {
  val schema =
    schema"""
      type Query {
        componenta: ComponentA!
      }
      type ComponentA {
        fielda1: String!
        fielda2: FieldA2
      }
      type FieldA2 {
        componentb: ComponentB
      }
      type ComponentB {
        fieldb1: String!
        fieldb2: FieldB2
      }
      type FieldB2 {
        componentc: ComponentC
      }
      type ComponentC {
        fieldc1: Int
      }
    """

  val QueryType = schema.ref("Query")
  val FieldA2Type = schema.ref("FieldA2")
  val FieldB2Type = schema.ref("FieldB2")

  override val typeMappings =
    List(
      ObjectMapping(
        tpe = QueryType,
        fieldMappings =
          List(
            Delegate("componenta", ComponentA)
          )
      ),
      ObjectMapping(
        tpe = FieldA2Type,
        fieldMappings =
          List(
            Delegate("componentb", ComponentB)
          )
      ),
      ObjectMapping(
        tpe = FieldB2Type,
        fieldMappings =
          List(
            Delegate("componentc", ComponentC)
          )
      )
    )
}
