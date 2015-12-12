name := "lcboViewer"

version := "1.0"

scalaVersion := "2.11.7"

resolvers ++= Seq("snapshots"     at "https://oss.sonatype.org/content/repositories/snapshots",
                  "staging"       at "https://oss.sonatype.org/content/repositories/staging",
                  "releases"      at "https://oss.sonatype.org/content/repositories/releases"
                 )

unmanagedResourceDirectories in Test <+= (baseDirectory) { _ / "src/main/webapp" }

scalacOptions ++= Seq("-deprecation", "-explaintypes", "-feature", "-unchecked", "-Dusejavacp=true")

// log4j/slf4j is not standard for liftweb so we use ch.qos.logback (https://www.assembla.com/wiki/show/liftweb/Logging)
// We use postgresql in place of simple DB    "com.h2database"    % "h2"                  % "1.3.167"
// For Twitter Bootstrap, possibly try (in vain?)     "net.liftmodules" %% "fobo_2.6"    % "1.4"       % "compile",

libraryDependencies ++= {
  val liftVersion = "2.6.2"
  Seq(
    "net.liftweb" %% "lift-webkit" % liftVersion % "compile" withSources(),
    "net.liftweb" %% "lift-mapper" % liftVersion % "compile->default" withSources(),
    "net.liftweb" %% "lift-wizard" % liftVersion % "compile->default" withSources(),
    "net.liftmodules" %% "lift-jquery-module_2.6" % "2.8" withSources())
}

libraryDependencies ++= Seq (
    "postgresql"        % "postgresql"          % "9.1-901.jdbc4",
    "org.eclipse.jetty" % "jetty-webapp"        % "8.1.7.v20120910"  % "container,test",
    "org.eclipse.jetty" % "jetty-plus"          % "8.1.7.v20120910"  % "container,test", // For Jetty Config
    "org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016" % "container,test" artifacts Artifact("javax.servlet", "jar", "jar"),
    "org.specs2"        %% "specs2"             % "2.3.12"             % "test",
    "ch.qos.logback" % "logback-classic" % "1.0.13"
  )

// by default, it listens on port 8080; use the following to override
//port in container.Configuration := 8081

enablePlugins(JettyPlugin)

