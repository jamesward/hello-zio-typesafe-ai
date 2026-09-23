name := "hello-zio-typesafe-ai"

scalaVersion := "3.9.0"

libraryDependencies ++= Seq(
  "com.jamesward" %% "zio-bedrock" % "0.1.0",
  "com.jamesward" %% "zio-typesafe-ai" % "0.1.0",
  "com.jamesward" %% "zio-http-mcp" % "0.8.2",

  "dev.zio" %% "zio-test" % "2.1.26" % Test,
  "dev.zio" %% "zio-test-sbt" % "2.1.26" % Test,
)

fork := true
