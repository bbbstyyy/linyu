#!/usr/bin/env bash
# Sign only a validated release APK; passwords are passed through the environment.
set -euo pipefail
umask 077
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
unsigned_apk="${1:?Usage: sign_release.sh unsigned.apk output-directory}"
output_dir="${2:?Output directory required}"
: "${ANDROID_HOME:?Android SDK path required}"
: "${ANDROID_KEYSTORE_PATH:?Signing keystore path required}"
: "${ANDROID_KEYSTORE_PASSWORD:?Keystore password required}"
: "${ANDROID_KEY_ALIAS:?Key alias required}"
: "${ANDROID_KEY_PASSWORD:?Key password required}"
: "${ANDROID_SIGNING_CERT_SHA256:?Expected certificate SHA-256 required}"
build_tools="$ANDROID_HOME/build-tools/36.0.0"
python3 "$project_dir/scripts/check_release.py" --apk "$unsigned_apk" --aapt "$build_tools/aapt"
version="$(python3 -c 'import sys; sys.path.insert(0, sys.argv[1]); from check_release import version; print(version()["version"])' "$project_dir/scripts")"
expected="$(printf '%s' "$ANDROID_SIGNING_CERT_SHA256" | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo 'Invalid signing certificate fingerprint' >&2; exit 1; }
mkdir -p "$output_dir"
target="$output_dir/shower-helper-$version.apk"
[[ ! -e "$target" && ! -e "$output_dir/SHA256SUMS" && ! -e "$output_dir/SIGNING_CERT.txt" ]] || {
    echo 'Refusing to overwrite existing release assets' >&2; exit 1;
}
sign_tmp="$(mktemp -d)"
trap 'rm -rf "$sign_tmp"' EXIT
"$build_tools/zipalign" -f -p 4 "$unsigned_apk" "$sign_tmp/aligned.apk"
"$build_tools/apksigner" sign --ks "$ANDROID_KEYSTORE_PATH" --ks-key-alias "$ANDROID_KEY_ALIAS" \
    --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
    --out "$sign_tmp/signed.apk" "$sign_tmp/aligned.apk"
"$build_tools/apksigner" verify --verbose --print-certs "$sign_tmp/signed.apk" > "$sign_tmp/certificate.txt"
actual="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$sign_tmp/certificate.txt" | tr '[:upper:]' '[:lower:]')"
[[ "$actual" == "$expected" ]] || { echo 'Signing certificate does not match the expected fingerprint' >&2; exit 1; }
if grep -qi 'CN=Android Debug' "$sign_tmp/certificate.txt"; then
    echo 'Refusing an Android debug signing certificate' >&2; exit 1
fi
"$build_tools/zipalign" -c -p 4 "$sign_tmp/signed.apk"
python3 "$project_dir/scripts/check_release.py" --apk "$sign_tmp/signed.apk" --aapt "$build_tools/aapt"
cp "$sign_tmp/signed.apk" "$target"
printf 'Signer certificate SHA-256: %s\n' "$actual" > "$output_dir/SIGNING_CERT.txt"
(cd "$output_dir" && sha256sum "shower-helper-$version.apk" SIGNING_CERT.txt > SHA256SUMS)
echo "Verified release assets prepared in $output_dir"
