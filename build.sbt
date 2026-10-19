name := "hello-zio-typesafe-ai"

scalaVersion := "3.10.0"

libraryDependencies ++= Seq(
  "com.jamesward" %% "zio-bedrock" % "0.1.0",
  "com.jamesward" %% "zio-typesafe-ai" % "0.1.0",
  "com.jamesward" %% "zio-http-mcp" % "0.10.0",

  "dev.zio" %% "zio-test" % "2.1.27" % Test,
  "dev.zio" %% "zio-test-sbt" % "2.1.27" % Test,
)

fork := true

// SkillsJars: extract agent Skills with `./sbta extractSkillsJars`
skillsJarsOutputDir := Some(file(".kiro/skills"))

libraryDependencies += "com.jamesward" % "skills" % "0.0.17" % Skills
