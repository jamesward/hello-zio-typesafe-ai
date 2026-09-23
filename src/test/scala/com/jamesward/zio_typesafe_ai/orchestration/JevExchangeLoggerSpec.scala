package com.jamesward.zio_typesafe_ai.orchestration

import com.jamesward.zio_typesafe_ai.TypeSafeAI
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object JevExchangeLoggerSpec extends ZIOSpecDefault:
  private val forbidden = Set(
    "schema", "itemSchema", "targetInputSchema", "properties",
  )

  private val schema = Json.Obj(
    "type" -> Json.Str("object"),
    "description" -> Json.Str("secret schema detail"),
    "properties" -> Json.Obj("secret" -> Json.Obj("type" -> Json.Str("string"))),
  )

  private val operation = Json.Obj(
    "name" -> Json.Str("list_symbols"),
    "kind" -> Json.Str("Mcp"),
    "description" -> Json.Str("long tool description"),
    "inputSchema" -> schema,
    "outputSchema" -> schema,
    "unknownOperationField" -> Json.Str("drop me"),
  )

  private val planRequest = Json.Obj(
    "model" -> Json.Str("jev-latest"),
    "unknownRoot" -> Json.Str("drop me"),
    "state" -> Json.Obj(
      "prompt" -> Json.Str("Find the API"),
      "operationCatalog" -> Json.Arr(operation),
      "partialWorkflow" -> Json.Arr(
        Json.Obj(
          "id" -> Json.Str("s000"),
          "kind" -> Json.Str("call"),
          "operation" -> Json.Str("resolve"),
          "arguments" -> Json.Obj("query" -> Json.Str("secret runtime argument")),
        ),
      ),
      "evidenceCount" -> Json.Num(1),
      "runtimeValues" -> Json.Arr(Json.Str("drop me")),
    ),
    "questions" -> Json.Obj(
      "next_action" -> Json.Obj(
        "type" -> Json.Str("choice"),
        "instructions" -> Json.Str("choose"),
        "criteria" -> Json.Obj(
          "c000" -> Json.Obj(
            "candidateId" -> Json.Str("c000"),
            "transition" -> Json.Str("invoke"),
            "operation" -> operation,
            "mode" -> Json.Str("Direct"),
            "source" -> Json.Null,
            "exactBindings" -> Json.Obj("query" -> Json.Str("checkpoint")),
            "missingRequiredArguments" -> Json.Arr(),
            "hostWillInsertExtraction" -> Json.Bool(false),
            "runtimeValues" -> Json.Arr(Json.Str("drop me")),
          ),
          "c001" -> Json.Obj(
            "transition" -> Json.Str("return_evidence"),
            "evidenceCount" -> Json.Num(1),
          ),
        ),
      ),
    ),
  )

  private val predicate = Json.Obj(
    "kind" -> Json.Str("atom"),
    "leaf" -> Json.Obj(
      "op" -> Json.Str("contains"),
      "path" -> Json.Arr(Json.Str("name")),
      "value" -> Json.Str("Json"),
      "schema" -> schema,
    ),
    "unknownPredicateField" -> Json.Str("drop me"),
  )

  private val noPlanRequest = Json.Obj(
    "model" -> Json.Str("jev-latest"),
    "state" -> Json.Obj(
      "prompt" -> Json.Str("Find JSON types"),
      "operationCatalog" -> Json.Arr(operation),
      "confirmedActions" -> Json.Arr(Json.Str("invoke:list_symbols:direct"), Json.Str("filter:symbols")),
      "available" -> Json.Arr(Json.Obj(
        "name" -> Json.Str("symbols"),
        "path" -> Json.Str("/symbols"),
        "isArray" -> Json.Bool(true),
        "schema" -> schema,
        "runtime" -> Json.Arr(Json.Str("a"), Json.Str("b")),
      )),
      "evidence" -> Json.Arr(Json.Obj(
        "operation" -> Json.Str("list_symbols"),
        "confirmed" -> Json.Bool(true),
        "output" -> Json.Arr(Json.Str("not retained")),
      )),
      "pendingFilterOutcome" -> Json.Obj(
        "evaluation" -> Json.Obj(
          "classification" -> Json.Str("TooBroad"),
          "sourceCount" -> Json.Num(500),
          "matchCount" -> Json.Num(20),
          "predicate" -> predicate,
          "source" -> Json.Arr(Json.Str("not retained")),
          "matches" -> Json.Arr(Json.Str("not retained")),
        ),
        "synthesisRejection" -> Json.Obj(
          "kind" -> Json.Str("invalid_predicate"),
          "message" -> Json.Str("unsupported generated criterion"),
          "rawCriteria" -> Json.Obj("secret" -> Json.Str("not retained")),
        ),
        "attemptsUsed" -> Json.Num(2),
        "parentSource" -> Json.Arr(Json.Str("not retained")),
      ),
    ),
    "questions" -> Json.Obj(
      "action" -> Json.Obj(
        "type" -> Json.Str("choice"),
        "criteria" -> Json.Obj(
          "c000" -> Json.Obj(
            "transition" -> Json.Str("filter_recovery"),
            "capability" -> Json.Str("synthesize_filter"),
            "action" -> Json.Str("refine"),
            "priorPredicate" -> predicate,
            "outcomeDiagnostics" -> Json.Obj(
              "classification" -> Json.Str("TooBroad"),
              "sourceCount" -> Json.Num(500),
              "matchCount" -> Json.Num(20),
              "message" -> Json.Str("strictly reduce"),
              "items" -> Json.Arr(Json.Str("not retained")),
            ),
            "synthesisRejection" -> Json.Obj(
              "kind" -> Json.Str("invalid_predicate"),
              "message" -> Json.Str("unsupported generated criterion"),
              "rawCriteria" -> Json.Obj("secret" -> Json.Str("not retained")),
            ),
            "nextAttempt" -> Json.Num(3),
          ),
          "c001" -> Json.Obj(
            "transition" -> Json.Str("invoke"),
            "operation" -> Json.Obj("name" -> Json.Str("get_symbol"), "kind" -> Json.Str("Mcp"), "schema" -> schema),
            "missingRequiredArguments" -> Json.Arr(Json.Str("version")),
          ),
        ),
      ),
    ),
  )

  private val response = Json.Obj(
    "model" -> Json.Str("jev-latest"),
    "answers" -> Json.Obj(
      "isReady" -> Json.Obj(
        "type" -> Json.Str("noul"),
        "noul" -> Json.Num(0.7),
        "explanation" -> Json.Str("drop me"),
      ),
      "action" -> Json.Obj(
        "type" -> Json.Str("choice"),
        "choice" -> Json.Str("c001"),
        "probabilities" -> Json.Obj("c000" -> Json.Num(0.1), "c001" -> Json.Num(0.9)),
        "confidence" -> Json.Num(0.8),
        "description" -> Json.Str("drop me"),
      ),
      "quality" -> Json.Obj(
        "type" -> Json.Str("score"),
        "score" -> Json.Num(1.75),
        "legend" -> Json.Obj("0" -> Json.Str("bad"), "1" -> Json.Str("good"), "2" -> Json.Str("best")),
        "probabilities" -> Json.Obj("0" -> Json.Num(0.1), "1" -> Json.Num(0.3), "2" -> Json.Num(0.6)),
        "confidence" -> Json.Num(0.95),
        "unknown" -> Json.Arr(Json.Str("drop me")),
      ),
    ),
    "usage" -> Json.Obj(
      "input_tokens" -> Json.Num(17),
      "output_tokens" -> Json.Num(9),
      "other" -> Json.Num(100),
    ),
    "schema" -> schema,
  )

  private def keys(value: Json): Set[String] = value match
    case Json.Obj(fields) => fields.map(_._1).toSet ++ fields.flatMap((_, child) => keys(child)).toSet
    case Json.Arr(values) => values.flatMap(keys).toSet
    case _                => Set.empty

  private def at(value: Json, path: String*): Option[Json] =
    path.foldLeft(Option(value))((current, name) => current.flatMap(_.asObject).flatMap(_.get(name)))

  def spec = suite("compact Jev exchange logger")(
    test("compacts current generic plan state and Choice options by allow-list") {
      val compact = JevExchangeLogger.compactRequest(planRequest)
      assertTrue(
        at(compact, "model").contains(Json.Str("jev-latest")),
        at(compact, "state", "prompt").contains(Json.Str("Find the API")),
        at(compact, "state", "operationCatalog").flatMap(_.asArray).flatMap(_.headOption)
          .flatMap(_.asObject).flatMap(_.get("name")).contains(Json.Str("list_symbols")),
        at(compact, "state", "partialWorkflow").contains(Json.Obj(
          "steps" -> Json.Arr(Json.Obj(
            "id" -> Json.Str("s000"),
            "kind" -> Json.Str("call"),
            "operation" -> Json.Str("resolve"),
          )),
        )),
        at(compact, "questions", "next_action", "type").contains(Json.Str("choice")),
        at(compact, "questions", "next_action", "instructions").contains(Json.Str("choose")),
        at(compact, "questions", "next_action", "criteria", "c000", "operation", "description")
          .contains(Json.Str("long tool description")),
        at(compact, "questions", "next_action", "criteria", "c000", "operation", "inputSchema", "fields")
          .flatMap(_.asArray).exists(_.nonEmpty),
        at(compact, "questions", "next_action", "criteria", "c000", "exactBindings", "query")
          .contains(Json.Str("checkpoint")),
        at(compact, "questions", "next_action", "criteria", "c000", "hostWillInsertExtraction")
          .contains(Json.Bool(false)),
        at(compact, "questions", "next_action", "criteria", "c001", "evidenceCount").contains(Json.Num(1)),
        forbidden.intersect(keys(compact)).isEmpty,
        !keys(compact).contains("unknownRoot"),
        !compact.toJson.contains("secret runtime"),
        !compact.toJson.contains("runtimeValues"),
      )
    },
    test("compacts generic no-plan checkpoint and filter recovery") {
      val compact = JevExchangeLogger.compactRequest(noPlanRequest)
      assertTrue(
        at(compact, "state", "prompt").contains(Json.Str("Find JSON types")),
        at(compact, "state", "confirmedActions").contains(
          Json.Arr(Json.Str("invoke:list_symbols:direct"), Json.Str("filter:symbols"))
        ),
        at(compact, "state", "available").flatMap(_.asArray).flatMap(_.headOption).contains(Json.Obj(
          "name" -> Json.Str("symbols"), "path" -> Json.Str("/symbols"), "isArray" -> Json.Bool(true),
        )),
        at(compact, "state", "evidence").flatMap(_.asArray).flatMap(_.headOption).contains(Json.Obj(
          "operation" -> Json.Str("list_symbols"), "confirmed" -> Json.Bool(true),
        )),
        at(compact, "state", "pendingFilterOutcome", "evaluation", "classification").contains(Json.Str("TooBroad")),
        at(compact, "state", "pendingFilterOutcome", "evaluation", "predicate").contains(predicateWithoutUnknowns),
        at(compact, "state", "pendingFilterOutcome", "synthesisRejection").contains(Json.Obj(
          "kind" -> Json.Str("invalid_predicate"),
          "message" -> Json.Str("unsupported generated criterion"),
        )),
        at(compact, "state", "pendingFilterOutcome", "attemptsUsed").contains(Json.Num(2)),
        at(compact, "questions", "action", "criteria", "c000", "outcomeDiagnostics", "message")
          .contains(Json.Str("strictly reduce")),
        at(compact, "questions", "action", "criteria", "c000", "priorPredicate").contains(predicateWithoutUnknowns),
        at(compact, "questions", "action", "criteria", "c000", "synthesisRejection").contains(Json.Obj(
          "kind" -> Json.Str("invalid_predicate"),
          "message" -> Json.Str("unsupported generated criterion"),
        )),
        at(compact, "questions", "action", "criteria", "c000", "nextAttempt").contains(Json.Num(3)),
        at(compact, "questions", "action", "criteria", "c001", "operation")
          .contains(Json.Obj("name" -> Json.Str("get_symbol"), "kind" -> Json.Str("Mcp"))),
        forbidden.intersect(keys(compact)).isEmpty,
        !compact.toJson.contains("not retained"),
      )
    },
    test("preserves Noul, Choice, Score, complete distributions, legend, and usage") {
      val compact = JevExchangeLogger.compactResponse(response)
      assertTrue(
        at(compact, "model").contains(Json.Str("jev-latest")),
        at(compact, "answers", "isReady").contains(Json.Obj(
          "type" -> Json.Str("noul"), "noul" -> Json.Num(0.7),
        )),
        at(compact, "answers", "action", "choice").contains(Json.Str("c001")),
        at(compact, "answers", "action", "probabilities")
          .contains(Json.Obj("c000" -> Json.Num(0.1), "c001" -> Json.Num(0.9))),
        at(compact, "answers", "action", "confidence").contains(Json.Num(0.8)),
        at(compact, "answers", "quality", "score").contains(Json.Num(1.75)),
        at(compact, "answers", "quality", "legend").contains(
          Json.Obj("0" -> Json.Str("bad"), "1" -> Json.Str("good"), "2" -> Json.Str("best"))
        ),
        at(compact, "answers", "quality", "probabilities").contains(
          Json.Obj("0" -> Json.Num(0.1), "1" -> Json.Num(0.3), "2" -> Json.Num(0.6))
        ),
        at(compact, "usage").contains(Json.Obj("input_tokens" -> Json.Num(17), "output_tokens" -> Json.Num(9))),
        forbidden.intersect(keys(compact)).isEmpty,
        !keys(compact).contains("explanation"),
        !keys(compact).contains("unknown"),
      )
    },
    test("emits one-line compact success and failure records") {
      for
        before <- ZTestLogger.logOutput.map(_.size)
        _ <- JevExchangeLogger.compact.observe(TypeSafeAI.ExchangeObservation(
          planRequest,
          TypeSafeAI.ExchangeOutcome.Success(response),
          12L,
        ))
        _ <- JevExchangeLogger.compact.observe(TypeSafeAI.ExchangeObservation(
          noPlanRequest,
          TypeSafeAI.ExchangeOutcome.Failure(Cause.fail(TypeSafeAI.Error.RateLimit("retry\nlater"))),
          15L,
        ))
        output <- ZTestLogger.logOutput.map(_.drop(before).map(_.message()))
      yield assertTrue(
        output.size == 2,
        output.forall(_.contains("Jev compact exchange")),
        output.forall(message => !message.contains("TypeSafe AI compact exchange")),
        output.head.contains("request="),
        output.head.contains("response="),
        output.head.contains("latencyMs=12"),
        output(1).contains("request="),
        output(1).contains("failure="),
        output(1).contains("latencyMs=15"),
        !output(1).contains("response="),
        output.forall(message => !message.contains('\n')),
        output.exists(_.contains("long tool description")),
        output.exists(_.contains("secret schema detail")),
      )
    },
    test("compacts internal Jev variant batches to counts without runtime values") {
      val request = Json.Obj(
        "model" -> Json.Str("jev-latest"),
        "state" -> Json.Obj(
          "prompt" -> Json.Str("find target"),
          "filterMode" -> Json.Str("variant_relevance"),
          "variants" -> Json.Arr(
            Json.Obj("id" -> Json.Str("v000"), "values" -> Json.Obj("/name" -> Json.Str("secret-a"))),
            Json.Obj("id" -> Json.Str("v001"), "values" -> Json.Obj("/name" -> Json.Str("secret-b"))),
          ),
        ),
        "questions" -> Json.Obj(
          "item_0" -> Json.Obj("type" -> Json.Str("noul"), "instructions" -> Json.Str("first")),
          "item_1" -> Json.Obj("type" -> Json.Str("noul"), "instructions" -> Json.Str("second")),
        ),
      )
      val compact = JevExchangeLogger.compactRequest(request)
      assertTrue(
        at(compact, "state", "filterMode").contains(Json.Str("variant_relevance")),
        at(compact, "state", "variantsCount").contains(Json.Num(2)),
        at(compact, "questions", "count").contains(Json.Num(2)),
        !compact.toJson.contains("secret-a"),
        !compact.toJson.contains("first"),
      )
    },
    test("unknown and malformed inputs are total and redact runtime arrays") {
      val untrusted = Json.Arr(Vector.fill(32)(Json.Obj(
        "name" -> Json.Str("value"),
        "path" -> Json.Str("/value"),
        "isArray" -> Json.Bool(false),
        "schema" -> schema,
        "payload" -> Json.Arr(Vector.fill(10)(Json.Str("secret"))*),
      ))*)
      val malformed = Json.Obj(
        "model" -> untrusted,
        "state" -> Json.Obj(
          "prompt" -> Json.Num(42),
          "operationCatalog" -> untrusted,
          "available" -> untrusted,
          "confirmedActions" -> untrusted,
          "evidence" -> untrusted,
        ),
        "questions" -> untrusted,
      )
      val compact = JevExchangeLogger.compactRequest(malformed)
      assertTrue(
        at(compact, "model").isEmpty,
        at(compact, "state", "confirmedActions").contains(Json.Arr()),
        at(compact, "questions").contains(Json.Obj()),
        forbidden.intersect(keys(compact)).isEmpty,
        !compact.toJson.contains("secret"),
        JevExchangeLogger.compactRequest(Json.Str("bad")) == Json.Obj(),
        JevExchangeLogger.compactResponse(Json.Arr()) == Json.Obj(),
        JevExchangeLogger.selected(false) eq JevExchangeLogger.compact,
        JevExchangeLogger.selected(true) eq com.jamesward.zio_typesafe_ai.TypeSafeAI.ExchangeObserver.logging,
      )
    },
  )

  private val predicateWithoutUnknowns = Json.Obj(
    "kind" -> Json.Str("atom"),
    "leaf" -> Json.Obj(
      "op" -> Json.Str("contains"),
      "path" -> Json.Arr(Json.Str("name")),
      "value" -> Json.Str("Json"),
    ),
  )
