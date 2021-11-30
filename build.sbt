import Dependencies._

val Scala212 = "2.12.15"
val Scala213 = "2.13.7"
val Scala3 = "3.1.0"

ThisBuild / scalaVersion := Scala213
ThisBuild / crossScalaVersions := Seq(Scala3, Scala213, Scala212)
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "io.github.benoitlouy"
ThisBuild / organizationName := "Benoit Louy"
ThisBuild / startYear := Some(2021)
ThisBuild / licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

ThisBuild / Test / testOptions += Tests.Argument(
  new TestFramework("munit.Framework"),
  "+l"
)

val isDotty = Def.setting(
  CrossVersion.partialVersion(scalaVersion.value).exists(_._1 == 3)
)

lazy val core = project.settings(
  libraryDependencies ++= Seq(
    L.collectionCompat,
    L.kinesis,
    L.kinesisClient,
    L.catsEffect,
    L.fs2,
    L.munitCatsEffect % Test,
    L.logback % Test,
    L.circeCore % Test,
    L.circeParser % Test
  ) ++ (
    if (isDotty.value) Nil
    else
      Seq(compilerPlugin(L.kindProjector.cross(CrossVersion.full)))
  )
)

lazy val docs =
  project
    .in(file("tele-docs"))
    .enablePlugins(MdocPlugin)
    .settings(
      libraryDependencies ++= Seq(L.circeCore, L.circeParser, L.circeGeneric),
      mdocOut := (ThisBuild / baseDirectory).value,
      mdocVariables := Map(
        "ORGANIZATION" -> organization.value,
        "NAME" -> (root / name).value,
        "VERSION" -> version.value
      )
    )
    .dependsOn(core)

lazy val root = project
  .in(file("."))
  .settings(
    name := "tele"
  )
  .aggregate(core)
