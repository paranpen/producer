addSbtPlugin("com.github.gseitz"    % "sbt-release"           % "1.0.0")
addSbtPlugin("com.jsuereth"         % "sbt-pgp"               % "1.0.0")
addSbtPlugin("com.typesafe.sbt"     % "sbt-ghpages"           % "0.5.4")
addSbtPlugin("com.typesafe.sbt"     % "sbt-site"              % "0.8.2")
addSbtPlugin("com.typesafe.sbt"     % "sbt-git"               % "0.8.5")
addSbtPlugin("org.xerial.sbt"       % "sbt-sonatype"          % "1.1")
addSbtPlugin("com.ambiata"          % "promulgate"            % "0.11.0-20150727222014-93879fa")
addSbtPlugin("ohnosequences"        % "sbt-github-release"    % "0.4.0")

resolvers += Resolver.url("ambiata-oss", new URL("https://ambiata-oss.s3.amazonaws.com"))(Resolver.ivyStylePatterns)
resolvers += "Era7 maven releases" at "http://releases.era7.com.s3.amazonaws.com"
resolvers += "repo.jenkins-ci.org" at "http://repo.jenkins-ci.org/public"
