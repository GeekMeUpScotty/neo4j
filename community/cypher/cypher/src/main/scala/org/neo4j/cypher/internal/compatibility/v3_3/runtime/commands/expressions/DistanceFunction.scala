/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compatibility.v3_3.runtime.commands.expressions

import java.lang.Math._
import java.util

import org.neo4j.cypher.internal.compatibility.v3_3.runtime.pipes.QueryState
import org.neo4j.cypher.internal.compatibility.v3_3.runtime.{CRS, ExecutionContext}
import org.neo4j.cypher.internal.frontend.v3_3.CypherTypeException
import org.neo4j.graphdb.spatial.{Coordinate, Geometry, Point}

case class DistanceFunction(p1: Expression, p2: Expression) extends Expression {

  private val availableCalculators = Seq(HaversinCalculator, CartesianCalculator)

  override def apply(ctx: ExecutionContext)(implicit state: QueryState): Any = {
    // TODO: Support better calculations, like https://en.wikipedia.org/wiki/Vincenty%27s_formulae
    // TODO: Support more coordinate systems
    (p1(ctx), p2(ctx)) match {
      case (null, _) => null
      case (_, null) => null
      case (geometry1: Point, geometry2: Point) => calculateDistance(geometry1, geometry2)
      case (geometry1: Geometry, geometry2: Geometry) => calculateDistance(geometry1, geometry2)
      case (x, y) => throw new CypherTypeException(s"Expected two Points, but got $x and $y")
    }
  }

  implicit def coerceToPoint(geometry: Geometry): Point = geometry match {
    case point: Point => point
    case _ if geometry.getGeometryType == "Point" => new Point {
      override def getCRS = geometry.getCRS
      override def getGeometryType: String = geometry.getGeometryType
      override def getCoordinates: util.List[Coordinate] = geometry.getCoordinates
    }
    case _ => throw new CypherTypeException(s"Expected Point, but got $geometry")
  }

  def calculateDistance(geometry1: Point, geometry2: Point) = {
    availableCalculators.collectFirst {
      case distance: DistanceCalculator if distance.isDefinedAt(geometry1, geometry2) =>
        distance(geometry1, geometry2)
    }.getOrElse(
      throw new IllegalArgumentException(s"Invalid points passed to distance($p1, $p2)")
    ).get
  }

  override def rewrite(f: (Expression) => Expression) = f(DistanceFunction(p1.rewrite(f), p2.rewrite(f)))

  override def arguments: Seq[Expression] = p1.arguments ++ p2.arguments

  override def symbolTableDependencies = p1.symbolTableDependencies ++ p2.symbolTableDependencies

  override def toString = "Distance(" + p1 + ", " + p2 + ")"
}

trait DistanceCalculator {
  def isDefinedAt(p1: Point, p2: Point): Boolean

  def calculateDistance(p1: Point, p2: Point): Double

  def apply(p1: Point, p2: Point): Option[Double] =
    if (isDefinedAt(p1, p2))
      Some(calculateDistance(p1, p2))
    else
      None
}

object CartesianCalculator extends DistanceCalculator {
  override def isDefinedAt(p1: Point, p2: Point): Boolean =
    p1.getCRS.getCode == CRS.Cartesian.code && p2.getCRS.getCode == CRS.Cartesian.code

  override def calculateDistance(p1: Point, p2: Point): Double = {
    val p1Coordinates = p1.getCoordinate.getCoordinate
    val p2Coordinates = p2.getCoordinate.getCoordinate

    sqrt((p2Coordinates.get(0) - p1Coordinates.get(0)) * (p2Coordinates.get(0) - p1Coordinates.get(0)) +
           (p2Coordinates.get(1) - p1Coordinates.get(1)) * (p2Coordinates.get(1) - p1Coordinates.get(1)))
  }
}

object HaversinCalculator extends DistanceCalculator {

  private val EARTH_RADIUS_METERS = 6378140.0

  override def isDefinedAt(p1: Point, p2: Point): Boolean =
    p1.getCRS.getCode == CRS.WGS84.code && p2.getCRS.getCode == CRS.WGS84.code

  override def calculateDistance(p1: Point, p2: Point): Double = {
    val c1Coord = p1.getCoordinate.getCoordinate
    val c2Coord = p2.getCoordinate.getCoordinate
    val c1: Array[Double] = Array(toRadians(c1Coord.get(0)), toRadians(c1Coord.get(1)))
    val c2: Array[Double] = Array(toRadians(c2Coord.get(0)), toRadians(c2Coord.get(1)))
    val dx = c2(0) - c1(0)
    val dy = c2(1) - c1(1)
    val a = pow(sin(dy / 2), 2.0) + cos(c1(1)) * cos(c2(1)) * pow(sin(dx / 2.0), 2.0)
    val greatCircleDistance = 2.0 * atan2(sqrt(a), sqrt(1-a))
    EARTH_RADIUS_METERS * greatCircleDistance
  }
}