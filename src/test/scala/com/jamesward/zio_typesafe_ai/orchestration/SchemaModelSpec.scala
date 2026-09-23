package com.jamesward.zio_typesafe_ai.orchestration

import zio.json.ast.Json
import zio.test.*

object SchemaModelSpec extends ZIOSpecDefault:
  private val string = Json.Obj("type" -> Json.Str("string"))
  private val integer = Json.Obj("type" -> Json.Str("integer"))

  def spec = suite("SchemaModel")(
    test("required defaults to empty and compatibility is exact and conservative") {
      val optional = Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj("name" -> string))
      val nullableString = Json.Obj("type" -> Json.Arr(Json.Str("string"), Json.Str("null")))
      assertTrue(
        SchemaModel.required(optional).isEmpty,
        SchemaModel.compatible(string, nullableString, string, nullableString),
        !SchemaModel.compatible(string, integer, string, integer),
        !SchemaModel.compatible(
          Json.Obj("oneOf" -> Json.Arr(string, integer)), string,
          Json.Obj("oneOf" -> Json.Arr(string, integer)), string,
        ),
      )
    },
    test("indexes nested leaves and array sources in stable path order") {
      val schema = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj(
          "meta" -> Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj("token" -> string)),
          "rows" -> Json.Obj("type" -> Json.Str("array"), "items" -> Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj("id" -> string))),
        ),
      )
      val indexed = SchemaModel.indexOutput(schema)
      assertTrue(
        indexed.map(_.path.pointer) == Vector("/meta", "/meta/token", "/rows"),
        indexed.last.isArray,
      )
    },
    test("projection is strict and preserves definitions used by local refs") {
      val schema = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj(
          "id" -> Json.Obj("$ref" -> Json.Str("#/$defs/Identifier")),
          "ignored" -> string,
        ),
        "$defs" -> Json.Obj("Identifier" -> string),
        "required" -> Json.Arr(Json.Str("id")),
      )
      val projected = SchemaModel.projectRequired(schema, Vector("id"))
      assertTrue(
        projected.get("additionalProperties").flatMap(_.asBoolean).contains(false),
        SchemaModel.properties(projected).map(_._1) == Vector("id"),
        projected.get("$defs").nonEmpty,
        SchemaModel.validate(Json.Obj("id" -> Json.Str("x")), projected).isRight,
      )
    },
    test("validator reports required, nested type, enum, ref, and additional-property paths") {
      val schema = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj(
          "count" -> integer,
          "mode" -> Json.Obj("type" -> Json.Str("string"), "enum" -> Json.Arr(Json.Str("safe"))),
          "nested" -> Json.Obj(
            "type" -> Json.Str("object"),
            "properties" -> Json.Obj("id" -> Json.Obj("$ref" -> Json.Str("#/$defs/Identifier"))),
            "required" -> Json.Arr(Json.Str("id")),
            "additionalProperties" -> Json.Bool(false),
          ),
        ),
        "$defs" -> Json.Obj("Identifier" -> string),
        "required" -> Json.Arr(Json.Str("count"), Json.Str("nested")),
        "additionalProperties" -> Json.Bool(false),
      )
      val invalid = Json.Obj(
        "count" -> Json.Num(1.5),
        "mode" -> Json.Str("unsafe"),
        "nested" -> Json.Obj("id" -> Json.Num(4), "extra" -> Json.Bool(true)),
        "extraRoot" -> Json.Str("x"),
      )
      val errors = SchemaModel.validate(invalid, schema).left.toOption.toVector.flatMap(_.map(_.path)).toSet
      assertTrue(
        errors.contains("/count"), errors.contains("/mode"), errors.contains("/nested/id"),
        errors.contains("/nested/extra"), errors.contains("/extraRoot"),
        SchemaModel.validate(Json.Obj("count" -> Json.Num(2), "nested" -> Json.Obj("id" -> Json.Str("ok"))), schema).isRight,
      )
    },
    test("anyOf, oneOf, allOf, const, and arrays are locally enforced") {
      val schema = Json.Obj(
        "type" -> Json.Str("array"),
        "items" -> Json.Obj("anyOf" -> Json.Arr(
          Json.Obj("const" -> Json.Str("x")),
          Json.Obj("type" -> Json.Str("integer")),
        )),
      )
      assertTrue(
        SchemaModel.validate(Json.Arr(Json.Str("x"), Json.Num(2)), schema).isRight,
        SchemaModel.validate(Json.Arr(Json.Bool(true)), schema).isLeft,
      )
    },
  )
