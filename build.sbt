ThisBuild / scalaVersion := "2.13.7"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "tele"
ThisBuild / organizationName := "Benoit Louy"
ThisBuild / startYear := Some(2021)
ThisBuild / licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))

lazy val core = project.settings(
  libraryDependencies ++= Seq(
    "software.amazon.awssdk" % "kinesis" % "2.17.88",
    "org.typelevel" %% "cats-effect" % "3.2.9"
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(core)
