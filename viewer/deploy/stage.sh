#!/usr/bin/env bash
# Dev side: copy the committed viewer (HEAD) to ~/camcast-viewer-staging on the Pi. Secrets are staged separately.
# Then on the Pi: sudo bash ~/camcast-viewer-staging/deploy/install.sh
set -euo pipefail
PI="${PI:-claude@192.168.0.12}"
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
git archive HEAD:viewer camcast_viewer web deploy VERSION | \
  ssh "$PI" 'umask 077; d=~/camcast-viewer-staging; f=$d/upstream; t=$(mktemp); [ -f $f ] && cp $f $t;
             rm -rf $d && mkdir -m 700 $d && tar -x -C $d; [ -s $t ] && cp $t $f; rm -f $t; ls $d'
echo "staged $(git rev-parse --short HEAD) on $PI"
