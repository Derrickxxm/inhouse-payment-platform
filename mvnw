#!/bin/sh
# Bootstrap the Apache Maven Wrapper from Maven Central.
set -eu
BASE_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_DIR="$BASE_DIR/.mvn/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/maven-wrapper.jar"
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA=java
fi
if [ ! -f "$WRAPPER_JAR" ]; then
  URL=$(sed -n 's/^wrapperUrl=//p' "$WRAPPER_DIR/maven-wrapper.properties")
  TEMP_JAR=$(mktemp "$WRAPPER_DIR/maven-wrapper.jar.XXXXXX")
  trap 'rm -f "$TEMP_JAR"' EXIT HUP INT TERM
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error "$URL" -o "$TEMP_JAR"
  else
    wget -q "$URL" -O "$TEMP_JAR"
  fi
  mv "$TEMP_JAR" "$WRAPPER_JAR"
  trap - EXIT HUP INT TERM
fi
exec "$JAVA" "-Dmaven.multiModuleProjectDirectory=$BASE_DIR" \
  -classpath "$WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"
