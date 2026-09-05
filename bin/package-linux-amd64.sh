#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 OUTPUT_ROOT" >&2
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
native_image_version=$(native-image --version 2>&1 | head -n 1)
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
(
  cd "${version_root}"
  sha256sum "${binary_name}" > "${binary_name}.sha256"
)

checksum=$(awk '{print $1}' "${version_root}/${binary_name}.sha256")
size=$(stat -c '%s' "${version_root}/${binary_name}")
cat > "${release_root}/latest.json" <<EOF
{
  "version": "${release_version}",
  "url": "https://dl.zrlog.com/ctl/release/${release_version}/${binary_name}",
  "sha256": "${checksum}",
  "size": ${size}
}
EOF

"${version_root}/${binary_name}" --version
echo "release files: ${release_root}"
