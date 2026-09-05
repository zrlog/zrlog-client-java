#!/bin/sh
set -eu

release_root=https://dl.zrlog.com/ctl/release
install_path=/usr/local/bin/zrlogctl

if [ "$(uname -s)" != Linux ] || [ "$(uname -m)" != x86_64 ]; then
  echo "zrlogctl supports Linux AMD64 only" >&2
  exit 1
fi
for command in curl sha256sum mktemp install; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "required command not found: ${command}" >&2
    exit 1
  fi
done

temporary_directory=$(mktemp -d)
trap 'rm -rf -- "${temporary_directory}"' EXIT HUP INT TERM
manifest_file=${temporary_directory}/latest.json
binary_file=${temporary_directory}/zrlogctl-linux-amd64

curl --proto '=https' --tlsv1.2 --fail --silent --show-error --location --retry 3 \
  --output "${manifest_file}" "${release_root}/latest.json"

version=$(sed -n 's/^[[:space:]]*"version":[[:space:]]*"\([^"]*\)".*/\1/p' "${manifest_file}")
download_url=$(sed -n 's/^[[:space:]]*"url":[[:space:]]*"\([^"]*\)".*/\1/p' "${manifest_file}")
expected_sha256=$(sed -n 's/^[[:space:]]*"sha256":[[:space:]]*"\([0-9a-f]*\)".*/\1/p' "${manifest_file}")
expected_size=$(sed -n 's/^[[:space:]]*"size":[[:space:]]*\([0-9][0-9]*\).*/\1/p' "${manifest_file}")

if ! printf '%s\n' "${version}" | awk -F. 'NF == 3 && $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ && $3 ~ /^[0-9]+$/ { found = 1 } END { exit !found }'; then
  echo "invalid zrlogctl release version" >&2
  exit 1
fi
expected_url=${release_root}/${version}/zrlogctl-linux-amd64
if [ "${download_url}" != "${expected_url}" ]; then
  echo "invalid zrlogctl download URL" >&2
  exit 1
fi
if [ "${#expected_sha256}" -ne 64 ]; then
  echo "invalid zrlogctl SHA-256" >&2
  exit 1
fi
case ${expected_sha256} in
  *[!0-9a-f]*) echo "invalid zrlogctl SHA-256" >&2; exit 1 ;;
esac
case ${expected_size} in
  ''|*[!0-9]*) echo "invalid zrlogctl file size" >&2; exit 1 ;;
esac

curl --proto '=https' --tlsv1.2 --fail --silent --show-error --location --retry 3 \
  --output "${binary_file}" "${download_url}"
actual_size=$(wc -c < "${binary_file}" | tr -d '[:space:]')
if [ "${actual_size}" != "${expected_size}" ]; then
  echo "zrlogctl file size verification failed" >&2
  exit 1
fi
if ! printf '%s  %s\n' "${expected_sha256}" "${binary_file}" | sha256sum --check --status; then
  echo "zrlogctl SHA-256 verification failed" >&2
  exit 1
fi

if [ "$(id -u)" -eq 0 ]; then
  install -m 755 "${binary_file}" "${install_path}"
elif command -v sudo >/dev/null 2>&1; then
  sudo install -m 755 "${binary_file}" "${install_path}"
else
  echo "root privileges or sudo are required to install ${install_path}" >&2
  exit 1
fi

echo "Installed zrlogctl ${version} to ${install_path}"
