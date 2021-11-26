val Scala212 = "2.12.15"
val Scala213 = "2.13.7"
val Scala3 = "3.0.2"

ThisBuild / scalaVersion := Scala213
ThisBuild / crossScalaVersions := Seq(Scala3, Scala213, Scala212)
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "tele"
ThisBuild / organizationName := "Benoit Louy"
ThisBuild / startYear := Some(2021)
ThisBuild / licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))

lazy val core = project.settings(
  libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.6.0",
    "software.amazon.awssdk" % "kinesis" % "2.17.88",
    "software.amazon.kinesis" % "amazon-kinesis-client" % "2.3.9",
    "org.typelevel" %% "cats-effect" % "3.2.9",
    "co.fs2" %% "fs2-core" % "3.2.2",
    "org.typelevel" %% "munit-cats-effect-3" % "1.0.6" % Test,
    "ch.qos.logback" % "logback-classic" % "1.2.7" % Test
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(core)
