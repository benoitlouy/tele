import Dependencies._
import ReleaseTransformations._
import xerial.sbt.Sonatype._

val Scala212 = "2.12.15"
val Scala213 = "2.13.7"
val Scala3 = "3.1.0"
val ScalaVersions = Seq(Scala3, Scala212, Scala213)

ThisBuild / scalaVersion := Scala212

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

def kinesisDependencies(proj: Project): Project = {
  proj.settings(
    libraryDependencies ++= {
      if (virtualAxes.value.contains(VirtualAxis.jvm))
        Seq(
          D.kinesis,
          D.kinesisClient,
          D.logback % Test
        )
      else Seq()
    }
  )
}

lazy val tele = projectMatrix
  .jvmPlatform(scalaVersions = ScalaVersions)
  .configure(kinesisDependencies)
  .settings(
    publishSettings,
    libraryDependencies ++= Seq(
      D.collectionCompat.value,
      D.catsEffect.value,
      D.fs2.value,
      D.munitCatsEffect.value % Test,
      D.circeCore.value % Test,
      D.circeParser.value % Test
    )
  )

lazy val docs =
  projectMatrix
    .in(file("tele-docs"))
    .jvmPlatform(scalaVersions = ScalaVersions)
    .enablePlugins(MdocPlugin)
    .settings(noPublishSettings)
    .settings(
      libraryDependencies ++= Seq(
        D.circeCore.value,
        D.circeParser.value,
        D.circeGeneric.value
      ),
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
  .aggregate(tele.projectRefs: _*)

val publishSettings: Seq[Setting[_]] = Seq(
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
    releaseStepInputTask(docs.finder().apply(Scala212) / mdoc),
    releaseStepCommand("git add README.md"),
    releaseStepCommand("""git commit -m "update README""""),
    tagRelease,
    releaseStepCommandAndRemaining("publishSigned"),
    releaseStepCommand("sonatypeBundleRelease"),
    setNextVersion,
    commitNextVersion,
    pushChanges
  )
)

val noPublishSettings = Seq(
  publish / skip := true,
  publishLocal / skip := true,
  publishArtifact := false
)

usePgpKeyHex("6B4EA2C6E2FC6A8EC9E8102EAA1014BFFF5A28BF")
