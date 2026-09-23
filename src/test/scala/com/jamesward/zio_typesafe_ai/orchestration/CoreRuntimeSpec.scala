package com.jamesward.zio_typesafe_ai.orchestration

import com.jamesward.zio_typesafe_ai.orchestration.model.*
import com.jamesward.zio_typesafe_ai.orchestration.runtime.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object CoreRuntimeSpec extends ZIOSpecDefault:
  private val rejectingOperations = new OperationInvoker:
    def call(operation: String, arguments: Json.Obj): Task[Json] =
      ZIO.dieMessage(s"unexpected operation $operation")

  private def run(result: Expr): Task[ExecutionReport] =
    WorkflowRuntime.execute(Workflow(Vector.empty, result), Json.Obj(), rejectingOperations)

  def spec = suite("neutral workflow indexed expressions")(
    test("At serializes, carries source dependencies, and projects index then object fields") {
      val projected = Expr.At(Expr.Ref("source"), 0, List("result", "version"))
      val workflow = Workflow(
        Vector(
          Step.Construct("projected", projected),
          Step.Construct("source", Expr.Literal(Json.Arr(
            Json.Obj("result" -> Json.Obj("version" -> Json.Str("1.2.3"))),
          ))),
        ),
        Expr.Ref("projected"),
      )
      for
        report <- WorkflowRuntime.execute(workflow, Json.Obj(), rejectingOperations)
      yield assertTrue(
        report.output == Json.Str("1.2.3"),
        workflow.toJson.toJson.contains("\"at\""),
        workflow.toJson.toJson.contains("\"index\":0"),
      )
    },
    test("At preserves MissingReference from its source") {
      run(Expr.At(Expr.Ref("absent"), 0, Nil)).either.map: result =>
        assertTrue(result.left.toOption.exists(_.isInstanceOf[ExecutionError.MissingReference]))
    },
    test("At rejects a non-array source") {
      run(Expr.At(Expr.Literal(Json.Obj()), 0, Nil)).either.map: result =>
        assertTrue(result.left.toOption.exists(_.isInstanceOf[ExecutionError.ExpectedArray]))
    },
    test("At rejects negative and out-of-bounds indexes") {
      for
        negative <- run(Expr.At(Expr.Literal(Json.Arr(Json.Str("x"))), -1, Nil)).either
        beyond <- run(Expr.At(Expr.Literal(Json.Arr(Json.Str("x"))), 1, Nil)).either
      yield assertTrue(
        negative.left.toOption.exists(_.isInstanceOf[ExecutionError.InvalidIndex]),
        beyond.left.toOption.exists(_.isInstanceOf[ExecutionError.IndexOutOfBounds]),
      )
    },
    test("At reports a missing projected object path") {
      run(Expr.At(Expr.Literal(Json.Arr(Json.Obj("present" -> Json.Str("x")))), 0, List("missing"))).either.map: result =>
        assertTrue(result.left.toOption.exists(_.isInstanceOf[ExecutionError.MissingAtPath]))
    },
  )
