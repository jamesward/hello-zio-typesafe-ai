package com.jamesward.zio_typesafe_ai.orchestration

import com.jamesward.zio_typesafe_ai.{AppTypeSafeAIMock, TypeSafeAI}
import com.jamesward.zio_typesafe_ai.TypeSafeAI.Client
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object JevVariantFilterSpec extends ZIOSpecDefault:
  private val string = Json.Obj("type" -> Json.Str("string"))
  private val itemSchema = Json.Obj(
    "type" -> Json.Str("object"),
    "properties" -> Json.Obj("name" -> string, "kind" -> string),
  )

  def spec = suite("Jev-first variant filtering")(
    test("projects distinct scalar variants and applies selected membership to the complete source") {
      val source = Vector(
        Json.Obj("name" -> Json.Str("Alpha"), "kind" -> Json.Str("CLASS")),
        Json.Obj("name" -> Json.Str("Target"), "kind" -> Json.Str("Class")),
        Json.Obj("name" -> Json.Str("Target"), "kind" -> Json.Str("CLASS")),
      )
      val prepared = JevVariantFilter.prepare(source, itemSchema)
      val selected = prepared.variants.filter(_.projection.toJson.toLowerCase.contains("target")).map(_.projection)
      val predicate = PredicateFilter.variantMembership(prepared.paths, selected)
      val evaluation = PredicateFilter.classify(source, predicate, hostGuard = 10).toOption.get
      val canonical = PredicateFilter.canonical(predicate)
      val replayed = PredicateFilter.parseAndValidate(canonical, itemSchema).toOption.get
      assertTrue(
        prepared.variants.size == 2,
        evaluation.matches == source.drop(1),
        source.filter(PredicateFilter.evaluate(replayed, _)) == source.drop(1),
        canonical.get("_hostType").flatMap(_.asString).contains("filter_variant_membership_v1"),
      )
    },
    test("one Jev Noul batch selects multiple variants and records separate usage") {
      val source = Vector.tabulate(3)(index => Json.Obj(
        "name" -> Json.Str(s"item-$index"), "kind" -> Json.Str("CLASS"),
      ))
      val prepared = JevVariantFilter.prepare(source, itemSchema)
      import AppTypeSafeAIMock.ScriptedResponse
      for
        observed <- AppTypeSafeAIMock.scriptedObserved(
          ScriptedResponse.Nouls(Vector(0.1, 0.9)),
          ScriptedResponse.Nouls(Vector(0.1, 0.9, 0.9)),
        )
        client <- ZIO.service[Client].provideLayer(observed.layer)
        tracker <- JevFilterTracker.make
        selection <- JevVariantFilter.select(
          "select items one and two", prepared, client,
          tracker, maxParallelism = 2, PredicateFilter.SynthesisRequest("select", itemSchema, None, None),
        )
        metrics <- tracker.metrics
        bodies <- observed.requestBodies
        evaluation <- ZIO.fromEither(PredicateFilter.classify(source, selection.predicate, hostGuard = 10))
      yield assertTrue(
        selection.batchCount == 2,
        evaluation.matches == source.drop(1),
        metrics == JevFilterMetrics(calls = 2, inputTokens = 5, outputTokens = 5, timeMs = metrics.timeMs),
        bodies.lift(1).exists(body => body.toJson.contains("/name") && !body.toJson.contains("/kind")),
      )
    },
    test("more than 255 variants execute complete Jev batches in parallel") {
      val source = Vector.tabulate(256)(index => Json.Obj(
        "name" -> Json.Str(s"variant-$index"), "kind" -> Json.Str("CLASS"),
      ))
      val prepared = JevVariantFilter.prepare(source, itemSchema)
      for
        observed <- AppTypeSafeAIMock.noulObserved(_ => 0.9)
        client <- ZIO.service[Client].provideLayer(observed.layer)
        tracker <- JevFilterTracker.make
        selection <- JevVariantFilter.select(
          "select every variant", prepared, client, tracker,
          maxParallelism = 2, PredicateFilter.SynthesisRequest("select", itemSchema, None, None),
        )
        metrics <- tracker.metrics
        evaluation <- ZIO.fromEither(PredicateFilter.classify(source, selection.predicate, hostGuard = 300))
      yield assertTrue(
        selection.batchCount == 3,
        evaluation.matches == source,
        metrics.calls == 3,
        metrics.inputTokens == 258,
        metrics.outputTokens == 258,
      )
    },
    test("multi-batch recall consolidates preliminary positives in one stricter Jev pass") {
      val source = Vector.tabulate(300)(index => Json.Obj(
        "name" -> Json.Str(index match
          case 0 => "topic.Validator"
          case 1 => "topic.Validator.Base"
          case _ => s"variant-$index"
        ),
        "kind" -> Json.Str("CLASS"),
      ))
      val prepared = JevVariantFilter.prepare(source, itemSchema)
      for
        observed <- AppTypeSafeAIMock.noulObservedForRequest: (body, index) =>
          val mode = body.asObject.flatMap(_.get("state")).flatMap(_.asObject)
            .flatMap(_.get("filterMode")).flatMap(_.asString).getOrElse("")
          mode match
            case "field_relevance"      => if index == 0 then 0.9 else 0.1
            case "variant_recall"       => if index < 3 then 0.9 else 0.1
            case "variant_consolidation" => if index == 1 then 0.9 else 0.1
            case _                       => 0.1
        client <- ZIO.service[Client].provideLayer(observed.layer)
        tracker <- JevFilterTracker.make
        selection <- JevVariantFilter.select(
          "select central variants", prepared, client, tracker, maxParallelism = 2,
          PredicateFilter.SynthesisRequest("select", itemSchema, None, None),
        )
        metrics <- tracker.metrics
        evaluation <- ZIO.fromEither(PredicateFilter.classify(source, selection.predicate, hostGuard = 12))
        bodies <- observed.requestBodies
      yield assertTrue(
        selection.batchCount == 4,
        metrics.calls == 4,
        evaluation.matches == source.take(2),
        bodies.count(_.toJson.contains("\"filterMode\":\"variant_recall\"")) == 2,
        bodies.count(_.toJson.contains("\"filterMode\":\"variant_consolidation\"")) == 1,
        bodies.exists(body =>
          body.toJson.contains("Evaluate each preliminary positive independently") &&
            body.toJson.contains("do not retain only the single most central variant")
        ),
      )
    },
    test("LLM fallback evidence is deterministic, bounded, mixed, and explicitly incomplete") {
      val source = Vector.tabulate(400)(index => Json.Obj(
        "name" -> Json.Str(if index % 50 == 0 then s"polymorphic-validator-$index" else s"ordinary-$index"),
        "kind" -> Json.Str("CLASS"),
      ))
      val prepared = JevVariantFilter.prepare(source, itemSchema)
      val first = JevVariantFilter.llmEvidence("polymorphic validation", source.size, prepared)
      val second = JevVariantFilter.llmEvidence("polymorphic validation", source.size, prepared)
      assertTrue(
        first == second,
        first.sourceCount == 400,
        first.variantCount == 400,
        !first.complete,
        first.variants.elements.size == JevVariantFilter.MaxLlmEvidenceVariants,
        first.variants.toJson.contains("polymorphic-validator"),
        first.strategy == "prompt-linked-plus-stable-uniform",
        OrchestrationConfig().jevFilterVariantThreshold == 255,
        JevVariantFilter.route(255, 255) == JevVariantFilter.Route.Jev,
        JevVariantFilter.route(256, 255) == JevVariantFilter.Route.Llm,
        JevVariantFilter.route(256, 300) == JevVariantFilter.Route.Jev,
        JevVariantFilter.route(301, 300) == JevVariantFilter.Route.Llm,
      )
    },
  )
