#!/usr/bin/env bash
# Prints the bcrypt hash for the terminal login (GATEWAY_ADMIN_PASSWORD_HASH in infra/compose/.env).
# The password is read without echo and never printed; only the hash goes to stdout.
# Usage: scripts/gateway-password-hash.sh   (prompts twice; a piped stdin password also works)
# In .env wrap the hash in single quotes, or compose will treat each $ as a variable:
#   GATEWAY_ADMIN_PASSWORD_HASH='$2a$12$...'
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f infra/compose/compose.yaml --env-file infra/compose/.env.example)
image="$("${COMPOSE[@]}" config --images | grep gateway-bff | head -n 1)"
# Always build (cached when unchanged): an older local image may not contain PasswordHashCli.
echo "Building $image ..." >&2
"${COMPOSE[@]}" build gateway-bff >&2

if [[ -t 0 ]]; then
  IFS= read -r -s -p "Password: " password; echo >&2
  IFS= read -r -s -p "Repeat:   " repeat; echo >&2
  [[ "$password" == "$repeat" ]] || { echo "Passwords differ." >&2; exit 1; }
else
  IFS= read -r password || [[ -n "$password" ]]
fi
[[ -n "$password" ]] || { echo "Empty password." >&2; exit 1; }

# The image entrypoint is `java -jar`; PropertiesLauncher runs PasswordHashCli from inside the Boot jar.
printf '%s\n' "$password" | docker run --rm -i --entrypoint java \
  "$image" \
  -Dloader.main=com.vajra.gatewaybff.auth.PasswordHashCli -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
