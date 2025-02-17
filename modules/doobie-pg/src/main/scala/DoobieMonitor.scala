// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle
package doobie.postgres

import _root_.doobie.Fragment
import cats.Applicative
import cats.implicits._
import edu.gemini.grackle.QueryInterpreter.ProtoJson
import org.typelevel.log4cats.Logger
import edu.gemini.grackle.sql.SqlStatsMonitor
import cats.effect.Ref
import cats.effect.Sync

case class DoobieStats(
  query: Query,
  sql: String,
  args: List[Any],
  rows: Int,
  cols: Int
)

object DoobieMonitor {

  def noopMonitor[F[_]: Applicative]: DoobieMonitor[F] =
    new DoobieMonitor[F] {
      def queryMapped(query: Query, fragment: Fragment, rows: Int, cols: Int): F[Unit] = ().pure[F]
      def resultComputed(result: Result[ProtoJson]): F[Unit] = ().pure[F]
    }

  def loggerMonitor[F[_]](logger: Logger[F]): DoobieMonitor[F] =
    new DoobieMonitor[F] {

      def queryMapped(query: Query, fragment: Fragment, rows: Int, cols: Int): F[Unit] =
        logger.info(
          s"""query: $query
             |sql: ${fragment.internals.sql}
             |args: ${fragment.internals.elements.mkString(", ")}
             |fetched $rows row(s) of $cols column(s)
           """.stripMargin)

      def resultComputed(result: Result[ProtoJson]): F[Unit] =
        logger.info(s"result: $result")
    }

  def statsMonitor[F[_]: Sync]: F[SqlStatsMonitor[F, Fragment]] =
    Ref[F].of(List.empty[SqlStatsMonitor.SqlStats]).map { ref =>
      new SqlStatsMonitor[F, Fragment](ref) {
        def inspect(fragment: Fragment): (String, List[Any]) =
          (fragment.internals.sql, fragment.internals.elements)
      }
    }

}
