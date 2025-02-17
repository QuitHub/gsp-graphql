// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle

import scala.annotation.tailrec

import cats.implicits._
import cats.kernel.Order

import syntax._
import Cursor.Env
import Query._

/** GraphQL query Algebra */
sealed trait Query {
  /** Groups this query with its argument, Groups on either side are merged */
  def ~(query: Query): Query = (this, query) match {
    case (Group(hd), Group(tl)) => Group(hd ++ tl)
    case (hd, Group(tl)) => Group(hd :: tl)
    case (Group(hd), tl) => Group(hd :+ tl)
    case (hd, tl) => Group(List(hd, tl))
  }

  /** Yields a String representation of this query */
  def render: String
}

object Query {
  /** Select field `name` given arguments `args` and continue with `child` */
  case class Select(name: String, args: List[Binding], child: Query = Empty) extends Query {
    def eliminateArgs(elim: Query => Query): Query = copy(args = Nil, child = elim(child))

    def render = {
      val rargs = if(args.isEmpty) "" else s"(${args.map(_.render).mkString(", ")})"
      val rchild = if(child == Empty) "" else s" { ${child.render} }"
      s"$name$rargs$rchild"
    }
  }

  /** A Group of sibling queries at the same level */
  case class Group(queries: List[Query]) extends Query {
    def render = queries.map(_.render).mkString("{", ", ", "}")
  }

  /** Continues with single-element-list-producing `child` and yields the single element */
  case class Unique(child: Query) extends Query {
    def render = s"<unique: ${child.render}>"
  }

  /** Retains only elements satisfying `pred` and continues with `child` */
  case class Filter(pred: Predicate, child: Query) extends Query {
    def render = s"<filter: $pred ${child.render}>"
  }

  /** Identifies a component boundary.
   *  `join` is applied to the current cursor and `child` yielding a continuation query which will be
   *  evaluated by the interpreter identified by `componentId`.
   */
  case class Component[F[_]](mapping: Mapping[F], join: (Query, Cursor) => Result[Query], child: Query) extends Query {
    def render = s"<component: $mapping ${child.render}>"
  }

  /** Embeds possibly batched effects.
   *  `handler` is applied to one or more possibly batched queries and cursors yielding corresponding
   *  continuation queries and cursors which will be evaluated by the current interpreter in the next
   *  phase.
   */
  case class Effect[F[_]](handler: EffectHandler[F], child: Query) extends Query {
    def render = s"<effect: ${child.render}>"
  }

  trait EffectHandler[F[_]] {
    def runEffects(queries: List[(Query, Cursor)]): F[Result[List[(Query, Cursor)]]]
  }

  /** Evaluates an introspection query relative to `schema` */
  case class Introspect(schema: Schema, child: Query) extends Query {
    def render = s"<introspect: ${child.render}>"
  }

  /** Add `env` to the environment for the continuation `child` */
  case class Environment(env: Env, child: Query) extends Query {
    def render = s"<environment: $env ${child.render}>"
  }

  /**
   * Wraps the result of `child` as a field named `name` of an enclosing object.
   */
  case class Wrap(name: String, child: Query) extends Query {
    def render = {
      val rchild = if(child == Empty) "" else s" { ${child.render} }"
      s"[$name]$rchild"
    }
  }

  /**
   * Rename the topmost field of `sel` to `name`.
   */
  case class Rename(name: String, child: Query) extends Query {
    def render = s"<rename: $name ${child.render}>"
  }

  /**
   * Untyped precursor of `Narrow`.
   *
   * Trees of this type will be replaced by a corresponding `Narrow` by
   * `SelectElaborator`.
   */
  case class UntypedNarrow(tpnme: String, child: Query) extends Query {
    def render = s"<narrow: $tpnme ${child.render}>"
  }

  /**
   * The result of `child` if the focus is of type `subtpe`, `Empty` otherwise.
   */
  case class Narrow(subtpe: TypeRef, child: Query) extends Query {
    def render = s"<narrow: $subtpe ${child.render}>"
  }

  /** Skips/includes the continuation `child` depending on the value of `cond` */
  case class Skip(sense: Boolean, cond: Value, child: Query) extends Query {
    def render = s"<skip: $sense $cond ${child.render}>"
  }

  /** Limits the results of list-producing continuation `child` to `num` elements */
  case class Limit(num: Int, child: Query) extends Query {
    def render = s"<limit: $num ${child.render}>"
  }

  /** Drops the first `num` elements of list-producing continuation `child`. */
  case class Offset(num: Int, child: Query) extends Query {
    def render = s"<offset: $num ${child.render}>"
  }

  /** Orders the results of list-producing continuation `child` by fields
   *  specified by `selections`.
   */
  case class OrderBy(selections: OrderSelections, child: Query) extends Query {
    def render = s"<order-by: $selections ${child.render}>"
  }

  case class OrderSelections(selections: List[OrderSelection[_]]) {
    def order(lc: Seq[Cursor]): Seq[Cursor] = {
      def cmp(x: Cursor, y: Cursor): Int = {
        @tailrec
        def loop(sels: List[OrderSelection[_]]): Int =
          sels match {
            case Nil => 0
            case hd :: tl =>
              hd(x, y) match {
                case 0 => loop(tl)
                case ord => ord
              }
          }

        loop(selections)
      }

      lc.sortWith((x, y) => cmp(x, y) < 0)
    }
  }

  case class OrderSelection[T: Order](term: Term[T], ascending: Boolean = true, nullsLast: Boolean = true) {
    def apply(x: Cursor, y: Cursor): Int = {
      def deref(c: Cursor): Option[T] =
        if (c.isNullable) c.asNullable.toOption.flatten.flatMap(term(_).toOption)
        else term(c).toOption

      (deref(x), deref(y)) match {
        case (None, None) => 0
        case (_, None) => (if (nullsLast) -1 else 1)
        case (None, _) => (if (nullsLast) 1 else -1)
        case (Some(x0), Some(y0)) =>
          val ord = Order[T].compare(x0, y0)
          if (ascending) ord
          else -ord
      }
    }

    def subst(term: Term[T]): OrderSelection[T] = copy(term = term)
  }

  /** Computes the number of top-level elements of `child` as field `name` */
  case class Count(name: String, child: Query) extends Query {
    def render = s"$name:count { ${child.render} }"
  }

  /**
   * Uses the supplied function to compute a continuation `Cursor` from the
   * current `Cursor`.
   */
  case class TransformCursor(f: Cursor => Result[Cursor], child: Query) extends Query {
    def render = s"<transform-cursor ${child.render}>"
  }

  /** A placeholder for a skipped node */
  case object Skipped extends Query {
    def render = "<skipped>"
  }

  /** The terminal query */
  case object Empty extends Query {
    def render = ""
  }

  case class Binding(name: String, value: Value) {
    def render: String = s"$name: $value"
  }

  type UntypedVarDefs = List[UntypedVarDef]
  type VarDefs = List[InputValue]
  type Vars = Map[String, (Type, Value)]

  case class UntypedVarDef(name: String, tpe: Ast.Type, default: Option[Value])

  /**
   * Extractor for nested Rename/Select patterns in the query algebra
   *
   * PossiblyRenamedSelect is an extractor/constructor for a Select node
   * possibly wrapped in a Rename node so that they can be handled together
   * conveniently.
   */
  object PossiblyRenamedSelect {
    def apply(sel: Select, resultName: String): Query = sel match {
      case Select(`resultName`, _, _) => sel
      case _ => Rename(resultName, sel)
    }

    def apply(sel: Select, resultName: Option[String]): Query = resultName match {
      case Some(resultName) => Rename(resultName, sel)
      case None => sel
    }

    def unapply(q: Query): Option[(Select, String)] =
      q match {
        case Rename(name, sel: Select) => Some((sel, name))
        case sel: Select => Some((sel, sel.name))
        case _ => None
      }
  }

  def renameRoot(q: Query, rootName: String): Option[Query] = q match {
    case Rename(_, sel@Select(`rootName`, _, _)) => Some(sel)
    case r@Rename(`rootName`, _)                 => Some(r)
    case Rename(_, sel: Select)                  => Some(Rename(rootName, sel))
    case sel@Select(`rootName`, _, _)            => Some(sel)
    case sel: Select                             => Some(Rename(rootName, sel))
    case w@Wrap(`rootName`, _)                   => Some(w)
    case w: Wrap                                 => Some(w.copy(name = rootName))
    case e@Environment(_, child)                 => renameRoot(child, rootName).map(rc => e.copy(child = rc))
    case t@TransformCursor(_, child)             => renameRoot(child, rootName).map(rc => t.copy(child = rc))
    case _ => None
  }

  /**
    * Computes the root name and optional alias of the supplied query
    * if it is unique, `None` otherwise.
    */
  def rootName(q: Query): Option[(String, Option[String])] = {
    def loop(q: Query, alias: Option[String]): Option[(String, Option[String])] =
      q match {
        case Select(name, _, _)        => Some((name, alias))
        case Wrap(name, _)             => Some((name, alias))
        case Count(name, _)            => Some((name, alias))
        case Rename(name, child)       => loop(child, alias.orElse(Some(name)))
        case Environment(_, child)     => loop(child, alias)
        case TransformCursor(_, child) => loop(child, alias)
        case _                         => None
      }
    loop(q, None)
  }

  /**
   * Yields a list of the top level queries of the supplied, possibly
   * grouped query.
   */
  def ungroup(query: Query): List[Query] =
    query match {
      case Group(queries) => queries.flatMap(ungroup)
      case query => List(query)
    }

  /**
   * Returns the top-level field selections of the supplied query.
   */
  def children(q: Query): List[Query] = {
    def loop(q: Query): List[Query] =
      q match {
        case Select(_, _, child)       => ungroup(child)
        case Wrap(_, child)            => ungroup(child)
        case Count(_, child)           => ungroup(child)
        case Rename(_, child)          => loop(child)
        case Environment(_, child)     => loop(child)
        case TransformCursor(_, child) => loop(child)
        case _                         => Nil
      }
    loop(q)
  }

  /**
   * True if `fieldName` is a top-level selection of the supplied query,
   * false otherwise.
   */
  def hasField(query: Query, fieldName: String): Boolean = {
    def loop(q: Query): Boolean =
      ungroup(q).exists {
        case Select(`fieldName`, _, _) => true
        case Rename(_, child)          => loop(child)
        case Environment(_, child)     => loop(child)
        case TransformCursor(_, child) => loop(child)
        case _                         => false
      }
    loop(query)
  }

  /**
   * Returns the alias, if any, of the top-level field `fieldName` in
   * the supplied query.
   */
  def fieldAlias(query: Query, fieldName: String): Option[String] = {
    def loop(q: Query, alias: Option[String]): Option[String] =
      ungroup(q).collectFirstSome {
        case Select(`fieldName`, _, _) => alias
        case Wrap(`fieldName`, _)      => alias
        case Count(`fieldName`, _)     => alias
        case Rename(alias, child)      => loop(child, Some(alias))
        case Environment(_, child)     => loop(child, alias)
        case TransformCursor(_, child) => loop(child, alias)
        case _                         => None
      }
    loop(query, None)
  }

  /**
   * Tranform the children of `query` using the supplied function.
   */
  def mapFields(query: Query)(f: Query => Result[Query]): Result[Query] = {
    def loop(q: Query): Result[Query] =
      q match {
        case Group(qs) => qs.traverse(loop).map(Group(_))
        case s: Select => f(s)
        case w: Wrap => f(w)
        case c: Count => f(c)
        case r@Rename(_, child) => loop(child).map(ec => r.copy(child = ec))
        case e@Environment(_, child) => loop(child).map(ec => e.copy(child = ec))
        case t@TransformCursor(_, child) => loop(child).map(ec => t.copy(child = ec))
        case other => other.success
      }
    loop(query)
  }

  /** Constructor/extractor for nested Filter/OrderBy/Limit/Offset patterns
   *  in the query algebra
   **/
  object FilterOrderByOffsetLimit {
    def apply(pred: Option[Predicate], oss: Option[List[OrderSelection[_]]], offset: Option[Int], limit: Option[Int], child: Query): Query = {
      val filter = pred.map(p =>   Filter(p, child)).getOrElse(child)
      val order  = oss.map(o =>    OrderBy(OrderSelections(o), filter)).getOrElse(filter)
      val off    = offset.map(n => Offset(n, order)).getOrElse(order)
      val lim    = limit.map(n =>  Limit(n, off)).getOrElse(off)
      lim
    }

    def unapply(q: Query): Option[(Option[Predicate], Option[List[OrderSelection[_]]], Option[Int],  Option[Int], Query)] = {
      val (limit, q0) = q match {
        case Limit(lim, child) => (Some(lim), child)
        case child => (None, child)
      }
      val (offset, q1) = q0 match {
        case Offset(off, child) => (Some(off), child)
        case child => (None, child)
      }
      val (order, q2) = q1 match {
        case OrderBy(OrderSelections(oss), child) => (Some(oss), child)
        case child => (None, child)
      }
      val (filter, q3) = q2 match {
        case Filter(pred, child) => (Some(pred), child)
        case child => (None, child)
      }
      limit.orElse(offset).orElse(order).orElse(filter).map { _ =>
        (filter, order, offset, limit, q3)
      }
    }
  }

  /** Extractor for grouped Narrow patterns in the query algebra */
  object TypeCase {
    def unapply(q: Query): Option[(Query, List[Narrow])] = {
      def loop(q: Query): List[Query] =
        q match {
          case Group(children) => children.flatMap(loop)
          case other => List(other)
        }

      def branch(q: Query): Option[TypeRef] =
        q match {
          case Narrow(subtpe, _) => Some(subtpe)
          case _ => None
        }

      val grouped = loop(q).groupBy(branch).toList
      val (default0, narrows0) = grouped.partition(_._1.isEmpty)
      if (narrows0.isEmpty) None
      else {
        val default = default0.flatMap(_._2) match {
          case Nil => Empty
          case children => Group(children)
        }
        val narrows = narrows0.collect {
          case (Some(subtpe), narrows) =>
            narrows.collect { case Narrow(_, child) => child } match {
              case List(child) => Narrow(subtpe, child)
              case children => Narrow(subtpe, Group(children))
            }
        }
        Some((default, narrows))
      }
    }
  }

  /** Construct a query which yields all the supplied paths */
  def mkPathQuery(paths: List[List[String]]): List[Query] =
    paths match {
      case Nil => Nil
      case paths =>
        val oneElemPaths = paths.filter(_.sizeCompare(1) == 0).distinct
        val oneElemQueries: List[Query] = oneElemPaths.map(p => Select(p.head, Nil, Empty))
        val multiElemPaths = paths.filter(_.length > 1).distinct
        val grouped: List[Query] = multiElemPaths.groupBy(_.head).toList.map {
          case (fieldName, suffixes) =>
            Select(fieldName, Nil, mergeQueries(mkPathQuery(suffixes.map(_.tail).filterNot(_.isEmpty))))
        }
        oneElemQueries ++ grouped
    }

  /** Merge the given queries as a single query */
  def mergeQueries(qs: List[Query]): Query = {
    qs.filterNot(_ == Empty) match {
      case Nil => Empty
      case List(one) => one
      case qs =>
        def flattenLevel(qs: List[Query]): List[Query] = {
          def loop(qs: List[Query], acc: List[Query]): List[Query] =
            qs match {
              case Nil => acc.reverse
              case Group(gs) :: tl => loop(gs ++ tl, acc)
              case Empty :: tl => loop(tl, acc)
              case hd :: tl => loop(tl, hd :: acc)
            }
          loop(qs, Nil)
        }

        val flattened = flattenLevel(qs)
        val (selects, rest) = flattened.partition { case PossiblyRenamedSelect(_, _) => true ; case _ => false }

        val mergedSelects =
          selects.groupBy { case PossiblyRenamedSelect(Select(fieldName, _, _), resultName) => (fieldName, resultName) ; case _ => throw new MatchError("Impossible") }.values.map { rsels =>
            val PossiblyRenamedSelect(Select(fieldName, _, _), resultName) = rsels.head : @unchecked
            val sels = rsels.collect { case PossiblyRenamedSelect(sel, _) => sel }
            val children = sels.map(_.child)
            val merged = mergeQueries(children)
            PossiblyRenamedSelect(Select(fieldName, Nil, merged), resultName)
          }

        Group(rest ++ mergedSelects)
      }
  }
}
