package lila.analyse

import chess.Color

import lila.common.Maths
import lila.game.Game
import lila.tree.Eval
import lila.tree.Eval.{ Cp, Mate }

// Quality of a move, based on previous and next WinPercent
case class AccuracyPercent private (value: Double) extends AnyVal with Percent {
  def *(weight: Double) = copy(value * weight)
}

object AccuracyPercent {

  def fromPercent(int: Int) = AccuracyPercent(int.toDouble)

  val perfect = fromPercent(100)

  implicit val ordering = Ordering.by[AccuracyPercent, Double](_.value)

  /*
from scipy.optimize import curve_fit
import numpy as np

def model_func(x, a, k, b):
    return a * np.exp(-k*x) + b

# sample data
xs      = np.array([    0,  5, 10, 20, 40, 60,    80, 90, 100])
ys      = np.array([  100, 75, 60, 42, 20,  5,     0,  0,   0])
sigma   = np.array([0.005,  1,  1,  1,  1,  1, 0.005,  1,   1]) # error stdev

opt, pcov = curve_fit(model_func, xs, ys, None, sigma)
a, k, b = opt
print(f"{a} * exp(-{k} * x) + {b}")
for x in xs:
    print(f"f({x}) = {model_func(x, a, k, b)}");
   */
  def fromWinPercents(before: WinPercent, after: WinPercent): AccuracyPercent = AccuracyPercent {
    if (after.value >= before.value) 100d
    else
      {
        val winDiff = before.value - after.value
        103.1668100711649 * Math.exp(-0.04354415386753951 * winDiff) + -3.166924740191411;
      } atMost 100 atLeast 0
  }

  // returns None if one or more evals have no score (incomplete analysis)
  // def winPercents(pov: Game.SideAndStart, evals: List[Eval]): Option[List[WinPercent]] = {
  //   val subjectiveEvals = pov.color.fold(evals, evals.map(_.invert))
  //   val alignedEvals = if (pov.color == pov.startColor) Eval.initial :: subjectiveEvals else subjectiveEvals
  //   alignedEvals.flatMap(WinPercent.fromEval).some.filter(_.sizeCompare(evals) == 0)
  // }

  def fromEvalsAndPov(pov: Game.SideAndStart, evals: List[Eval]): List[AccuracyPercent] = {
    val subjectiveEvals = pov.color.fold(evals, evals.map(_.invert))
    val alignedEvals = if (pov.color == pov.startColor) Eval.initial :: subjectiveEvals else subjectiveEvals
    alignedEvals
      .grouped(2)
      .collect { case List(e1, e2) =>
        for {
          before <- WinPercent.fromEval(e1)
          after  <- WinPercent.fromEval(e2)
        } yield AccuracyPercent.fromWinPercents(before, after)
      }
      .flatten
      .toList
  }

  def fromAnalysisAndPov(pov: Game.SideAndStart, analysis: Analysis): List[AccuracyPercent] =
    fromEvalsAndPov(pov, analysis.infos.map(_.eval))

  def gameAccuracy(startColor: Color, analysis: Analysis): Option[Color.Map[AccuracyPercent]] =
    gameAccuracy(startColor, analysis.infos.map(_.eval).flatMap(_.forceAsCp))

  def gameAccuracy(startColor: Color, cps: List[Cp]): Option[Color.Map[AccuracyPercent]] = {
    val allWinPercents = (Cp.initial :: cps) map WinPercent.fromCentiPawns
    allWinPercents.headOption flatMap { firstWinPercent =>
      val windowSize          = (cps.size / 10) atLeast 2 atMost 6
      val allWinPercentValues = allWinPercents.map(_.value)
      val windows =
        List
          .fill((windowSize).atMost(allWinPercentValues.size) - 1)(allWinPercentValues take windowSize)
          .toList ::: allWinPercentValues.sliding(windowSize).toList
      val weights = windows map { xs => ~Maths.standardDeviation(xs) atLeast 1 }
      val weightedAccuracies: Iterable[((Double, Double), Color)] = allWinPercents
        .sliding(2)
        .zip(weights)
        .zipWithIndex
        .collect { case ((List(prev, next), weight), i) =>
          val color    = Color.fromWhite((i % 2 == 0) == startColor.white)
          val accuracy = AccuracyPercent.fromWinPercents(color.fold(prev, next), color.fold(next, prev)).value
          ((accuracy, weight), color)
        }
        .to(Iterable)

      def colorAccuracy(color: Color) = Maths.weightedMean {
        weightedAccuracies collect {
          case (weightedAccuracy, c) if c == color => weightedAccuracy
        }
      } map AccuracyPercent.apply

      for {
        wa <- colorAccuracy(Color.white)
        ba <- colorAccuracy(Color.black)
      } yield Color.Map(wa, ba)
    }
  }
}
