#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 OUTPUT_ROOT RELEASE_VERSION" >&2
  exit 1
fi
output_root=$1
release_version=$2
if [[ ! "${release_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "RELEASE_VERSION must be MAJOR.MINOR.BUILD_NUMBER" >&2
  exit 1
fi

release_root="${output_root%/}/ctl/release"
version_root="${release_root}/${release_version}"
binary_name=zrlogctl-linux-amd64
binary_file="${version_root}/${binary_name}"

# Exercise the final executable, including service processing, before publishing metadata.
python3 bin/test-native-openapi.py "${binary_file}"
python3 bin/test-native-proxy.py "${binary_file}"
"${binary_file}" --version

install -m 755 bin/install.sh "${output_root%/}/ctl/install.sh"
(
  cd "${version_root}"
  sha256sum "${binary_name}" > "${binary_name}.sha256"
)

checksum=$(awk '{print $1}' "${version_root}/${binary_name}.sha256")
size=$(stat -c '%s' "${binary_file}")
cat > "${release_root}/latest.json" <<EOF
{
  "version": "${release_version}",
  "url": "https://dl.zrlog.com/ctl/release/${release_version}/${binary_name}",
  "sha256": "${checksum}",
  "size": ${size}
}
EOF

echo "release files: ${release_root}"
