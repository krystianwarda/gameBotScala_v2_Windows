FROM jenkins/jenkins:lts-jdk17

USER root

# Install sbt, git, and other tools Jenkins will use
RUN apt-get update && \
    apt-get install -y curl gnupg git && \
    curl -L -o sbt.deb https://repo.scala-sbt.org/scalasbt/debian/sbt-1.9.9.deb && \
    apt-get install -y ./sbt.deb && \
    rm sbt.deb && \
    apt-get clean

# Create workspace directory and set permissions
RUN mkdir -p /workspace/scalaSegment && \
    chown -R jenkins:jenkins /workspace && \
    chmod -R 777 /workspace

# Switch back to Jenkins user
# USER jenkins

# Pre-install Jenkins plugins for pipelines and Git
RUN jenkins-plugin-cli --plugins "workflow-aggregator git"

# Default Jenkins home directory
ENV JENKINS_HOME=/var/jenkins_home