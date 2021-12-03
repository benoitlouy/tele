import Dependencies._
import ReleaseTransformations._
import xerial.sbt.Sonatype._

val Scala212 = "2.12.15"
val Scala213 = "2.13.7"
val Scala3 = "3.1.0"

ThisBuild / scalaVersion := Scala213
ThisBuild / crossScalaVersions := Seq(Scala3, Scala213, Scala212)

ThisBuild / organization := "io.github.benoitlouy"
ThisBuild / organizationName := "Benoit Louy"
ThisBuild / startYear := Some(2021)
ThisBuild / licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / description := "Kinesis client providing fs2 interfaces to produce and consumer from Kinesis streams"
ThisBuild / homepage := Some(url("https://github.com/benoitlouy/tele"))
ThisBuild / developers := List(
  Developer(
    id = "benoitlouy",
    name = "Benoit Louy",
    email = "benoit.louy+oss@fastmail.com",
    url = url("https://github.com/benoitlouy")
  )
)

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

lazy val tele = project
  .settings(
    publishSettings,
    kindProjectorSettings,
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
    .settings(noPublishSettings)
    .settings(
      libraryDependencies ++= Seq(L.circeCore, L.circeParser, L.circeGeneric),
      mdocOut := (ThisBuild / baseDirectory).value,
      mdocVariables := Map(
        "ORGANIZATION" -> organization.value,
        "NAME" -> (root / name).value,
        "VERSION" -> version.value
      )
    )
    .dependsOn(tele)

lazy val root = project
  .in(file("."))
  .settings(publishSettings)
  .settings(noPublishSettings)
  .settings(
    name := "tele"
  )
  .aggregate(tele)

val kindProjectorSettings = Seq(scalacOptions ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((3, _)) => Seq("-Ykind-projector:underscores")
    case Some((2, 13)) | Some((2, 12)) => Seq("-Xsource:3", "-P:kind-projector:underscore-placeholders")
    case _ => Nil
  }
})

val publishSettings = Seq(
  publishMavenStyle := true,
  pomIncludeRepository := { _ => false },
  publishTo := sonatypePublishToBundle.value,
  sonatypeCredentialHost := "s01.oss.sonatype.org",
  sonatypeProfileName := "io.github.benoitlouy",
  sonatypeProjectHosting := Some(
    GitHubHosting(user = "benoitlouy", repository = "tele", email = "benoit.louy+oss@fastmail.com")
  ),
  Test / publishArtifact := false,
  releaseTagName := s"v${version.value}",
  releaseVcsSign := true,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  releaseCrossBuild := true,
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    releaseStepCommandAndRemaining("+publishSigned"),
    setNextVersion,
    commitNextVersion,
    releaseStepCommand("sonatypeBundleRelease"),
    pushChanges
  )
)

val noPublishSettings = Seq(
  publish / skip := true,
  publishLocal / skip := true,
  publishArtifact := false
)

usePgpKeyHex("6B4EA2C6E2FC6A8EC9E8102EAA1014BFFF5A28BF")
