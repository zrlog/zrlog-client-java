#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 || ( $# -eq 2 && "$2" != "--prepare-only" ) ]]; then
  echo "usage: $0 OUTPUT_ROOT [--prepare-only]" >&2
  exit 1
fi
output_root=$1

if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
  echo "zrlogctl release builds require Linux AMD64" >&2
  exit 1
fi
build_number=${BUILD_NUMBER:-}
if [[ -z "${build_number}" ]]; then
  build_number=$(git rev-list --all --count)
fi
if [[ ! "${build_number}" =~ ^[0-9]+$ ]]; then
  echo "BUILD_NUMBER must be a non-negative integer" >&2
  exit 1
fi
if ! command -v native-image >/dev/null 2>&1; then
  echo "GraalVM Native Image for Java 25 is required" >&2
  exit 1
fi

java_specification_version=$(java -XshowSettings:properties -version 2>&1 \
  | awk -F'= ' '/java.specification.version/ {print $2; exit}')
if [[ "${java_specification_version}" != "25" ]]; then
  echo "Java 25 is required; found ${java_specification_version:-unknown}" >&2
  exit 1
fi
native_image_version=$(native-image --version 2>&1)
if [[ ! "${native_image_version}" =~ (^|[[:space:]])25\. ]]; then
  echo "GraalVM Native Image 25 is required; found ${native_image_version:-unknown}" >&2
  exit 1
fi

release_version=$(./mvnw --quiet \
  -DbuildNumber="${build_number}" \
  help:evaluate \
  -Dexpression=project.version \
  -DforceStdout)
if [[ ! "${release_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "pom.xml must resolve project.version to MAJOR.MINOR.BUILD_NUMBER; found ${release_version:-empty}" >&2
  exit 1
fi

release_root="${output_root%/}/ctl/release"
version_root="${release_root}/${release_version}"
binary_name=zrlogctl-linux-amd64

./mvnw \
  -DbuildNumber="${build_number}" \
  -Dproject.build.outputTimestamp=2013-01-01T00:00:00Z \
  -Dmaven.test.skip=false \
  -DskipTests=false \
  -Pnative \
  clean package

mkdir -p "${version_root}"
install -m 755 target/zrlogctl "${version_root}/${binary_name}"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "artifact_file=${version_root}/${binary_name}"
    echo "artifact_name=zrlogctl"
    echo "artifact_version=${release_version}"
    echo "artifact_architecture=Linux-amd64"
  } >> "${GITHUB_OUTPUT}"
fi

if [[ "${2:-}" == "--prepare-only" ]]; then
  echo "binary ready for processing: ${version_root}/${binary_name}"
  exit 0
fi

bash bin/finalize-linux-amd64.sh "${output_root}" "${release_version}"
