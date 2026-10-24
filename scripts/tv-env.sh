# Sourced by the tv-* scripts: which TV to talk to. TV=<ip>:5555 on the command line wins, else the
# `tv=` line in local.properties.
TV=${TV:-$(sed -n 's/^tv=//p' "$(dirname "$0")/../local.properties" 2>/dev/null)}
if [ -z "$TV" ]; then
  echo "Which TV? Run with TV=<ip>:5555, or add tv=<ip>:5555 to local.properties." >&2
  exit 1
fi
