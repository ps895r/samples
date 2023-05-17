#!/bin/bash

set -e

# Check that all checksums in manifest match tar directory checksums

pushd $1
shalist=()
# Builds a string with file name and path followed by checksum. Compared in following loop for match
shopt -s globstar
for file in ./**; do
    if [ -f "$file" ]; then
        sha="$(openssl sha256 "$(readlink -f "./$file")" | awk '{print $NF}')"
        filename=$(find "$file")
        manifestlist="${filename} ${sha}"
        shalist+=("$manifestlist")
    fi
done
popd
for sha in "${shalist[@]}"; do
    if grep "$sha" "$2"; then
        echo "$sha - matching file and checksum found...continue"
        continue
    else
        echo "$sha - missing from manifest...exit"
        exit 1
    fi
done
