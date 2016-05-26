import sbt._
import Process._
import com.typesafe.sbt.packager.archetypes.ServerLoader

name := "verticegateway"

version := "1.5"

scalaVersion := "2.11.8"

organization := "Megam Systems"

homepage := Some(url("https://www.megam.io"))

description := """Vertice Gateway : RESTful API gateway for Megam Vertice using HMAC authentication
Vertice gateway connects to an opensource database ScyllaDB 0.19 or latest,
compatible cassandra 2.1.9. A messaging layer via Nsqd (nsq.io) provides an
extra layer of decoupling from the virtualization or container platforms.
.
Vertice extends the benefits of OpenNebula virtualization platforms to allow
single click launch of application, high availability using ceph, autoscaling
and billing integrated.
.
This package contains playframework based API server managing ScyllaDB for
open source Megam Vertice."""


javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

initialize := {
  val _ = initialize.value
  if (sys.props("java.specification.version") != "1.8")
    sys.error("Java 8 is required for this project.")
}

javaOptions ++= Seq("-Dconfig.file=" + {
  val home  = System getenv "MEGAM_HOME"
  if (home == null || home.length <=0) sys.error("Must define MEGAM_HOME")
  val gwconfPath = Path(home)
  val gwconf = gwconfPath / "verticegateway" /  "gateway.conf"
  gwconf.toString
},
"-Dlogger.file=" + {
  val home  = System getenv "MEGAM_HOME"
  if (home == null || home.length <=0) sys.error("Must define MEGAM_HOME")
  val logconfPath = Path(home)
  val logconf = logconfPath / "verticegateway" /  "logger.xml"
  logconf.toString
})

scalacOptions := Seq(
  "-deprecation",
  "-feature",
  "-optimise",
  "-Xcheckinit",
  "-Xlint",
  "-Xverify",
  "-Yinline",
  "-Yclosure-elim",
  "-Yconst-opt",
  "-Ydelambdafy:method" ,
  "-Ybackend:GenBCode",
  "-language:implicitConversions",
  "-language:higherKinds",
  "-language:reflectiveCalls",
  "-language:postfixOps",
  "-language:implicitConversions",
  "-Ydead-code")


incOptions := incOptions.value.withNameHashing(true)

lazy val root = (project in file(".")).enablePlugins(PlayScala)

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"
resolvers += "Typesafe Snapshots" at "http://repo.typesafe.com/typesafe/snapshots/"
resolvers += "Scala-Tools Maven2 Snapshots Repository" at "http://scala-tools.org/repo-snapshots"
resolvers += "Spray repo" at "http://repo.spray.io"
resolvers += "Spy Repository" at "http://files.couchbase.com/maven2"
resolvers += "Bintray megamsys" at "https://dl.bintray.com/megamsys/scala/"
resolvers += "Websudos" at "https://dl.bintray.com/websudos/oss-releases/"


val phantomV = "1.16.0"

libraryDependencies ++= Seq(filters, cache,
  "org.yaml" % "snakeyaml" % "1.16",
  "io.megam" %% "libcommon" % "0.39",
  "io.megam" %% "newman" % "1.3.12",
  "com.typesafe.akka" %% "akka-slf4j" % "2.4.1",
  "com.websudos"      %%  "phantom-dsl"               % phantomV,
  "com.websudos"      %%  "phantom-testkit"           % phantomV,
  "com.websudos"      %%  "phantom-connectors"        % phantomV,
  "org.specs2" %% "specs2-core" % "3.7-scalaz-7.1.6" % "test",
  "org.specs2" % "specs2-matcher-extra_2.11" % "3.7-scalaz-7.1.6" % "test")

//routesGenerator := InjectedRoutesGenerator

enablePlugins(DebianPlugin)

enablePlugins(RpmPlugin)

NativePackagerKeys.defaultLinuxInstallLocation := "/usr/share/megam/"

NativePackagerKeys.defaultLinuxLogsLocation := "/var/log/megam"

version in Debian <<= (version, sbt.Keys.version) apply { (v, sv) =>
      val nums = (v split "[^\\d]")
      "%s" format (sv)
}


maintainer in Linux := "Rajthilak <rajthilak@megam.io> Yeshwanth Kumar <getyesh@megam.io>"

packageSummary in Linux := "REST based API server - Verticegateway for Megam Vertice."

packageDescription in Linux := "REST based API server which acts as the Gateway server for Megam vertice."

daemonUser in Linux := "megam" // user which will execute the application

daemonGroup in Linux := "megam"    // group which will execute the application

debianPackageDependencies in Debian ++= Seq("curl", "verticecommon")

linuxPackageMappings <+= (normalizedName, daemonUser in Linux, daemonGroup in Linux) map { (name, user, group) =>
      packageTemplateMapping("/var/run/megam/" + name)() withUser user withGroup group withPerms "755"
}

rpmVendor := "megam"

rpmUrl := Some("http://docs.megam.io/docs/vertice")

rpmLicense := Some("Apache v2")

packageArchitecture in Rpm := "x86_64"

serverLoading in Rpm := ServerLoader.Systemd

rpmPost := None // disables starting the server on install

linuxStartScriptTemplate in Rpm := (baseDirectory.value / "src" / "rpm" / "start").asURL
