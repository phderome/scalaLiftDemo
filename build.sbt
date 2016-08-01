name := "lcboViewer"

version := "1.0"

scalaVersion := "2.11.8"

organization := "ORG"

resolvers ++= Seq("snapshots"     at "https://oss.sonatype.org/content/repositories/snapshots",
                  "staging"       at "https://oss.sonatype.org/content/repositories/staging",
                  "releases"      at "https://oss.sonatype.org/content/repositories/releases")

unmanagedResourceDirectories in Test <+= baseDirectory { _ / "src/main/webapp" }

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-language:_"
)

ideaExcludeFolders += ".idea"

ideaExcludeFolders += ".idea_modules"

javaOptions in run += "-Xmn2G -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+PrintGC -XX:+PrintGCTimeStamps"

libraryDependencies ++= {
  val liftVersion = "2.6.2"
  val scalaCompiler = "2.11.8"
  Seq(
    "org.scala-lang"    % "scala-compiler"      % scalaCompiler,
    "org.scala-lang"    % "scala-reflect"       % scalaCompiler,
    "org.typelevel" %% "cats" % "0.6.1",
    "net.liftweb"     %% "lift-webkit" % liftVersion % "compile" withSources(),
    "net.liftweb"     %% "lift-mapper" % liftVersion % "compile->default" withSources(),
    "net.liftweb"     %% "lift-wizard" % liftVersion % "compile->default" withSources(),
    "net.liftweb"     %% "lift-squeryl-record" % liftVersion % "compile->default" withSources(), // Record interface to RDBMS,
    "net.liftmodules" %% "lift-jquery-module_2.6" % "2.8" withSources(),
    "postgresql"        % "postgresql"          % "9.1-901.jdbc4",
    "org.scalatest"     %% "scalatest"      % "2.2.6"            % Test,
    "org.apache.httpcomponents" % "httpclient"  % "4.5.2",
    "org.skinny-framework" %% "skinny-http-client" % "2.2.0",
    "org.eclipse.jetty" %  "jetty-webapp" % "9.1.0.v20131115"   % "container,test",  // Needed to run RunWebApp.scala in IDEA
    "org.eclipse.jetty" %  "jetty-plus"   % "9.1.0.v20131115"  % "container,test", // For Jetty Config
    "org.specs2"        %% "specs2"             % "2.3.12"           % Test,
    "ch.qos.logback"    % "logback-classic"     % "1.0.13"
  )
}

enablePlugins(JettyPlugin)  // so we can do jetty:start jetty:stop in sbt https://github.com/earldouglas/xsbt-web-plugin/blob/master/docs/2.0.md

containerPort := 8090  // applicable when running from sbt, not with the jetty container plug-in in IDEA (which uses 8080 and not this variable).
