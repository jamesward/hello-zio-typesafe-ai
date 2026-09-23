package com.jamesward.zio_typesafe_ai

import com.jamesward.zio_typesafe_ai.TypeSafeAI.{Client, Error, ModelId}
import com.jamesward.zio_typesafe_ai.internal.Wire
import zio.*

import scala.collection.immutable.Queue

object AppTypeSafeAIMock:
  def choices(values: String*): ULayer[Client] =
    ZLayer.fromZIO:
      Ref.make(Queue.from(values)).map: pending =>
        new Client:
          val modelId: ModelId = ModelId("app-test")

          def send(request: Wire.SystemOneRequest): IO[Error, Wire.SystemOneResponse] =
            pending.modify: queue =>
              queue.dequeueOption match
                case Some((choice, remaining)) => Some(choice) -> remaining
                case None                      => None -> queue
            .flatMap:
              case Some(choice) =>
                ZIO.succeed(Wire.SystemOneResponse(
                  model = modelId.unwrap,
                  answers = Map("next_action" -> Wire.Answer.Choice(
                    choice = choice,
                    probabilities = Map(choice -> 1.0),
                    confidence = 1.0,
                  )),
                  usage = Wire.Usage(1, 1),
                ))
              case None =>
                ZIO.fail(Error.Unexpected(
                  zio.http.Status.InternalServerError,
                  "App TypeSafeAI mock script exhausted",
                ))

  case class Observed(layer: ULayer[Client], requestBodies: UIO[Vector[zio.json.ast.Json]])

  def choicesObserved(values: String*): UIO[Observed] =
    for
      pending <- Ref.make(Queue.from(values))
      seen <- Ref.make(Vector.empty[Wire.SystemOneRequest])
    yield Observed(
      ZLayer.succeed(new Client:
        val modelId: ModelId = ModelId("app-test")
        def send(request: Wire.SystemOneRequest): IO[Error, Wire.SystemOneResponse] =
          seen.update(_ :+ request) *> pending.modify: queue =>
            queue.dequeueOption match
              case Some((choice, remaining)) => Some(choice) -> remaining
              case None                      => None -> queue
          .flatMap:
            case Some(choice) => ZIO.succeed(Wire.SystemOneResponse(
              model = modelId.unwrap,
              answers = Map("next_action" -> Wire.Answer.Choice(choice, Map(choice -> 1.0), 1.0)),
              usage = Wire.Usage(1, 1),
            ))
            case None => ZIO.fail(Error.Unexpected(zio.http.Status.InternalServerError, "App TypeSafeAI mock script exhausted"))
      ),
      seen.get.map(_.map(_.body)),
    )
  enum ScriptedResponse:
    case Choice(value: String)
    case Nouls(probabilities: Vector[Double])

  def scriptedObserved(responses: ScriptedResponse*): UIO[Observed] =
    for
      pending <- Ref.make(Queue.from(responses))
      seen <- Ref.make(Vector.empty[Wire.SystemOneRequest])
    yield Observed(
      ZLayer.succeed(new Client:
        val modelId: ModelId = ModelId("app-test")
        def send(request: Wire.SystemOneRequest): IO[Error, Wire.SystemOneResponse] =
          seen.update(_ :+ request) *> pending.modify: queue =>
            queue.dequeueOption match
              case Some((response, remaining)) => Some(response) -> remaining
              case None                        => None -> queue
          .flatMap:
            case Some(ScriptedResponse.Choice(choice)) => ZIO.succeed(Wire.SystemOneResponse(
              model = modelId.unwrap,
              answers = Map("next_action" -> Wire.Answer.Choice(choice, Map(choice -> 1.0), 1.0)),
              usage = Wire.Usage(1, 1),
            ))
            case Some(ScriptedResponse.Nouls(probabilities)) => ZIO.succeed(Wire.SystemOneResponse(
              model = modelId.unwrap,
              answers = probabilities.zipWithIndex.map((probability, index) => s"item_$index" -> Wire.Answer.Noul(probability)).toMap,
              usage = Wire.Usage(probabilities.size, probabilities.size),
            ))
            case None => ZIO.fail(Error.Unexpected(zio.http.Status.InternalServerError, "App TypeSafeAI mock script exhausted"))
      ),
      seen.get.map(_.map(_.body)),
    )

  def noulObserved(probabilityForIndex: Int => Double): UIO[Observed] =
    for
      seen <- Ref.make(Vector.empty[Wire.SystemOneRequest])
    yield Observed(
      ZLayer.succeed(new Client:
        val modelId: ModelId = ModelId("app-test")
        def send(request: Wire.SystemOneRequest): IO[Error, Wire.SystemOneResponse] =
          val questionIds = request.body.asObject.flatMap(_.get("questions")).flatMap(_.asObject)
            .map(_.fields.map(_._1).toVector).getOrElse(Vector.empty)
          seen.update(_ :+ request).as(Wire.SystemOneResponse(
            model = modelId.unwrap,
            answers = questionIds.map: id =>
              val index = id.stripPrefix("item_").toInt
              id -> Wire.Answer.Noul(probabilityForIndex(index))
            .toMap,
            usage = Wire.Usage(questionIds.size, questionIds.size),
          ))
      ),
      seen.get.map(_.map(_.body)),
    )

  def noulObservedForRequest(probability: (zio.json.ast.Json, Int) => Double): UIO[Observed] =
    for
      seen <- Ref.make(Vector.empty[Wire.SystemOneRequest])
    yield Observed(
      ZLayer.succeed(new Client:
        val modelId: ModelId = ModelId("app-test")
        def send(request: Wire.SystemOneRequest): IO[Error, Wire.SystemOneResponse] =
          val questionIds = request.body.asObject.flatMap(_.get("questions")).flatMap(_.asObject)
            .map(_.fields.map(_._1).toVector).getOrElse(Vector.empty)
          seen.update(_ :+ request).as(Wire.SystemOneResponse(
            model = modelId.unwrap,
            answers = questionIds.map: id =>
              val index = id.stripPrefix("item_").toInt
              id -> Wire.Answer.Noul(probability(request.body, index))
            .toMap,
            usage = Wire.Usage(questionIds.size, questionIds.size),
          ))
      ),
      seen.get.map(_.map(_.body)),
    )
