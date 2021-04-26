// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package projection

import cats.effect.Sync
import cats.implicits._

import edu.gemini.grackle._, skunk._, syntax._
import edu.gemini.grackle.Path._
import edu.gemini.grackle.Predicate.{Const, Eql, Project}
import edu.gemini.grackle.Query.{Binding, Filter, Select}
import edu.gemini.grackle.QueryCompiler.SelectElaborator
import edu.gemini.grackle.Value.{BooleanValue, ObjectValue}
import cats.effect.Resource
import _root_.skunk.Session
import _root_.skunk.codec.all._

trait ProjectionMapping[F[_]] extends SkunkMapping[F] {

  object level0 extends TableDef("level0") {
    val id = col("id", varchar)
  }

  object level1 extends TableDef("level1") {
    val id = col("id", varchar)
    val level0Id = col("level0_id", varchar.opt)
  }

  object level2 extends TableDef("level2") {
    val id = col("id", varchar)
    val level1Id = col("level1_id", varchar.opt)
    val attr = col("attr", bool.opt)
  }

  val schema =
    schema"""
      type Query {
        level0(filter: Filter): [Level0!]!
        level1(filter: Filter): [Level1!]!
        level2(filter: Filter): [Level2!]!
      }
      type Level0 {
        id: String!
        level1(filter: Filter): [Level1!]!
      }
      type Level1 {
        id: String!
        level2(filter: Filter): [Level2!]!
      }
      type Level2 {
        id: String!
        attr: Boolean
      }
      input Filter {
        attr: Boolean
      }
    """

  val QueryType = schema.ref("Query")
  val Level0Type = schema.ref("Level0")
  val Level1Type = schema.ref("Level1")
  val Level2Type = schema.ref("Level2")

  val typeMappings =
    List(
      ObjectMapping(
        tpe = QueryType,
        fieldMappings =
          List(
            SqlRoot("level0"),
            SqlRoot("level1"),
            SqlRoot("level2")
          )
      ),
      ObjectMapping(
        tpe = Level0Type,
        fieldMappings =
          List(
            SqlField("id", level0.id, key = true),
            SqlObject("level1", Join(level0.id, level1.level0Id))
          )
      ),
      ObjectMapping(
        tpe = Level1Type,
        fieldMappings =
          List(
            SqlField("id", level1.id, key = true),
            SqlField("level0_id", level1.level0Id, hidden = true),
            SqlObject("level2", Join(level1.id, level2.level1Id))
          )
      ),
      ObjectMapping(
        tpe = Level2Type,
        fieldMappings =
          List(
            SqlField("id", level2.id, key = true),
            SqlField("attr", level2.attr),
            SqlField("level1_id", level2.level1Id, hidden = true)
          )
      )
    )

  object Level0FilterValue {
    def unapply(input: ObjectValue): Option[Predicate] = {
      input.fields match {
        case List(("attr", BooleanValue(attr))) =>
          Some(Project(List("level1", "level2"), Eql(UniquePath(List("attr")), Const(attr))))
        case _ => None
      }
    }
  }

  object Level1FilterValue {
    def unapply(input: ObjectValue): Option[Predicate] = {
      input.fields match {
        case List(("attr", BooleanValue(attr))) =>
          Some(Project(List("level2"), Eql(UniquePath(List("attr")), Const(attr))))
        case _ => None
      }
    }
  }

  object Level2FilterValue {
    def unapply(input: ObjectValue): Option[Predicate] = {
      input.fields match {
        case List(("attr", BooleanValue(attr))) =>
          Some(Eql(UniquePath(List("attr")), Const(attr)))
        case _ => None
      }
    }
  }

  override val selectElaborator: SelectElaborator = new SelectElaborator(Map(
    QueryType -> {
      case Select("level0", List(Binding("filter", filter)), child) =>
        val f = filter match {
          case Level0FilterValue(f) => Filter(f, child)
          case _ => child
        }
        Select("level0", Nil, f).rightIor

      case Select("level1", List(Binding("filter", filter)), child) =>
        val f = filter match {
          case Level1FilterValue(f) => Filter(f, child)
          case _ => child
        }
        Select("level1", Nil, f).rightIor

      case Select("level2", List(Binding("filter", filter)), child) =>
        val f = filter match {
          case Level2FilterValue(f) => Filter(f, child)
          case _ => child
        }
        Select("level2", Nil, f).rightIor

      case other => other.rightIor
    },
    Level0Type -> {
      case Select("level1", List(Binding("filter", filter)), child) =>
        val f = filter match {
          case Level1FilterValue(f) => Filter(f, child)
          case _ => child
        }
        Select("level1", Nil, f).rightIor

      case other => other.rightIor
    },
    Level1Type -> {
      case Select("level2", List(Binding("filter", filter)), child) =>
        val f = filter match {
          case Level2FilterValue(f) => Filter(f, child)
          case _ => child
        }
        Select("level2", Nil, f).rightIor

      case other => other.rightIor
    }
  ))
}

object ProjectionMapping extends SkunkMappingCompanion {
  def mkMapping[F[_]: Sync](pool: Resource[F, Session[F]], monitor: SkunkMonitor[F]): Mapping[F] =
    new SkunkMapping[F](pool, monitor) with ProjectionMapping[F]
}
