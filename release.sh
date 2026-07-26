#!/bin/bash

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <new_version>"
    exit 1
fi

NEW_VERSION="$1"

set -x

git tag -a "v${NEW_VERSION}" -m "Version ${NEW_VERSION}"; git push --tags; git push
